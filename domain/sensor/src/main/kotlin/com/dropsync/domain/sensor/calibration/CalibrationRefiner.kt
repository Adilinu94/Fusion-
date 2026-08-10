package com.dropsync.domain.sensor.calibration

import com.dropsync.domain.sensor.CalibrationProfile
import com.dropsync.domain.sensor.SensorSample
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Learn loop (Fusion Phase 4): when the user corrects the live-counted reps,
 * the buffered set is re-analysed so the pipeline parameters would reproduce
 * the TRUE count, and the stored profile is nudged towards the new evidence.
 *
 * Approach: project the set's gyro signal onto the stored rotation axis
 * (bias-corrected), detect [correctedReps] peaks, and derive the prominence
 * and rep-duration they imply. The template is re-extracted from the
 * corrected peaks. New values are smoothed into the profile (the calibration
 * still dominates, so one odd set never wrecks it).
 */
object CalibrationRefiner {
    /** Fraction of the new estimate blended into the stored profile. */
    private const val ADAPT_RATE = 0.3

    private const val SAMPLE_RATE_HZ = 50.0

    /**
     * Returns an improved [CalibrationProfile] for [correctedReps] performed
     * in [samples], or null when the set is too short to re-analyse.
     */
    fun refine(
        samples: List<SensorSample>,
        correctedReps: Int,
        profile: CalibrationProfile,
    ): CalibrationProfile? {
        if (correctedReps < 1 || samples.size < SAMPLE_RATE_HZ) return null
        val signal = project(samples, profile.rotationAxis, profile.gyroBias)
        val peaks = detectPeaks(signal, correctedReps)
        if (peaks.isEmpty()) return null

        // Rep duration from the spacing between consecutive corrected peaks.
        val intervals = (1 until peaks.size).map { peaks[it] - peaks[it - 1] }
        val newDuration = if (intervals.isNotEmpty()) median(intervals.map { it.toDouble() }) else null

        // Prominence implied by the corrected peaks.
        val newProminence = median(peaks.map { prominenceAt(signal, it) })

        // Template re-extracted around the corrected peaks.
        val newTemplate = extractTemplate(signal, peaks)

        return profile.copy(
            expectedProminence = blend(profile.expectedProminence, newProminence),
            expectedDurationSamples =
                if (newDuration != null) {
                    blend(profile.expectedDurationSamples, newDuration)
                } else {
                    profile.expectedDurationSamples
                },
            repTemplate = newTemplate ?: profile.repTemplate,
        )
    }

    // --- Signal projection (bias-corrected, onto the rotation axis) --------

    private fun project(
        samples: List<SensorSample>,
        axis: List<Double>,
        bias: List<Double>,
    ): DoubleArray {
        val out = DoubleArray(samples.size)
        for (i in samples.indices) {
            val s = samples[i]
            val dx = s.gx - bias[0]
            val dy = s.gy - bias[1]
            val dz = s.gz - bias[2]
            out[i] = dx * axis[0] + dy * axis[1] + dz * axis[2]
        }
        return out
    }

    // --- Peak detection: keep the `count` most prominent local maxima -------

    private fun detectPeaks(
        signal: DoubleArray,
        count: Int,
    ): List<Int> {
        if (signal.size < 3) return emptyList()
        val refractory = (SAMPLE_RATE_HZ * 0.3).toInt() // >= 0.3 s between reps
        val candidates = mutableListOf<Int>()
        for (i in 1 until signal.size - 1) {
            if (signal[i] >= signal[i - 1] && signal[i] >= signal[i + 1] && signal[i] > 0) {
                candidates.add(i)
            }
        }
        // Greedy: take the strongest peaks, respecting the refractory gap.
        val chosen = mutableListOf<Int>()
        for (idx in candidates.sortedByDescending { signal[it] }) {
            if (chosen.none { abs(it - idx) < refractory }) {
                chosen.add(idx)
                if (chosen.size == count) break
            }
        }
        return chosen.sorted()
    }

    /** Local prominence: peak height above the surrounding baseline. */
    private fun prominenceAt(
        signal: DoubleArray,
        index: Int,
    ): Double {
        val half = (SAMPLE_RATE_HZ * 0.5).toInt()
        val start = maxOf(0, index - half)
        val end = minOf(signal.size, index + half)
        var localMin = Double.MAX_VALUE
        for (i in start until end) if (signal[i] < localMin) localMin = signal[i]
        if (localMin == Double.MAX_VALUE) localMin = 0.0
        return abs(signal[index] - localMin)
    }

    // --- Template: median window across the corrected peaks -----------------

    private fun extractTemplate(
        signal: DoubleArray,
        peaks: List<Int>,
    ): List<Double>? {
        val templateLen = 64
        val half = templateLen / 2
        val windows =
            peaks.mapNotNull { p ->
                val start = maxOf(0, p - half)
                val end = minOf(signal.size, p + half)
                if (end - start >= templateLen / 2) signal.copyOfRange(start, end).toList() else null
            }
        if (windows.size < 2) return null
        val len = windows.minOf { it.size }
        return List(len) { i -> median(windows.map { it[i] }) }
    }

    // --- Small numeric helpers ----------------------------------------------

    private fun blend(
        old: Double,
        new: Double,
    ): Double = old * (1 - ADAPT_RATE) + new * ADAPT_RATE

    private fun median(values: List<Double>): Double {
        if (values.isEmpty()) return 0.0
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2.0
    }
}
