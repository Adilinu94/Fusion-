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
    val expectedProminence: Double = 50.0,
    /** Umbauplan Phase 1.4: calibrated detection threshold (deg/s). */
    val detectionThreshold: Double = 32.5,
    /** Umbauplan Phase 4: expected rep duration in milliseconds. */
    val expectedDurationMs: Double = 1_000.0,
    val hasValidCalibration: Boolean = false,
    /** Punkt 4: Accel-Kanal + Voting aktiv (Feature-Flag fuer den Rollout). */
    val accelEnabled: Boolean = false,
    /** Punkt 5: Groesse des Template-Pools (Formdrift). */
    val templatePoolSize: Int = 5,
    /** Punkt 8: Madgwick-Orientierungs-Tracking der kalibrierten Achse. */
    val orientationTrackingEnabled: Boolean = false,
) {
    init {
        require(rotationAxis.size == 3) { "rotationAxis must have 3 components" }
        require(gyroBias.size == 3) { "gyroBias must have 3 components" }
        require(templatePoolSize >= 1) { "templatePoolSize must be >= 1" }
        require(detectionThreshold.isFinite() && detectionThreshold >= 0.0) {
            "detectionThreshold must be finite and >= 0"
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
            expectedProminence = config.expectedProminence,
            expectedDurationMs = config.expectedDurationMs,
            minScore = config.minQualityScore,
        )

    private val templateMatcher =
        TemplateMatcher(
            threshold = config.templateThreshold,
            poolSize = config.templatePoolSize,
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
                    // Punkt 4: Accel-Signale sind eine Magnituden-Abweichung
                    // (~0 im Ruhezustand), also deutlich kleiner als Gyro.
                    // Konservative Levels: theta niedrig, Prominenz niedrig.
                    PeakDetector(
                        sampleRateHz = config.sampleRateHz,
                        threshold = 0.1625,
                        prominenceRatio = 0.2,
                        signal = { it.smoothedAccel },
                    )
                } else {
                    null
                },
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
        val frame = signalChain.process(timestampMs, gx, gy, gz, ax, ay, az)
        if (!frame.isSettled) {
            framesRejected++
            return EngineFrameResult(frame = frame, repResult = RepResult.NONE)
        }
        framesProcessed++
        val repResult = repCounter.process(frame)
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
        if (!frame.isSettled) {
            framesRejected++
            return
        }
        framesProcessed++
        val repResult = repCounter.process(frame)
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
    }

    companion object {
        /** Umbauplan Phase 2.6: Luecke, ab der Zustand verworfen wird. */
        const val LARGE_GAP_MS = 250L
    }
}
