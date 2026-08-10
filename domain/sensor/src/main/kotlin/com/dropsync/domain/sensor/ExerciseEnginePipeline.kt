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
    val thresholdFactor: Double = 0.25,
    val templateThreshold: Double = 0.7,
    val minQualityScore: Double = 0.4,
    val expectedProminence: Double = 50.0,
    val expectedDurationSamples: Double = 50.0,
    val hasValidCalibration: Boolean = false,
) {
    init {
        require(rotationAxis.size == 3) { "rotationAxis must have 3 components" }
        require(gyroBias.size == 3) { "gyroBias must have 3 components" }
    }
}

/** A confirmed (or rejected) rep event emitted by the pipeline. */
data class RepEvent(
    val repNumber: Int,
    val qualityScore: Double,
    val correlation: Double?,
    val prominence: Double,
    val durationSamples: Int,
    val timestampMs: Long,
)

/** Result of processing one raw sample through the pipeline. */
data class EngineFrameResult(
    val frame: ProcessedFrame?,
    val repResult: RepResult,
)

/**
 * New orchestrator of the full rep-detection pipeline (port of
 * exercise_engine.dart): SignalChain -> RepCounter, plus state and event
 * emission. Stateful - call [reset] on session change.
 *
 * SHADOW ONLY (design doc Phase 4 step 6): runs alongside the legacy path
 * once a calibration profile exists; never counts live until its own
 * shadow DoD (section 11b) is met.
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
        )

    private val qualityScorer =
        QualityScorer(
            expectedProminence = config.expectedProminence,
            expectedDurationSamples = config.expectedDurationSamples,
            minScore = config.minQualityScore,
        )

    private val templateMatcher = TemplateMatcher(threshold = config.templateThreshold)

    private val repCounter =
        RepCounter(
            peakDetector =
                PeakDetector(
                    sampleRateHz = config.sampleRateHz,
                    thresholdFactor = config.thresholdFactor,
                ),
            templateMatcher = templateMatcher,
            phaseValidator = PhaseValidator(),
            qualityScorer = qualityScorer,
        )

    private val _repCount = MutableStateFlow(0)
    override val repCount: StateFlow<Int> = _repCount.asStateFlow()

    private val _repEvents = MutableSharedFlow<RepEvent>(extraBufferCapacity = 16)
    val repEvents: SharedFlow<RepEvent> = _repEvents.asSharedFlow()

    var framesProcessed = 0
        private set
    var framesRejected = 0
        private set

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
    ): EngineFrameResult {
        val frame = signalChain.process(timestampMs, gx, gy, gz)
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
                    timestampMs = frame.timestampMs,
                ),
            )
        }
        return EngineFrameResult(frame = frame, repResult = repResult)
    }

    /** ExerciseEngine contract: feed an already-processed frame. */
    override fun processFrame(frame: ProcessedFrame) {
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
                    timestampMs = frame.timestampMs,
                ),
            )
        }
    }

    /** Sets the rep template (from the calibration profile). */
    fun setTemplate(template: List<Double>) = repCounter.setTemplate(template)

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
    }
}
