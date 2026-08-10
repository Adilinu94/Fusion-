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

/** Result of a completed guided calibration (persisted per exercise+device). */
data class GuidedCalibrationResult(
    val rotationAxis: List<Double>,
    val gyroBias: List<Double>,
    val chosenSignal: ChosenSignal,
    val theta: Double,
    val baseline: Double,
    val expectedProminence: Double,
    val expectedDurationSamples: Double,
    val repTemplate: List<Double>,
    val qualityScore: Double,
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
            val stats = restStats(bufRest)
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
     * Ends the current stage and evaluates its buffer. Returns a German
     * failure message when a quality gate failed (stage is repeated), null on
     * success.
     */
    fun finishStage(): String? {
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

        return GuidedCalibrationResult(
            rotationAxis = axis.achse.toList(),
            gyroBias = restS.gyroBias.toList(),
            chosenSignal = cfg.signal,
            theta = theta,
            baseline = baseline,
            expectedProminence = expectedProminence,
            expectedDurationSamples = medT * sampleRateHz,
            repTemplate = repTemplate,
            qualityScore = quality,
        )
    }

    // --- Stage evaluation -------------------------------------------------

    private fun finishRest(): String? {
        val seconds = bufRest.size / sampleRateHz
        if (seconds < CalibrationThresholds.REST_MIN_SECONDS) {
            return "Zu kurz: noch ${"%.1f".format(seconds)} s von " +
                "${CalibrationThresholds.REST_MIN_SECONDS.toInt()} s Mindest-Ruhe. " +
                "Arm still halten, dann erneut Weiter."
        }
        val stats = restStats(bufRest)
        if (!stats.gateOk) {
            val reasons = mutableListOf<String>()
            if (stats.gyroMagMean >= CalibrationThresholds.REST_GYRO_MEAN_MAX_DEG_PER_SEC) {
                reasons.add("|gyro| ${"%.1f".format(stats.gyroMagMean)} deg/s")
            }
            if (stats.sigmaAccel >= CalibrationThresholds.REST_ACCEL_SIGMA_MAX_G) {
                reasons.add("Accel-Rauschen ${"%.3f".format(stats.sigmaAccel)} g")
            }
            bufRest.clear()
            return "Ruhe-Gate nicht bestanden: ${reasons.joinToString("; ")}. " +
                "Arm in Startposition still halten, 2-3 s warten, dann Weiter."
        }
        rest = stats
        stage = Stage.SINGLE_REP
        return null
    }

    private fun finishSingleRep(): String? {
        val res = axisAnalysis(bufA, rest!!)
        if (res == null) {
            bufA.clear()
            return "Kein Bewegungsfenster gefunden. Bitte genau 1 deutliche Wiederholung ausfuehren."
        }
        axisResult = res
        stage = Stage.KNOWN_SET
        return null
    }

    private fun runBSweep() {
        val axis = axisResult ?: return
        val restS = rest ?: return
        signalsB = candidateSignals(bufB, axis.achse, restS.gyroBias)
        metaB = signalMeta(signalsB!!)
        sweepCfg = knownCountSweep(signalsB!!, metaB!!, axis.t0, knownSetCount)
        val cfg = sweepCfg
        if (cfg != null) {
            val (theta, _, _, _) = medianMinusKMad(cfg.peakHoehen, cfg.theta)
            cfg.theta = theta
            baselineChosen = metaB!![cfg.signal]!!.first
            thetaFinal = theta
            quality = 1.0 - min(1.0, cfg.cv)
        } else {
            thetaFinal = null
            baselineChosen = null
            quality = 0.0
        }
    }

    private fun finishKnownSet(): String? {
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
            )
        if (res.ok) thetaFinal = res.theta
    }

    private fun finishSlowSet(): String? {
        runC()
        stage = Stage.REVIEW
        return null
    }

    // --- Stage 0: rest analysis (Referenz stufe0_ruheanalyse) --------------

    internal data class RestStats(
        val n: Int,
        val baseline: Double,
        val sigmaAccel: Double,
        val gyroBias: DoubleArray,
        val sigmaGyro: Double,
        val gyroMagMean: Double,
        val gateOk: Boolean,
    )

    internal fun restStats(buf: List<SensorSample>): RestStats {
        val n = buf.size
        if (n == 0) {
            return RestStats(
                0,
                0.0,
                Double.POSITIVE_INFINITY,
                DoubleArray(3),
                Double.POSITIVE_INFINITY,
                Double.POSITIVE_INFINITY,
                false,
            )
        }
        // Trim start settle + end tap motion: first ~15% and last ~0.4 s.
        val dropEnd = min(n / 4, max(1, (0.4 * sampleRateHz).toInt()))
        val dropStart = min(n / 6, max(0, n - dropEnd - 10))
        val end = max(dropStart + 1, n - dropEnd)
        val window = buf.subList(dropStart, end)

        val accelMag = window.map { it.accelMagnitude }
        val bias =
            doubleArrayOf(
                mean(window.map { it.gx }),
                mean(window.map { it.gy }),
                mean(window.map { it.gz }),
            )
        val gyroMag =
            window.map { s ->
                val dx = s.gx - bias[0]
                val dy = s.gy - bias[1]
                val dz = s.gz - bias[2]
                sqrt(dx * dx + dy * dy + dz * dz)
            }
        val sigmaAccel = std(accelMag)
        val gyroMagMean = mean(gyroMag)
        val gateOk =
            gyroMagMean < CalibrationThresholds.REST_GYRO_MEAN_MAX_DEG_PER_SEC &&
                sigmaAccel < CalibrationThresholds.REST_ACCEL_SIGMA_MAX_G
        return RestStats(
            n = window.size,
            baseline = mean(accelMag),
            sigmaAccel = sigmaAccel,
            gyroBias = bias,
            sigmaGyro = std(gyroMag),
            gyroMagMean = gyroMagMean,
            gateOk = gateOk,
        )
    }

    // --- Stage A: axis analysis via 3x3 PCA (Referenz stufeA) --------------

    internal data class AxisResult(
        val achse: DoubleArray,
        val t0: Double,
        val gyroPeak: Double,
        val varianceShare: Double,
    )

    internal fun axisAnalysis(
        buf: List<SensorSample>,
        rest: RestStats,
    ): AxisResult? {
        val bias = rest.gyroBias
        val gyroZ = buf.map { s -> doubleArrayOf(s.gx - bias[0], s.gy - bias[1], s.gz - bias[2]) }
        val gyroMag = gyroZ.map { r -> sqrt(r[0] * r[0] + r[1] * r[1] + r[2] * r[2]) }
        val schwelle = max(15.0, 4.0 * rest.sigmaGyro)
        val aktiv = gyroMag.indices.filter { gyroMag[it] > schwelle }
        if (aktiv.size < CalibrationThresholds.MIN_ACTIVITY_SAMPLES) return null
        val i0 = aktiv.first()
        val i1 = aktiv.last()
        val fenster = gyroZ.subList(i0, i1 + 1)

        val (axis, varianceShare) = principalAxis(fenster) ?: return null
        val projected = fenster.map { r -> r[0] * axis[0] + r[1] * axis[1] + r[2] * axis[2] }
        return AxisResult(
            achse = axis,
            t0 = (i1 - i0) / sampleRateHz,
            gyroPeak = projected.max(),
            varianceShare = varianceShare,
        )
    }

    // --- Stage B: candidate signals + known-count sweep --------------------

    internal fun candidateSignals(
        buf: List<SensorSample>,
        achse: DoubleArray,
        bias: DoubleArray,
    ): Map<ChosenSignal, DoubleArray> {
        val gyroWeight = 0.05 // same formula as SignalProcessor
        val n = buf.size
        val gP = DoubleArray(n)
        val gyroMag = DoubleArray(n)
        val combinedRaw = DoubleArray(n)
        for (i in 0 until n) {
            val s = buf[i]
            val dx = s.gx - bias[0]
            val dy = s.gy - bias[1]
            val dz = s.gz - bias[2]
            gP[i] = dx * achse[0] + dy * achse[1] + dz * achse[2]
            gyroMag[i] = sqrt(dx * dx + dy * dy + dz * dz)
            combinedRaw[i] = s.accelMagnitude + gyroWeight * gyroMag[i]
        }
        return mapOf(
            ChosenSignal.GP to gP,
            ChosenSignal.COMBINED to ema(combinedRaw, 0.6),
            ChosenSignal.GYRO_MAG to gyroMag,
        )
    }

    /** Baseline/sigma per candidate signal from the rest edges (1 s each side). */
    internal fun signalMeta(signals: Map<ChosenSignal, DoubleArray>): Map<ChosenSignal, Pair<Double, Double>> {
        val nRest = sampleRateHz.toInt()
        return signals.mapValues { (_, sig) ->
            val restSamples =
                (sig.take(min(nRest, sig.size)) + sig.takeLast(min(nRest, sig.size)))
            median(restSamples) to std(restSamples)
        }
    }

    internal data class SweepCfg(
        val signal: ChosenSignal,
        var theta: Double,
        val refractoryS: Double,
        val prominenz: Double,
        val cv: Double,
        val margin: Double,
        val peakHoehen: List<Double>,
    )

    internal fun knownCountSweep(
        signals: Map<ChosenSignal, DoubleArray>,
        meta: Map<ChosenSignal, Pair<Double, Double>>,
        t0: Double,
        nSoll: Int,
    ): SweepCfg? {
        var beste: SweepCfg? = null
        for ((name, sig) in signals) {
            val (baseline, sigma) = meta[name]!!
            val span = percentile(sig.toList(), 99.0) - baseline
            if (span <= 0) continue
            val vorl = zaehleEdge(sig, sampleRateHz, baseline + 3 * sigma, 0.35 * t0, baseline)
            val prom = if (vorl.size >= 3) 0.2 * median(vorl.map { it.height }) else 0.0
            // Tempo probe: 3x stretched signal must still count nSoll.
            val sigLangsam = stretch3(sig)
            for (frac in linspace(0.10, 1.00, 20)) {
                val theta = baseline + frac * span
                for (prominenz in listOf(0.0, prom)) {
                    // Stability probe: shortest refractory must still count nSoll.
                    if (zaehleEdge(sig, sampleRateHz, theta, 0.35 * t0, baseline, prominenz = prominenz).size !=
                        nSoll
                    ) {
                        continue
                    }
                    if (zaehleEdge(sigLangsam, sampleRateHz, theta, 0.35 * t0, baseline, prominenz = prominenz).size !=
                        nSoll
                    ) {
                        continue
                    }
                    for (refrFaktor in linspace(0.35, 0.75, 5)) {
                        val refr = refrFaktor * t0
                        val reps = zaehleEdge(sig, sampleRateHz, theta, refr, baseline, prominenz = prominenz)
                        if (reps.size != nSoll) continue
                        val hoehen = reps.map { it.height }
                        if (median(hoehen) < baseline + 0.5 * span) continue
                        val intervalle =
                            (1 until reps.size).map { i ->
                                (reps[i].sampleIndex - reps[i - 1].sampleIndex) / sampleRateHz
                            }
                        val meanI = mean(intervalle)
                        val cv =
                            if (intervalle.size > 1 &&
                                meanI > 0
                            ) {
                                std(intervalle) / meanI
                            } else {
                                Double.POSITIVE_INFINITY
                            }
                        val margin = theta - (baseline + 3 * sigma)
                        val cfg = SweepCfg(name, theta, refr, prominenz, cv, margin, hoehen)
                        val current = beste
                        if (current == null || isBetter(cfg, current)) beste = cfg
                    }
                }
            }
        }
        return beste
    }

    /** Tie-break: minimal interval CV, then maximal margin above noise floor. */
    private fun isBetter(
        a: SweepCfg,
        b: SweepCfg,
    ): Boolean = if (a.cv != b.cv) a.cv < b.cv else a.margin > b.margin

    /** Final threshold = median - k*MAD of validated peak heights. */
    internal fun medianMinusKMad(
        peakHoehen: List<Double>,
        thetaSweep: Double,
    ): Quad {
        val med = median(peakHoehen)
        val mad = median(peakHoehen.map { abs(it - med) })
        if (mad < 1e-9) return Quad(thetaSweep, 0.0, med, mad)
        val k = max(0.0, (med - thetaSweep) / mad)
        return Quad(med - k * mad, k, med, mad)
    }

    internal data class Quad(
        val theta: Double,
        val k: Double,
        val med: Double,
        val mad: Double,
    )

    // --- Stage C: tempo robustness (Referenz stufeC) -----------------------

    internal data class StufeCResult(
        val theta: Double,
        val ok: Boolean,
        val angepasst: Boolean,
    )

    internal fun stufeC(
        sigB: DoubleArray,
        sigC: DoubleArray,
        baseline: Double,
        cfg: SweepCfg,
        nB: Int,
        nC: Int,
    ): StufeCResult {
        fun zaehl(
            sig: DoubleArray,
            theta: Double,
        ) = zaehleEdge(sig, sampleRateHz, theta, cfg.refractoryS, baseline, prominenz = cfg.prominenz).size

        fun hoehen(
            sig: DoubleArray,
            theta: Double,
        ) = zaehleEdge(sig, sampleRateHz, theta, cfg.refractoryS, baseline, prominenz = cfg.prominenz).map { it.height }

        val theta0 = cfg.theta
        var thetaArbeit: Double? = null
        if (zaehl(sigC, theta0) == nC && zaehl(sigB, theta0) == nB) {
            thetaArbeit = theta0
        } else {
            for (f in linspace(0.98, 0.05, 60)) {
                val thetaT = baseline + (theta0 - baseline) * f
                if (zaehl(sigB, thetaT) == nB && zaehl(sigC, thetaT) == nC) {
                    thetaArbeit = thetaT
                    break
                }
            }
            if (thetaArbeit == null) return StufeCResult(theta0, ok = false, angepasst = false)
        }
        // Conservative cap: lower distribution edge of the slow peaks.
        val langsamHoehen = hoehen(sigC, thetaArbeit)
        if (langsamHoehen.isNotEmpty() && cfg.peakHoehen.isNotEmpty()) {
            val medB = median(cfg.peakHoehen)
            val madB = median(cfg.peakHoehen.map { abs(it - medB) })
            val sigmaRel = max(1.4826 * madB / max(medB, 1e-9), 0.10)
            val medC = median(langsamHoehen)
            val deckel = medC - 2.5 * sigmaRel * medC
            val thetaDeckel = min(thetaArbeit, deckel)
            if (thetaDeckel < thetaArbeit - 1e-9 && zaehl(sigB, thetaDeckel) == nB && zaehl(sigC, thetaDeckel) == nC) {
                return StufeCResult(thetaDeckel, ok = true, angepasst = true)
            }
        }
        return StufeCResult(thetaArbeit, ok = true, angepasst = abs(thetaArbeit - theta0) > 1e-9)
    }

    // --- Counting path (Referenz zaehle_edge) -------------------------------

    internal data class RepMark(
        val sampleIndex: Int,
        val height: Double,
    )

    internal fun zaehleEdge(
        signal: DoubleArray,
        hz: Double,
        theta: Double,
        refractoryS: Double,
        baseline: Double,
        fallingRatio: Double = 0.5,
        prominenz: Double = 0.0,
        fallingDebounce: Int = 4,
    ): List<RepMark> {
        val reps = mutableListOf<RepMark>()
        var above = false
        var excPeak = Double.NEGATIVE_INFINITY
        var excIdx = -1
        var preMin = Double.POSITIVE_INFINITY
        var lastEnd = Int.MIN_VALUE / 2
        var unterFalling = 0
        val refrSamples = (refractoryS * hz).toInt()
        val falling = baseline + (theta - baseline) * fallingRatio
        for (i in signal.indices) {
            val v = signal[i]
            if (!above) {
                if (v < preMin) preMin = v
                if (v > theta) {
                    if (i - lastEnd < refrSamples) continue
                    above = true
                    excPeak = v
                    excIdx = i
                    unterFalling = 0
                }
            } else {
                if (v >= excPeak) {
                    excPeak = v
                    excIdx = i
                }
                if (v < falling) {
                    unterFalling++
                } else {
                    unterFalling = 0
                }
                if (unterFalling >= fallingDebounce) {
                    above = false
                    unterFalling = 0
                    if (prominenz > 0.0 && (excPeak - preMin) < prominenz) {
                        preMin = v
                        continue
                    }
                    reps.add(RepMark(excIdx, excPeak))
                    lastEnd = i
                    preMin = v
                }
            }
        }
        return reps
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
