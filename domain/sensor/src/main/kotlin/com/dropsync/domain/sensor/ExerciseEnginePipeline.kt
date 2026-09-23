package com.dropsync.domain.sensor

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/** Configuration for the [ExerciseEnginePipeline] (port of ExerciseEngineConfig). */
data class ExerciseEngineConfig(
    val sampleRateHz: Double = 50.0,
    val rotationAxis: List<Double>,
    val gyroBias: List<Double>,
    val oneEuroMinCutoff: Double = 1.0,
    val oneEuroBeta: Double = 0.007,
    val envelopeCutoffHz: Double = 3.0,
    val templateThreshold: Double = 0.7,
    val minQualityScore: Double = 0.55,
    /**
     * B1 (RC-18, Stufe 1): einseitige Qualitaetstoleranzen des
     * [QualityScorer]. In Ermuedungsrichtung (Prominenz faellt, Dauer steigt)
     * gilt [fatigueTolerance] (+0.45 gegenueber der alten 1.0), gegen
     * Schwung/Fremdbewegung [suspiciousTolerance] (-0.20). Plan-Namen:
     * toleranceBelow/toleranceAbove.
     */
    val fatigueTolerance: Double = 1.45,
    val suspiciousTolerance: Double = 0.80,
    /**
     * B6 (RC-21): Mindestabstand ueber [minQualityScore], den eine Rep
     * erreichen muss, um in den Template-Pool aufgenommen zu werden
     * (`admissionMinScore = minQualityScore + templateAdmissionMargin`).
     * 0.0 = Altverhalten (jede akzeptierte Rep kommt in den Pool).
     */
    val templateAdmissionMargin: Double = 0.05,
    val expectedProminence: Double = 50.0,
    /** Umbauplan Phase 1.4: calibrated detection threshold (deg/s). */
    val detectionThreshold: Double = 32.5,
    /** Umbauplan Phase 4: expected rep duration in milliseconds. */
    val expectedDurationMs: Double = 1_000.0,
    /**
     * B2 (RC-19, Stufe 1): Startwert NUR fuer die Qualitaets-Erwartung
     * ([QualityScorer.expectedDurationMs]) — z. B. die am Set-Ende gemessene
     * Rep-Periode des Vorgaenger-Satzes. Refraktaerzeit und Pending-Deckel
     * bleiben bewusst am gleitenden Mittelwert ([expectedDurationMs]).
     * null = Profilwert verwenden.
     */
    val qualityDurationMs: Double? = null,
    val hasValidCalibration: Boolean = false,
    /** Punkt 4: Accel-Kanal + Voting aktiv (Feature-Flag fuer den Rollout). */
    val accelEnabled: Boolean = false,
    /**
     * P2-Fix #22: kalibrierte Schwelle des Accel-Kanals (Abweichung der
     * Magnitude von 1 g). Vorher stand hier eine geratene Konstante
     * (0.1625 = 32.5/200) ohne physikalischen Bezug — genau der Grund, warum
     * [accelEnabled] dauerhaft aus blieb. Wird der Kanal aktiviert, MUSS ein
     * kalibrierter Wert > 0 vorliegen.
     */
    val accelThreshold: Double = 0.0,
    /** Punkt 5: Groesse des Template-Pools (Formdrift). */
    val templatePoolSize: Int = 5,
    /** Punkt 8: Madgwick-Orientierungs-Tracking der kalibrierten Achse. */
    val orientationTrackingEnabled: Boolean = false,
    /**
     * P2-Fix #24: ZUPT-Segmentierung. In den Ruhefenstern zwischen den
     * Wiederholungen wird der Gyro-Bias nachgemessen (Temperaturdrift des
     * MPU6886) und ein Pending-Rep, der in echte Ruhe hineinragt, verworfen.
     */
    val zuptEnabled: Boolean = true,
    /**
     * Umbauplan 2026-09-04 Phase 6.2: Vote-Fenster des Accel-Kanals in ms.
     * War bisher nur als RepCounter-Default (800) existent und damit fuer
     * den Offline-Sweep nicht erreichbar. Default unveraendert.
     */
    val accelVoteWindowMs: Long = 800L,
    /**
     * Umbauplan 2026-09-04 Phase 6.2: Breite des Sakoe-Chiba-Bands des
     * TemplateMatchers. Default unveraendert (TemplateMatcher.DTW_BAND);
     * der Sweep variiert ihn gegen die ~12 %-Verzerrungsannahme.
     */
    val dtwBand: Int = TemplateMatcher.DTW_BAND,
) {
    init {
        require(rotationAxis.size == 3) { "rotationAxis must have 3 components" }
        require(gyroBias.size == 3) { "gyroBias must have 3 components" }
        require(templatePoolSize >= 1) { "templatePoolSize must be >= 1" }
        require(detectionThreshold.isFinite() && detectionThreshold >= 0.0) {
            "detectionThreshold must be finite and >= 0"
        }
        // B1 (RC-18): Toleranzen muessen positiv sein, sonst waere jeder
        // Score 0 (Division durch 0 bzw. negative Toleranz).
        require(fatigueTolerance.isFinite() && fatigueTolerance > 0.0) {
            "fatigueTolerance must be finite and > 0"
        }
        require(suspiciousTolerance.isFinite() && suspiciousTolerance > 0.0) {
            "suspiciousTolerance must be finite and > 0"
        }
        require(qualityDurationMs == null || (qualityDurationMs.isFinite() && qualityDurationMs > 0.0)) {
            "qualityDurationMs must be null or finite and > 0"
        }
        require(templateAdmissionMargin.isFinite() && templateAdmissionMargin >= 0.0) {
            "templateAdmissionMargin must be finite and >= 0"
        }
        // P2-Fix #22: der Accel-Kanal darf nur mit KALIBRIERTER Schwelle
        // laufen. Ein geratener Wert wuerde entweder alles durchlassen oder
        // gute Wiederholungen verwerfen - beides schlimmer als kein Voting.
        require(!accelEnabled || (accelThreshold.isFinite() && accelThreshold > 0.0)) {
            "accelEnabled requires a calibrated accelThreshold > 0"
        }
    }
}

/** A confirmed (or rejected) rep event emitted by the pipeline. */
data class RepEvent(
    val repNumber: Int,
    val qualityScore: Double,
    val correlation: Double?,
    val prominence: Double,
    val durationSamples: Int,
    val durationMs: Long,
    val timestampMs: Long,
)

/** Result of processing one raw sample through the pipeline. */
data class EngineFrameResult(
    val frame: ProcessedFrame?,
    val repResult: RepResult,
)

/**
 * Orchestrator of the full rep-detection pipeline (port of
 * exercise_engine.dart): SignalChain -> RepCounter, plus state and event
 * emission. Stateful - call [reset] on session change.
 *
 * Umbauplan Phase 1: the pipeline consumes the calibrated threshold
 * directly ([ExerciseEngineConfig.detectionThreshold]) so calibration and
 * live detection share the exact same parameter.
 */
class ExerciseEnginePipeline(
    val config: ExerciseEngineConfig,
) : ExerciseEngine {
    private val signalChain =
        SignalChain(
            rotationAxis = config.rotationAxis.toDoubleArray(),
            gyroBias = config.gyroBias.toDoubleArray(),
            sampleRateHz = config.sampleRateHz,
            oneEuroMinCutoff = config.oneEuroMinCutoff,
            oneEuroBeta = config.oneEuroBeta,
            envelopeCutoffHz = config.envelopeCutoffHz,
            accelEnabled = config.accelEnabled,
            orientationTracker = if (config.orientationTrackingEnabled) OrientationTracker() else null,
        )

    private val qualityScorer =
        QualityScorer(
            // B2 (RC-19): die gemessene Periode des Vorgaenger-Satzes seedet
            // NUR die Qualitaets-Erwartung; die Refraktaerzeit des
            // PeakDetectors bleibt am Profil-/Mittelwert.
            expectedDurationMs = config.qualityDurationMs ?: config.expectedDurationMs,
            expectedProminence = config.expectedProminence,
            minScore = config.minQualityScore,
            // B1 (RC-18): einseitige Toleranzen aus der Config.
            fatigueTolerance = config.fatigueTolerance,
            suspiciousTolerance = config.suspiciousTolerance,
        )

    /**
     * B2 (RC-19): aktuelle Qualitaets-Erwartung des Motors (Diagnose/Tests).
     * Sie folgt ab der dritten Rep dem gleitenden Mittelwert; der Startwert
     * kann aus [ExerciseEngineConfig.qualityDurationMs] stammen.
     */
    internal val qualityExpectedDurationMs: Double
        get() = qualityScorer.expectedDurationMs

    private val templateMatcher =
        TemplateMatcher(
            threshold = config.templateThreshold,
            poolSize = config.templatePoolSize,
            dtwBand = config.dtwBand,
            // B6 (RC-21): grenzwertige Reps nicht in den Pool lassen.
            admissionMinScore = config.minQualityScore + config.templateAdmissionMargin,
        )

    private val repCounter =
        RepCounter(
            peakDetector =
                PeakDetector(
                    sampleRateHz = config.sampleRateHz,
                    threshold = config.detectionThreshold,
                    refractoryMs = (config.expectedDurationMs * 0.3).toLong().coerceIn(100, 2_000),
                    expectedDurationMs = config.expectedDurationMs,
                ),
            templateMatcher = templateMatcher,
            phaseValidator = PhaseValidator(),
            qualityScorer = qualityScorer,
            accelPeakDetector =
                if (config.accelEnabled) {
                    // Punkt 4 + P2-Fix #22: Accel-Signale sind eine
                    // Magnituden-Abweichung (~0 im Ruhezustand), also deutlich
                    // kleiner als Gyro. Die Schwelle kommt jetzt aus der
                    // Kalibrierung (gemessen am KNOWN_SET), nicht mehr aus
                    // einer geratenen Konstante.
                    PeakDetector(
                        sampleRateHz = config.sampleRateHz,
                        threshold = config.accelThreshold,
                        prominenceRatio = 0.2,
                        signal = { it.smoothedAccel },
                    )
                } else {
                    null
                },
            accelVoteWindowMs = config.accelVoteWindowMs,
        )

    private val _repCount = MutableStateFlow(0)
    override val repCount: StateFlow<Int> = _repCount.asStateFlow()

    private val _repEvents = MutableSharedFlow<RepEvent>(extraBufferCapacity = 16)
    val repEvents: SharedFlow<RepEvent> = _repEvents.asSharedFlow()

    var framesProcessed = 0
        private set
    var framesRejected = 0
        private set

    /** Umbauplan Phase 2.6: Anzahl grosser Zeitluecken im aktuellen Set. */
    var largeGapCount = 0
        private set

    /** Umbauplan Phase 2.6: letzter Sample-Timestamp (Gap-Erkennung). */
    private var lastSampleTimestampMs: Long? = null

    /**
     * P2-Fix #21: schaetzt die TATSAECHLICHE Abtastrate. Bisher war die
     * gesamte Kette auf 50 Hz verdrahtet; die reale Rate haengt aber an
     * Firmware-Takt, BLE-Uebertragung und Paketverlust.
     */
    private val sampleRateEstimator = SampleRateEstimator(nominalRateHz = config.sampleRateHz)

    /** Letzte an die Filter durchgereichte Rate (verhindert Mikro-Updates). */
    private var appliedSampleRateHz: Double = config.sampleRateHz

    /** Aktuelle Schaetzung der Abtastrate in Hz (Diagnose/Telemetrie). */
    val estimatedSampleRateHz: Double
        get() = sampleRateEstimator.estimatedRateHz

    /**
     * P2-Fix #19: Ringpuffer des projizierten, gefilterten Signals fuer die
     * Autokorrelations-Plausibilitaetspruefung am Set-Ende. Bewusst ein
     * Ringpuffer mit fester Groesse: ein Satz mit 30 Wiederholungen bei
     * 50 Hz belegt so konstant ~48 KB statt unbegrenzt zu wachsen.
     */
    private val signalRing = DoubleArray(SIGNAL_RING_SIZE)
    private var signalHead = 0
    private var signalFill = 0

    /**
     * P2-Fix #24: erkennt Ruhefenster fuer Bias-Nachfuehrung und
     * Satz-Segmentierung. null, wenn per Config abgeschaltet.
     */
    private val zupt = if (config.zuptEnabled) ZuptDetector() else null

    /** Anzahl der nachgefuehrten Bias-Korrekturen (Diagnose). */
    var zuptBiasUpdates = 0
        private set

    /** Anzahl der wegen Ruhe verworfenen Pending-Reps (Diagnose). */
    var zuptAbortedPending = 0
        private set

    /**
     * RC-17: Ablehnungen je Mechanismus im aktuellen Set. Gefuellt in
     * [processSample]/[processFrame] aus [RepResult.rejection]; geleert in
     * [reset]. Nur Mechanismen mit mindestens einem Treffer.
     */
    private val rejectionCounts = mutableMapOf<RepRejectionReason, Int>()

    /** Momentaufnahme der Ablehnungszaehler (Diagnose/Satz-Report). */
    val rejectionCountsSnapshot: Map<RepRejectionReason, Int>
        get() = rejectionCounts.toMap()

    /** P2-Fix #24: true, solange der Sensor als ruhend erkannt ist. */
    val isStationary: Boolean
        get() = zupt?.isStationary == true

    val isSettled: Boolean
        get() = signalChain.isSettled
    val hasTemplate: Boolean
        get() = repCounter.hasTemplate

    /** Processes one raw gyro sample through the whole pipeline. */
    fun processSample(
        timestampMs: Long,
        gx: Double,
        gy: Double,
        gz: Double,
        ax: Double = 0.0,
        ay: Double = 0.0,
        az: Double = 0.0,
    ): EngineFrameResult {
        val last = lastSampleTimestampMs
        if (last != null && timestampMs - last >= LARGE_GAP_MS) {
            onLargeGap()
        }
        lastSampleTimestampMs = timestampMs
        trackSampleRate(timestampMs)
        applyZupt(timestampMs, gx, gy, gz, ax, ay, az)
        val frame = signalChain.process(timestampMs, gx, gy, gz, ax, ay, az)
        if (!frame.isSettled) {
            framesRejected++
            return EngineFrameResult(frame = frame, repResult = RepResult.NONE)
        }
        framesProcessed++
        pushSignalSample(frame.smoothedGp)
        val repResult = repCounter.process(frame)
        trackRejection(repResult)
        if (repResult.repCounted) {
            _repCount.value = repCounter.repCount
            _repEvents.tryEmit(
                RepEvent(
                    repNumber = repResult.repNumber,
                    qualityScore = repResult.qualityScore ?: 0.0,
                    correlation = repResult.correlation,
                    prominence = repResult.prominence ?: 0.0,
                    durationSamples = repResult.durationSamples ?: 0,
                    durationMs = repResult.durationMs ?: 0,
                    timestampMs = frame.timestampMs,
                ),
            )
        }
        return EngineFrameResult(frame = frame, repResult = repResult)
    }

    /**
     * P2-Fix #21: nimmt den Timestamp in die Ratenschaetzung auf und reicht
     * eine belastbare neue Rate an Filter und Detektoren durch.
     *
     * Die Weitergabe passiert nur bei einer relevanten Abweichung
     * ([SAMPLE_RATE_UPDATE_TOLERANCE_HZ]). Sonst wuerde jeder einzelne
     * Median-Sprung von 49.9 auf 50.1 Hz alle Filterkonstanten neu berechnen,
     * ohne dass sich am Verhalten etwas aendert.
     */
    private fun trackSampleRate(timestampMs: Long) {
        sampleRateEstimator.onSample(timestampMs)
        if (!sampleRateEstimator.isConfident) return
        val estimated = sampleRateEstimator.estimatedRateHz
        if (kotlin.math.abs(estimated - appliedSampleRateHz) < SAMPLE_RATE_UPDATE_TOLERANCE_HZ) return
        appliedSampleRateHz = estimated
        signalChain.updateSampleRate(estimated)
        repCounter.updateSampleRate(estimated)
    }

    /** P2-Fix #19: schreibt einen Signalwert in den Ringpuffer. */
    private fun pushSignalSample(value: Double) {
        signalRing[signalHead] = value
        signalHead = (signalHead + 1) % SIGNAL_RING_SIZE
        if (signalFill < SIGNAL_RING_SIZE) signalFill++
    }

    /**
     * RC-17: zaehlt eine klassifizierte Ablehnung. Der RepCounter liefert je
     * finalisiertem Pending-Rep genau EIN [RepResult], deshalb ist hier keine
     * Deduplizierung noetig.
     */
    private fun trackRejection(repResult: RepResult) {
        val reason = repResult.rejection ?: return
        rejectionCounts[reason] = (rejectionCounts[reason] ?: 0) + 1
    }

    /**
     * P2-Fix #24: fuehrt die ZUPT-Segmentierung auf den ROHEN Werten aus.
     *
     * Zwei Wirkungen:
     * 1. Bestaetigt der Detektor ein Ruhefenster, wird der dort gemessene
     *    Gyro-Bias uebernommen. Das ist der einzige Zeitpunkt, an dem der Bias
     *    beobachtbar ist: wo keine Rotation stattfindet, IST die gemessene
     *    Drehrate der Bias. Ohne diese Nachfuehrung verschiebt die
     *    Temperaturdrift des MPU6886 die projizierte Spur gegen die
     *    kalibrierte Schwelle theta.
     * 2. Setzt echte Ruhe ein, waehrend noch ein Pending-Rep offen ist, war
     *    dieser ein Artefakt: eine Wiederholung, deren Rueckbewegung nie kam.
     *    Sie wird verworfen statt nach dem Zeitlimit doch bewertet zu werden.
     *
     * WICHTIG: der Detektor bekommt die unkorrigierten Gyro-Werte. Mit
     * bereits korrigierten Werten wuerde er immer denselben Bias bestaetigen,
     * den er schon anwendet — die Drift bliebe unsichtbar.
     */
    private fun applyZupt(
        timestampMs: Long,
        gx: Double,
        gy: Double,
        gz: Double,
        ax: Double,
        ay: Double,
        az: Double,
    ) {
        val detector = zupt ?: return
        val result = detector.onSample(timestampMs, gx, gy, gz, ax, ay, az)
        if (!result.zuptConfirmed) return

        // Ruhe bestaetigt: ein noch offener Pending-Rep hat keine
        // Rueckbewegung mehr zu erwarten. Gezaehlt wird nur der ECHTE
        // Verwerf-Fall (RC-16) — eine Ruhephase ohne offenen Pending ist
        // kein verlorener Rep.
        if (repCounter.abortPending()) {
            zuptAbortedPending++
        }

        detector.biasEstimate?.let { bias ->
            signalChain.updateGyroBias(bias)
            zuptBiasUpdates++
        }
    }

    /** Signalhistorie in chronologischer Reihenfolge (P2-Fix #19). */
    private fun signalHistory(): DoubleArray {
        val out = DoubleArray(signalFill)
        val start = if (signalFill < SIGNAL_RING_SIZE) 0 else signalHead
        for (i in 0 until signalFill) {
            out[i] = signalRing[(start + i) % SIGNAL_RING_SIZE]
        }
        return out
    }

    /**
     * P2-Fix #19: prueft den aktuellen Zaehlerstand per Autokorrelation
     * gegen die im Signal enthaltene Periodizitaet. Am Set-Ende aufzurufen.
     *
     * Die Pruefung KORRIGIERT nicht — sie liefert nur eine unabhaengige
     * Zweitmeinung, die geloggt und (spaeter) dem Nutzer angezeigt werden
     * kann. Der Grund: die Autokorrelation kann die Zahl nicht exakt
     * bestimmen (Randeffekte, Tempowechsel), sie erkennt aber sehr gut, ob
     * die kalibrierte Schwelle noch zum tatsaechlichen Signal passt.
     */
    fun checkPlausibility(): RepCountPlausibility.Result =
        RepCountPlausibility.check(
            signal = signalHistory(),
            sampleRateHz = appliedSampleRateHz,
            countedReps = repCounter.repCount,
            // B2 (RC-19): eine Luecke im Set macht die Zeitbasis im Ring
            // unbrauchbar (keine Timestamps) -> keine Aussage.
            hasLargeGap = largeGapCount > 0,
        )

    /**
     * Umbauplan Phase 2.6: bei einer grossen Zeitluecke (>= 150-250 ms)
     * werden laufender Peak, Pending-Rep und Filterzustand verworfen und
     * die Filter schwingen neu ein. Physische Zeit wird so nie komprimiert.
     */
    private fun onLargeGap() {
        largeGapCount++
        repCounter.abortPending()
        signalChain.reset()
    }

    /** ExerciseEngine contract: feed an already-processed frame. */
    override fun processFrame(frame: ProcessedFrame) {
        val last = lastSampleTimestampMs
        if (last != null && frame.timestampMs - last >= LARGE_GAP_MS) {
            onLargeGap()
        }
        lastSampleTimestampMs = frame.timestampMs
        trackSampleRate(frame.timestampMs)
        if (!frame.isSettled) {
            framesRejected++
            return
        }
        framesProcessed++
        pushSignalSample(frame.smoothedGp)
        val repResult = repCounter.process(frame)
        trackRejection(repResult)
        if (repResult.repCounted) {
            _repCount.value = repCounter.repCount
            _repEvents.tryEmit(
                RepEvent(
                    repNumber = repResult.repNumber,
                    qualityScore = repResult.qualityScore ?: 0.0,
                    correlation = repResult.correlation,
                    prominence = repResult.prominence ?: 0.0,
                    durationSamples = repResult.durationSamples ?: 0,
                    durationMs = repResult.durationMs ?: 0,
                    timestampMs = frame.timestampMs,
                ),
            )
        }
    }

    /** Sets the rep template (from the calibration profile). */
    fun setTemplate(template: List<Double>) = repCounter.setTemplate(template)

    /** Umbauplan Phase 1.4: feeds the calibrated threshold directly. */
    fun updateThreshold(
        theta: Double,
        expectedDurationMs: Double? = null,
    ) = repCounter.updateThreshold(theta, expectedDurationMs)

    /** Adopts a new calibration axis + bias without resetting counts. */
    fun updateCalibration(
        rotationAxis: List<Double>,
        gyroBias: List<Double>,
    ) = signalChain.updateCalibration(rotationAxis, gyroBias)

    /** Full reset: new session, exercise switch, or reconnect. */
    override fun reset() {
        signalChain.reset()
        repCounter.reset()
        _repCount.value = 0
        framesProcessed = 0
        framesRejected = 0
        largeGapCount = 0
        lastSampleTimestampMs = null
        sampleRateEstimator.reset()
        appliedSampleRateHz = config.sampleRateHz
        signalChain.updateSampleRate(config.sampleRateHz)
        repCounter.updateSampleRate(config.sampleRateHz)
        signalHead = 0
        signalFill = 0
        zupt?.reset()
        zuptBiasUpdates = 0
        zuptAbortedPending = 0
        rejectionCounts.clear()
    }

    companion object {
        /** Umbauplan Phase 2.6: Luecke, ab der Zustand verworfen wird. */
        const val LARGE_GAP_MS = 250L

        /**
         * P2-Fix #21: Mindestabweichung, ab der die gemessene Rate an die
         * Filter durchgereicht wird.
         */
        const val SAMPLE_RATE_UPDATE_TOLERANCE_HZ = 1.5

        /**
         * P2-Fix #19: Groesse des Signal-Ringpuffers. 3000 Samples sind bei
         * 50 Hz eine Minute - deutlich mehr als jeder normale Satz.
         */
        const val SIGNAL_RING_SIZE = 3_000
    }
}
