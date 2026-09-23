package com.dropsync.domain.sensor.calibration

import com.dropsync.domain.sensor.SensorSample
import com.dropsync.domain.sensor.accelMagnitude
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Which candidate signal a calibration sweep chose (ExerciseProfile.chosenSignal). */
enum class ChosenSignal { GP, COMBINED, GYRO_MAG }

/** Rest-gate quality thresholds (Konzept Guided Calibration 2.0, §3 Stufe 0). */
object CalibrationThresholds {
    const val REST_MIN_SECONDS = 2.0
    const val REST_GYRO_MEAN_MAX_DEG_PER_SEC = 15.0
    const val REST_ACCEL_SIGMA_MAX_G = 0.05
    const val MIN_ACTIVITY_SAMPLES = 5

    /**
     * Untergrenze fuer die Bewegungs-Schwelle der Achsenanalyse (deg/s).
     * Zugleich die Schwelle, ab der die Live-Schaetzung im Einzel-Rep eine
     * Bewegung sieht.
     */
    const val MIN_ACTIVITY_GYRO_DEG_PER_SEC = 15.0
}

/** Live rest-gate snapshot for the calibration wizard UI. */
data class RestGateSnapshot(
    val seconds: Double,
    val n: Int,
    val gyroMagMean: Double,
    val sigmaAccel: Double,
    val gateOk: Boolean,
    val minSecondsReached: Boolean,
) {
    /** Ready to tap "Weiter" with a high chance of passing. */
    val ready: Boolean
        get() = gateOk && minSecondsReached
}

/**
 * RC-8: nachrechenbare Fakten der fertigen Kalibrierung fuer die
 * Review-Erklaerung im Wizard. Der Wizard zeigt daraus Saetze wie "4 von 5
 * Wiederholungen wiedergefunden" oder "Schwelle nur 2.1-fach ueber dem
 * Ruherauschen" — statt nur einer Prozentzahl, mit der niemand etwas
 * anfangen kann.
 *
 * Bewusst nur Fakten, keine Bewertung und keine Texte: dieses Modul kennt
 * weder Gebietsschema noch Ressourcen. Was ein Wert bedeutet, entscheidet
 * die UI-Schicht.
 */
data class CalibrationReview(
    /** Mit der gelernten Schwelle wiedergefundene Reps im 5er-Satz. */
    val detectedRepsKnownSet: Int,
    /** Soll-Zahl des 5er-Satzes (aus dem Wizard, i. d. R. 5). */
    val expectedRepsKnownSet: Int,
    /** Wiedergefundene Reps im Langsam-Satz; null ohne Langsam-Satz. */
    val detectedRepsSlowSet: Int?,
    /** Soll-Zahl des Langsam-Satzes; null ohne Langsam-Satz. */
    val expectedRepsSlowSet: Int?,
    /** Streuung der Rep-Abstaende im 5er-Satz (std/mean); null bei < 2 Reps. */
    val intervalCv: Double?,
    /**
     * Abstand der Schwelle zum Ruherauschen in Sigma des gewaehlten Signals:
     * (theta - baseline) / max(noiseFloor, eps). Kleine Werte bedeuten, dass
     * Rauschen und Rep-Spitze nah beieinander liegen — dann zaehlt die
     * Schwelle empfindlich.
     */
    val thresholdOverNoise: Double,
    /** True, wenn die gemessene Accel-Schwelle > 0 ist (Zweitkanal aktiv). */
    val accelVotingEnabled: Boolean,
    /** Erwartete Rep-Dauer in Sekunden (Median der Abstaende). */
    val expectedRepSeconds: Double,
)

/** Result of a completed guided calibration (persisted per exercise+device). */
data class GuidedCalibrationResult(
    val rotationAxis: List<Double>,
    val gyroBias: List<Double>,
    val chosenSignal: ChosenSignal,
    val theta: Double,
    val baseline: Double,
    /** Measured rest sigma of the chosen signal (noise floor). */
    val noiseFloor: Double,
    val expectedProminence: Double,
    val expectedDurationSamples: Double,
    val expectedDurationMs: Double,
    val repTemplate: List<Double>,
    val qualityScore: Double,
    /**
     * P2-Fix #22: kalibrierte Schwelle des Accel-Kanals (Abweichung der
     * Magnitude von 1 g). 0.0 bedeutet "nicht kalibriert" — dann bleibt das
     * Accel-Voting aus, statt mit einem geratenen Wert gute Wiederholungen
     * zu verwerfen.
     */
    val accelThreshold: Double = 0.0,
    /** RC-8: nachrechenbare Fakten fuer die Review-Erklaerung im Wizard. */
    val review: CalibrationReview? = null,
)

/**
 * Rep-Markierung des Kantenzaehlers ([zaehleEdge]): Sample-Index und Hoehe
 * der validierten Exkursion.
 */
internal data class RepMark(
    val sampleIndex: Int,
    val height: Double,
)

/**
 * Guided Calibration 2.0 (port of calibration_controller.dart, Konzept
 * §3 Stufen 0/A/B/C/D). Pure JVM, no Android: the wizard UI feeds samples
 * via [onSample] and advances stages via [finishStage].
 *
 * Core idea: the calibration gets the truth as a KNOWN COUNT (1 rep, 5 reps,
 * 3 slow reps) and optimizes its counting parameters to reproduce that truth
 * instead of guessing at its own detection (root causes K1-K4).
 */
class CalibrationController(
    val sampleRateHz: Double = 50.0,
    var knownSetCount: Int = 5,
    var slowSetCount: Int = 3,
) {
    /** Stages of Guided Calibration 2.0 (Konzept §3). */
    enum class Stage { REST, SINGLE_REP, KNOWN_SET, SLOW_SET, REVIEW, DONE, FAILED }

    var stage = Stage.REST
        private set
    var isRunning = false
        private set

    private val bufRest = mutableListOf<SensorSample>()
    private val bufA = mutableListOf<SensorSample>()
    private val bufB = mutableListOf<SensorSample>()
    private val bufC = mutableListOf<SensorSample>()

    private var rest: RestStats? = null
    private var axisResult: AxisResult? = null
    private var signalsB: Map<ChosenSignal, DoubleArray>? = null
    private var signalsC: Map<ChosenSignal, DoubleArray>? = null
    private var metaB: Map<ChosenSignal, Pair<Double, Double>>? = null
    private var sweepCfg: SweepCfg? = null
    private var thetaFinal: Double? = null
    private var baselineChosen: Double? = null
    private var quality = 0.0

    /** Number of collected samples in the current collecting stage (UI progress). */
    val bufferedSampleCount: Int
        get() =
            when (stage) {
                Stage.REST -> bufRest.size
                Stage.SINGLE_REP -> bufA.size
                Stage.KNOWN_SET -> bufB.size
                Stage.SLOW_SET -> bufC.size
                else -> 0
            }

    /** Live rest-gate metrics for the wizard UI (null outside REST / empty). */
    val liveRestGate: RestGateSnapshot?
        get() {
            if (stage != Stage.REST || bufRest.isEmpty()) return null
            val stats = restStats(bufRest, sampleRateHz)
            val seconds = bufRest.size / sampleRateHz
            return RestGateSnapshot(
                seconds = seconds,
                n = stats.n,
                gyroMagMean = stats.gyroMagMean,
                sigmaAccel = stats.sigmaAccel,
                gateOk = stats.gateOk,
                minSecondsReached = seconds >= CalibrationThresholds.REST_MIN_SECONDS,
            )
        }

    /**
     * RC-8: Live-Schaetzung der im aktuellen Puffer sichtbaren Reps fuer die
     * Stufen-Anzeige ("Reps erkannt: n"). null in Stufen ohne Zaehlung.
     *
     * Bewusst eine **Schaetzung fuer die Fuehrung**, keine Zaehlung: sie
     * laeuft auf denselben Puffern und derselben Zaehlfunktion wie die
     * Stufen-Auswertung, aber mit den zu diesem Zeitpunkt besten Parametern.
     * Vor Abschluss von Stufe B existiert noch keine gelernte Schwelle; dort
     * dient derselbe Startwert, mit dem der Sweep beginnt (Baseline + 3σ).
     * Erst der Abschluss der Stufe entscheidet — und im Review korrigiert der
     * Nutzer die tatsaechlich ausgeuehrte Zahl.
     */
    val liveRepEstimate: Int?
        get() =
            when (stage) {
                Stage.SINGLE_REP -> {
                    val restS = rest ?: return null
                    CalibrationLiveEstimator.activityBursts(bufA, restS.gyroBias, restS.sigmaGyro)
                }

                Stage.KNOWN_SET -> {
                    liveCount(bufB, useLearnedCfg = false)
                }

                Stage.SLOW_SET -> {
                    liveCount(bufC, useLearnedCfg = true)
                }

                else -> {
                    null
                }
            }

    /** Live-Schaetzung fuer Stufen B/C auf der GP-Projektion (siehe Estimator). */
    private fun liveCount(
        buf: List<SensorSample>,
        useLearnedCfg: Boolean,
    ): Int? {
        val axis = axisResult ?: return null
        val restS = rest ?: return null
        return CalibrationLiveEstimator.provisionalRepCount(
            buf = buf,
            achse = axis.achse,
            bias = restS.gyroBias,
            sampleRateHz = sampleRateHz,
            sweepCfg = sweepCfg,
            baselineChosen = baselineChosen,
            useLearnedCfg = useLearnedCfg,
            provisionalRefractoryS = 0.35 * axis.t0,
        )
    }

    /**
     * RC-8: "Stufe wiederholen" — leert den Puffer der aktuellen Stufe und
     * verwirft, was daraus schon abgeleitet wurde. Die Stufe selbst bleibt
     * stehen (kein Ruecksprung, keine verlorenen Vorergebnisse).
     */
    fun repeatStage() {
        redoFrom(stage)
    }

    /**
     * RC-8: springt auf eine fruehere Sammel-Stufe zurueck und verwirft
     * alles, was von ihr abhaengt — Puffer, abgeleitete Ergebnisse und
     * spaetere Stufen. Gedacht fuer das Review: ein misslungenes 5er-Set
     * laesst sich wiederholen, ohne Ruhe und Einzel-Rep erneut zu machen.
     *
     * Erlaubt sind nur die Sammel-Stufen bis einschliesslich SLOW_SET und nur
     * rueckwaerts (auf eine spaetere Stufe "zurueck" zu springen wuerde
     * Ergebnisse vortaeuschen, die es nicht gibt). REVIEW/DONE/FAILED sind
     * keine Ziele — dafuer gibt es [start].
     */
    fun redoFrom(target: Stage) {
        if (!isRunning) return
        val order =
            listOf(
                Stage.REST,
                Stage.SINGLE_REP,
                Stage.KNOWN_SET,
                Stage.SLOW_SET,
                Stage.REVIEW,
                Stage.DONE,
                Stage.FAILED,
            )
        val targetIndex = order.indexOf(target)
        val currentIndex = order.indexOf(stage)
        val lastCollecting = order.indexOf(Stage.SLOW_SET)
        if (targetIndex < 0 || targetIndex > lastCollecting) return
        if (currentIndex < targetIndex) return

        // Puffer der Ziel-Stufe und aller spaeteren Sammel-Stufen verwerfen.
        if (targetIndex <= 0) bufRest.clear()
        if (targetIndex <= 1) bufA.clear()
        if (targetIndex <= 2) bufB.clear()
        if (targetIndex <= 3) bufC.clear()

        // Abgeleitetes verwerfen, das auf den verworfenen Puffern beruht.
        if (targetIndex <= 0) rest = null
        if (targetIndex <= 1) axisResult = null
        if (targetIndex <= 2) {
            signalsB = null
            metaB = null
            sweepCfg = null
            thetaFinal = null
            baselineChosen = null
            quality = 0.0
        }
        if (targetIndex <= 3) signalsC = null

        stage = target
    }

    /** Starts a new calibration in stage REST (discards all buffers). */
    fun start() {
        stage = Stage.REST
        isRunning = true
        bufRest.clear()
        bufA.clear()
        bufB.clear()
        bufC.clear()
        rest = null
        axisResult = null
        signalsB = null
        signalsC = null
        metaB = null
        sweepCfg = null
        thetaFinal = null
        baselineChosen = null
        quality = 0.0
    }

    /** Feeds one IMU sample into the current collecting stage. */
    fun onSample(sample: SensorSample) {
        if (!isRunning) return
        when (stage) {
            Stage.REST -> bufRest.add(sample)
            Stage.SINGLE_REP -> bufA.add(sample)
            Stage.KNOWN_SET -> bufB.add(sample)
            Stage.SLOW_SET -> bufC.add(sample)
            else -> Unit
        }
    }

    /**
     * Ends the current stage and evaluates its buffer. Returns a
     * [CalibrationFailure] when a quality gate failed (stage is repeated),
     * null on success.
     *
     * P3-Fix #27: der Rueckgabewert traegt nur noch Grund und Messwerte. Den
     * anzuzeigenden Text baut die UI-Schicht daraus zusammen — dieses Modul
     * hat keine Ressourcen und kein Gebietsschema.
     */
    fun finishStage(): CalibrationFailure? {
        if (!isRunning) return null
        return when (stage) {
            Stage.REST -> {
                finishRest()
            }

            Stage.SINGLE_REP -> {
                finishSingleRep()
            }

            Stage.KNOWN_SET -> {
                finishKnownSet()
            }

            Stage.SLOW_SET -> {
                finishSlowSet()
            }

            Stage.REVIEW -> {
                stage = Stage.DONE
                null
            }

            else -> {
                null
            }
        }
    }

    /** Stage D: the user corrects the actually performed count; re-optimizes. */
    fun userCorrectCount(
        forStage: Stage,
        count: Int,
    ): Boolean {
        if (count < 1 || stage != Stage.REVIEW) return false
        if (forStage == Stage.KNOWN_SET) {
            knownSetCount = count
            if (bufB.isEmpty() || axisResult == null || rest == null) return false
            runBSweep()
            if (sweepCfg != null && bufC.isNotEmpty()) runC()
            return sweepCfg != null
        }
        if (forStage == Stage.SLOW_SET) {
            slowSetCount = count
            if (sweepCfg == null || bufC.isEmpty()) return false
            runC()
            return true
        }
        return false
    }

    /** Builds the [GuidedCalibrationResult] from the learned parameters. */
    fun finalize(): GuidedCalibrationResult? {
        val cfg = sweepCfg ?: return null
        val theta = thetaFinal ?: return null
        val axis = axisResult ?: return null
        val restS = rest ?: return null
        val baseline = baselineChosen ?: return null
        val sigB = signalsB?.get(cfg.signal) ?: return null

        val marks = zaehleEdge(sigB, sampleRateHz, theta, cfg.refractoryS, baseline, prominenz = cfg.prominenz)
        val intervals =
            (1 until marks.size).map { i ->
                (marks[i].sampleIndex - marks[i - 1].sampleIndex) / sampleRateHz
            }
        val medT = if (intervals.isNotEmpty()) median(intervals) else axis.t0

        // Template extraction: windows around each peak (+/- half template).
        val templateLen = 64
        val half = templateLen / 2
        val windows =
            marks.mapNotNull { mark ->
                val start = max(0, mark.sampleIndex - half)
                val end = min(sigB.size, mark.sampleIndex + half)
                if (end - start >= templateLen / 2) {
                    sigB.copyOfRange(start, end).toList()
                } else {
                    null
                }
            }
        val repTemplate = extractMedianTemplate(windows, templateLen)

        val expectedProminence =
            if (marks.size >= 2) median(marks.map { it.height }) else cfg.prominenz

        // RC-8: Fakten fuer die Review-Erklaerung. Alles nachrechenbar aus
        // demselben Durchlauf, den der Wizard gerade bewertet.
        val noiseFloor = metaB?.get(cfg.signal)?.second ?: 0.0
        val intervalsMean = mean(intervals)
        val intervalCv =
            if (intervals.size > 1 && intervalsMean > 0) std(intervals) / intervalsMean else null
        val slowSig = signalsC?.get(cfg.signal)
        val slowCount =
            if (slowSig != null && bufC.isNotEmpty()) {
                zaehleEdge(slowSig, sampleRateHz, theta, cfg.refractoryS, baseline, prominenz = cfg.prominenz).size
            } else {
                null
            }
        val accelThreshold = calibrateAccelThreshold(marks)
        val review =
            CalibrationReview(
                detectedRepsKnownSet = marks.size,
                expectedRepsKnownSet = knownSetCount,
                detectedRepsSlowSet = slowCount,
                expectedRepsSlowSet = if (slowCount != null) slowSetCount else null,
                intervalCv = intervalCv,
                thresholdOverNoise = (theta - baseline) / max(noiseFloor, REVIEW_NOISE_FLOOR_EPS),
                accelVotingEnabled = accelThreshold > 0.0,
                expectedRepSeconds = medT,
            )

        return GuidedCalibrationResult(
            rotationAxis = axis.achse.toList(),
            gyroBias = restS.gyroBias.toList(),
            chosenSignal = cfg.signal,
            theta = theta,
            baseline = baseline,
            noiseFloor = noiseFloor,
            expectedProminence = expectedProminence,
            expectedDurationSamples = medT * sampleRateHz,
            expectedDurationMs = medT * 1_000.0,
            repTemplate = repTemplate,
            qualityScore = quality,
            accelThreshold = accelThreshold,
            review = review,
        )
    }

    /**
     * P2-Fix #22: leitet die Accel-Schwelle aus dem KNOWN_SET ab, statt sie
     * zu raten.
     *
     * Vorher stand im Code eine feste 0.1625 — ein aus der Gyro-Schwelle
     * (32.5 deg/s) durch Division mit 200 entstandener Wert ohne
     * physikalischen Bezug. Genau deshalb blieb `accelEnabled` aus: mit einer
     * geratenen Schwelle haette das Voting entweder alles durchgelassen
     * (Schwelle zu tief) oder gute Wiederholungen verworfen (zu hoch).
     *
     * Vorgehen: an den bereits validierten Rep-Positionen des KNOWN_SET wird
     * die Spitze der Accel-Abweichung gemessen. Die Schwelle liegt bei
     * [ACCEL_PEAK_FRACTION] des Medians dieser Spitzen und muss zugleich
     * klar ueber dem Ruherauschen liegen. Findet sich keine belastbare
     * Trennung, gibt die Funktion 0.0 zurueck und das Voting bleibt aus —
     * lieber kein zweiter Kanal als ein falsch parametrisierter.
     */
    private fun calibrateAccelThreshold(marks: List<RepMark>): Double {
        if (marks.size < MIN_ACCEL_CALIBRATION_PEAKS) return 0.0
        val restS = rest ?: return 0.0
        if (bufB.isEmpty()) return 0.0

        val deviation = accelDeviation(bufB)
        val noiseSigma = restS.sigmaAccel
        val half = max(1, (sampleRateHz * ACCEL_PEAK_WINDOW_S).toInt())
        val peaks =
            marks.mapNotNull { mark ->
                val start = max(0, mark.sampleIndex - half)
                val end = min(deviation.size, mark.sampleIndex + half)
                if (end > start) deviation.copyOfRange(start, end).max() else null
            }
        if (peaks.size < MIN_ACCEL_CALIBRATION_PEAKS) return 0.0

        val candidate = ACCEL_PEAK_FRACTION * median(peaks)
        val noiseCeiling = ACCEL_NOISE_MARGIN * max(noiseSigma, MIN_ACCEL_NOISE_SIGMA)
        // Die Schwelle muss deutlich ueber dem Rauschen UND deutlich unter den
        // gemessenen Spitzen liegen; sonst ist der Kanal nicht trennscharf.
        if (candidate <= noiseCeiling) return 0.0
        return candidate
    }

    /**
     * Gefilterte Abweichung der Accel-Magnitude von 1 g — dieselbe Groesse,
     * die [com.dropsync.domain.sensor.SignalChain] live als `smoothedAccel`
     * berechnet. Die Kalibrierung MUSS auf demselben Signal messen, auf dem
     * die Live-Pipeline spaeter entscheidet.
     */
    private fun accelDeviation(buf: List<SensorSample>): DoubleArray {
        val raw = DoubleArray(buf.size) { abs(buf[it].accelMagnitude - 1.0) }
        return ema(raw, ACCEL_EMA_ALPHA)
    }

    // --- Stage evaluation -------------------------------------------------

    private fun finishRest(): CalibrationFailure? {
        val seconds = bufRest.size / sampleRateHz
        if (seconds < CalibrationThresholds.REST_MIN_SECONDS) {
            return CalibrationFailure.RestTooShort(
                measuredSeconds = seconds,
                requiredSeconds = CalibrationThresholds.REST_MIN_SECONDS,
            )
        }
        val stats = restStats(bufRest, sampleRateHz)
        if (!stats.gateOk) {
            val gyroFailed = stats.gyroMagMean >= CalibrationThresholds.REST_GYRO_MEAN_MAX_DEG_PER_SEC
            val accelFailed = stats.sigmaAccel >= CalibrationThresholds.REST_ACCEL_SIGMA_MAX_G
            bufRest.clear()
            return CalibrationFailure.RestGateFailed(
                gyroMagMean = stats.gyroMagMean.takeIf { gyroFailed },
                accelSigma = stats.sigmaAccel.takeIf { accelFailed },
            )
        }
        rest = stats
        stage = Stage.SINGLE_REP
        return null
    }

    private fun finishSingleRep(): CalibrationFailure? {
        val res = axisAnalysis(bufA, rest!!, sampleRateHz)
        if (res == null) {
            bufA.clear()
            return CalibrationFailure.NoMotionWindow
        }
        axisResult = res
        stage = Stage.KNOWN_SET
        return null
    }

    private fun runBSweep() {
        val axis = axisResult ?: return
        val restS = rest ?: return
        signalsB = candidateSignals(bufB, axis.achse, restS.gyroBias)
        metaB = signalMeta(signalsB!!, sampleRateHz)
        sweepCfg = knownCountSweep(signalsB!!, metaB!!, axis.t0, knownSetCount, sampleRateHz)
        val cfg = sweepCfg
        if (cfg != null) {
            val robustThreshold = medianMinusKMad(cfg.peakHoehen, cfg.theta)
            cfg.theta = robustThreshold.theta
            baselineChosen = metaB!![cfg.signal]!!.first
            thetaFinal = robustThreshold.theta
            quality = 1.0 - min(1.0, cfg.cv)
        } else {
            thetaFinal = null
            baselineChosen = null
            quality = 0.0
        }
    }

    private fun finishKnownSet(): CalibrationFailure? {
        runBSweep()
        // A failed sweep does NOT block: stage C still records, review corrects.
        stage = Stage.SLOW_SET
        return null
    }

    private fun runC() {
        val cfg = sweepCfg ?: return
        val sigB = signalsB ?: return
        val axis = axisResult ?: return
        val restS = rest ?: return
        signalsC = candidateSignals(bufC, axis.achse, restS.gyroBias)
        val res =
            stufeC(
                sigB[cfg.signal]!!,
                signalsC!![cfg.signal]!!,
                baselineChosen!!,
                cfg,
                knownSetCount,
                slowSetCount,
                sampleRateHz,
            )
        if (res.ok) thetaFinal = res.theta
    }

    private fun finishSlowSet(): CalibrationFailure? {
        runC()
        stage = Stage.REVIEW
        return null
    }

    // --- Stage 0: rest analysis (Referenz stufe0_ruheanalyse) --------------

    companion object {
        /** P2-Fix #22: so viele validierte Peaks braucht die Accel-Kalibrierung. */
        const val MIN_ACCEL_CALIBRATION_PEAKS = 3

        /** Halbes Suchfenster um einen Rep-Peak (s). */
        const val ACCEL_PEAK_WINDOW_S = 0.4

        /** Anteil der gemessenen Accel-Spitze, der als Schwelle dient. */
        const val ACCEL_PEAK_FRACTION = 0.35

        /** Faktor, um den die Schwelle ueber dem Ruherauschen liegen muss. */
        const val ACCEL_NOISE_MARGIN = 4.0

        /** Untergrenze fuer sigma, damit ein "zu ruhiges" Rest-Fenster nicht 0 liefert. */
        const val MIN_ACCEL_NOISE_SIGMA = 0.005

        /** EMA-Alpha der Accel-Glaettung (grob wie One-Euro bei 2 Hz/50 Hz). */
        const val ACCEL_EMA_ALPHA = 0.2

        /**
         * RC-8: Untergrenze fuer das Ruherauschen im Review-Verhaeltnis
         * theta/Rauschen. Ein perfekt stilles synthetisches Rest-Fenster
         * haette σ = 0; ohne Untergrenze waere das Verhaeltnis unendlich.
         */
        const val REVIEW_NOISE_FLOOR_EPS = 1e-3
    }
}

// --- Statistics helpers (Referenz: numpy-Entsprechungen) -------------------

internal fun mean(xs: List<Double>): Double = if (xs.isEmpty()) 0.0 else xs.sum() / xs.size

/** Population standard deviation (ddof=0), like numpy.std. */
internal fun std(xs: List<Double>): Double {
    if (xs.isEmpty()) return 0.0
    val m = mean(xs)
    return sqrt(mean(xs.map { (it - m) * (it - m) }))
}

internal fun median(xs: List<Double>): Double {
    if (xs.isEmpty()) return 0.0
    val s = xs.sorted()
    val n = s.size
    return if (n % 2 == 1) s[n / 2] else (s[n / 2 - 1] + s[n / 2]) / 2
}

/** numpy.percentile with linear interpolation (default method). */
internal fun percentile(
    xs: List<Double>,
    p: Double,
): Double {
    if (xs.isEmpty()) return 0.0
    val s = xs.sorted()
    val rank = (s.size - 1) * p / 100.0
    val i = rank.toInt()
    val f = rank - i
    if (i + 1 >= s.size) return s.last()
    return s[i] * (1 - f) + s[i + 1] * f
}

internal fun linspace(
    a: Double,
    b: Double,
    n: Int,
): List<Double> {
    if (n <= 1) return listOf(a)
    val step = (b - a) / (n - 1)
    return (0 until n).map { a + it * step }
}

/** Causal EMA low-pass like SignalProcessor (Referenz ema_glaettung). */
internal fun ema(
    xs: DoubleArray,
    alpha: Double,
): DoubleArray {
    if (xs.isEmpty()) return xs
    val out = DoubleArray(xs.size)
    out[0] = xs[0]
    for (i in 1 until xs.size) {
        out[i] = out[i - 1] * (1 - alpha) + xs[i] * alpha
    }
    return out
}

/** Tempo probe: signal linearly stretched by factor 3 (Referenz np.interp). */
internal fun stretch3(sig: DoubleArray): DoubleArray {
    val n = sig.size
    if (n < 2) return sig.copyOf()
    val m = 3 * (n - 1)
    val out = DoubleArray(m)
    for (k in 0 until m) {
        val x = k / 3.0
        val i = x.toInt()
        val f = x - i
        out[k] = sig[i] * (1 - f) + sig[i + 1] * f
    }
    return out
}

/** Median template across rep windows (Referenz TemplateExtractor.extract). */
internal fun extractMedianTemplate(
    windows: List<List<Double>>,
    templateLength: Int,
): List<Double> {
    if (windows.isEmpty()) return emptyList()
    // Pad/truncate each window to templateLength, then take the median per index.
    val normalized =
        windows.map { w ->
            if (w.size >= templateLength) {
                w.take(templateLength)
            } else {
                w + List(templateLength - w.size) { w.last() }
            }
        }
    return (0 until templateLength).map { i -> median(normalized.map { it[i] }) }
}
