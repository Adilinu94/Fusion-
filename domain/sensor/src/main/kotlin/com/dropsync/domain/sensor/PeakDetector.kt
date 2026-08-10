package com.dropsync.domain.sensor

/** A confirmed peak in the smoothed g_p signal (port of peak_event.dart). */
data class PeakEvent(
    val sampleIndex: Int,
    val timestampMs: Long,
    val peakValue: Double,
    val precedingValley: Double,
    val prominence: Double,
    val durationSamples: Int,
    /** Raw excursion window, consumed by TemplateMatcher. */
    val window: List<Double>,
)

private enum class DetectorState { IDLE, RISING, FALLING }

/**
 * Adaptive peak detector after Pan-Tompkins (port of peak_detector.dart):
 * theta = NPK + factor * (SPK - NPK), idle -> rising -> falling state
 * machine, refractory time against double counting, prominence filter.
 */
class PeakDetector(
    val sampleRateHz: Double = 50.0,
    initialSpk: Double = 100.0,
    initialNpk: Double = 10.0,
    private val thresholdFactor: Double = 0.25,
    private val fallingRatio: Double = 0.5,
    private val fallingDebounce: Int = 4,
    refractorySeconds: Double = 0.5,
    private val prominenceRatio: Double = 0.2,
) {
    private var spk: Double = initialSpk
    private var npk: Double = initialNpk

    private var state = DetectorState.IDLE
    private var currentMax = 0.0
    private var currentMin = Double.MAX_VALUE
    private var fallingCount = 0
    private val window = mutableListOf<Double>()
    private var sampleIndex = 0
    private var lastPeakSampleIndex: Int? = null
    private val refractorySamples = (refractorySeconds * sampleRateHz).toInt()

    /** Current adaptive threshold theta = NPK + factor * (SPK - NPK). */
    val currentThreshold: Double
        get() = npk + thresholdFactor * (spk - npk)

    var lastPeakDurationSamples: Int = 0
    var lastPeakProminence: Double = 0.0

    /** Processes ONE frame; returns a [PeakEvent] when a peak is confirmed. */
    fun process(frame: ProcessedFrame): PeakEvent? {
        sampleIndex++
        val value = frame.smoothedGp
        if (value.isNaN()) return null

        val theta = currentThreshold
        when (state) {
            DetectorState.IDLE -> {
                if (value > theta && !inRefractory()) {
                    state = DetectorState.RISING
                    currentMax = value
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
                if (value < theta * fallingRatio) {
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
            lastPeakSampleIndex = sampleIndex
            lastPeakDurationSamples = window.size
            lastPeakProminence = prominence
            PeakEvent(
                sampleIndex = sampleIndex,
                timestampMs = timestampMs,
                peakValue = currentMax,
                precedingValley = currentMin,
                prominence = prominence,
                durationSamples = window.size,
                window = window.toList(),
            )
        } else {
            // Rejected: update NPK (EMA alpha=0.125).
            npk = 0.125 * currentMax + 0.875 * npk
            null
        }
    }

    private fun inRefractory(): Boolean {
        val last = lastPeakSampleIndex ?: return false
        return (sampleIndex - last) < refractorySamples
    }

    /** Resets detector state; SPK/NPK survive (loaded from the profile). */
    fun reset() {
        state = DetectorState.IDLE
        currentMax = 0.0
        currentMin = Double.MAX_VALUE
        fallingCount = 0
        window.clear()
        sampleIndex = 0
        lastPeakSampleIndex = null
    }

    fun updateLevels(
        spk: Double? = null,
        npk: Double? = null,
    ) {
        spk?.let { this.spk = it }
        npk?.let { this.npk = it }
    }
}
