package com.dropsync.domain.sensor

/** A confirmed peak in the smoothed g_p signal (port of peak_event.dart). */
data class PeakEvent(
    val sampleIndex: Int,
    val timestampMs: Long,
    val peakValue: Double,
    val precedingValley: Double,
    val prominence: Double,
    /** Diagnostic: excursion length in samples (time decisions use ms). */
    val durationSamples: Int,
    /** Excursion length in milliseconds (time basis: timestamps). */
    val durationMs: Long,
    /** Raw excursion window, consumed by TemplateMatcher. */
    val window: List<Double>,
)

private enum class DetectorState { IDLE, RISING, FALLING }

/**
 * Adaptive peak detector after Pan-Tompkins (port of peak_detector.dart):
 * theta = calibrated detection threshold, idle -> rising -> falling state
 * machine, refractory time against double counting, prominence filter.
 *
 * Umbauplan Phase 1.4/4: the threshold [currentThreshold] is the calibrated
 * theta used DIRECTLY (no SPK/NPK reconstruction). SPK/NPK only adapt
 * internally via EMA after confirmed/rejected peaks.
 *
 * Umbauplan Phase 2.5: refractory and duration use TIMESTAMPS, not the local
 * sample index, so BLE packet loss cannot compress physical time.
 */
class PeakDetector(
    val sampleRateHz: Double = 50.0,
    // Legacy default: theta = NPK + 0.25 * (SPK - NPK) with SPK=100, NPK=10.
    private var threshold: Double = 32.5,
    private val fallingRatio: Double = 0.5,
    private val fallingDebounce: Int = 4,
    refractoryMs: Long = 500,
    expectedDurationMs: Double = refractoryMs.toDouble(),
    private val prominenceRatio: Double = 0.2,
    private val refractoryDurationRatio: Double = 0.3,
    private val signal: (ProcessedFrame) -> Double = { it.smoothedGp },
) {
    private var spk: Double = threshold
    private var npk: Double = threshold * 0.5

    private var state = DetectorState.IDLE
    private var currentMax = 0.0
    private var currentMin = Double.MAX_VALUE
    private var fallingCount = 0
    private val window = mutableListOf<Double>()
    private var sampleIndex = 0
    private var lastPeakTimestampMs: Long? = null
    private var excursionStartMs: Long = 0L
    private var refractoryMillis = refractoryMs

    /** Erwartete Rep-Dauer in ms (Basis fuer die adaptive Refraktaerzeit). */
    var expectedDurationMs: Double = expectedDurationMs
        private set

    /** Current adaptive detection threshold theta (calibrated start value). */
    var currentThreshold: Double = threshold
        private set

    var lastPeakDurationSamples: Int = 0
        private set
    var lastPeakProminence: Double = 0.0
        private set

    /**
     * Umbauplan Punkt 6: setzt die Refraktaerzeit auf 30% der erwarteten
     * Rep-Dauer in ms, begrenzt auf 100 ms bis 2 s.
     */
    fun updateExpectedDurationMs(durationMs: Double) {
        expectedDurationMs = durationMs
        refractoryMillis =
            (durationMs * refractoryDurationRatio)
                .toLong()
                .coerceAtLeast(100)
                .coerceAtMost(2_000)
    }

    /** Umbauplan Phase 1.4: sets the calibrated theta directly. */
    fun updateThreshold(
        theta: Double,
        expectedDurationMs: Double? = null,
    ) {
        require(theta.isFinite() && theta >= 0.0) { "theta must be finite and >= 0" }
        this.threshold = theta
        currentThreshold = theta
        spk = theta
        npk = theta * 0.5
        expectedDurationMs?.let { updateExpectedDurationMs(it) }
    }

    /** Processes ONE frame; returns a [PeakEvent] when a peak is confirmed. */
    fun process(frame: ProcessedFrame): PeakEvent? {
        sampleIndex++
        val value = signal(frame)
        if (value.isNaN()) return null

        when (state) {
            DetectorState.IDLE -> {
                if (value > currentThreshold && !inRefractory(frame.timestampMs)) {
                    state = DetectorState.RISING
                    currentMax = value
                    excursionStartMs = frame.timestampMs
                    window.clear()
                    window.add(value)
                } else if (value < currentMin) {
                    currentMin = value
                }
            }

            DetectorState.RISING -> {
                window.add(value)
                if (value > currentMax) {
                    currentMax = value
                    fallingCount = 0
                }
                if (value < currentThreshold * fallingRatio) {
                    state = DetectorState.FALLING
                    fallingCount = 1
                }
            }

            DetectorState.FALLING -> {
                window.add(value)
                if (value > currentMax) {
                    state = DetectorState.RISING
                    currentMax = value
                    fallingCount = 0
                } else {
                    fallingCount++
                    if (fallingCount >= fallingDebounce) {
                        val result = evaluatePeak(frame.timestampMs)
                        state = DetectorState.IDLE
                        currentMin = value
                        return result
                    }
                }
            }
        }
        return null
    }

    private fun evaluatePeak(timestampMs: Long): PeakEvent? {
        val prominence = currentMax - currentMin
        val minProminence = spk * prominenceRatio
        return if (prominence >= minProminence) {
            // Confirmed: update SPK (EMA alpha=0.125).
            spk = 0.125 * currentMax + 0.875 * spk
            lastPeakTimestampMs = timestampMs
            lastPeakDurationSamples = window.size
            lastPeakProminence = prominence
            PeakEvent(
                sampleIndex = sampleIndex,
                timestampMs = timestampMs,
                peakValue = currentMax,
                precedingValley = currentMin,
                prominence = prominence,
                durationSamples = window.size,
                durationMs = (timestampMs - excursionStartMs).coerceAtLeast(0),
                window = window.toList(),
            )
        } else {
            // Rejected: update NPK (EMA alpha=0.125).
            npk = 0.125 * currentMax + 0.875 * npk
            null
        }
    }

    private fun inRefractory(timestampMs: Long): Boolean {
        val last = lastPeakTimestampMs ?: return false
        return (timestampMs - last) < refractoryMillis
    }

    /** Resets detector state; threshold/levels survive (loaded from the profile). */
    fun reset() {
        state = DetectorState.IDLE
        currentMax = 0.0
        currentMin = Double.MAX_VALUE
        fallingCount = 0
        window.clear()
        sampleIndex = 0
        lastPeakTimestampMs = null
    }
}
