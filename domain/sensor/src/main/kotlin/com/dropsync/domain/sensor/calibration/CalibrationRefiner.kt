package com.dropsync.domain.sensor.calibration

import com.dropsync.domain.sensor.CalibrationProfile
import com.dropsync.domain.sensor.ExerciseEngineConfig
import com.dropsync.domain.sensor.ExerciseEnginePipeline
import com.dropsync.domain.sensor.ProfileStatus
import com.dropsync.domain.sensor.SensorSample
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Learn loop (Fusion Phase 4): when the user corrects the live-counted reps,
 * the buffered set is re-analysed so the pipeline parameters would reproduce
 * the TRUE count, and the stored profile is nudged towards the new evidence.
 *
 * Umbauplan Phase 7: der Refiner lernt NUR, wenn exakt [correctedReps]
 * plausible, zweiphasige Peaks gefunden wurden. Das angepasste Profil wird
 * anschliessend durch die echte Live-Pipeline geschickt und nur gespeichert,
 * wenn sie den bestaetigten Count reproduziert. Parameteraenderungen sind
 * pro Set begrenzt.
 */
object CalibrationRefiner {
    /** Fraction of the new estimate blended into the stored profile. */
    private const val ADAPT_RATE = 0.3

    /** Umbauplan Phase 7.3: maximale relative Parameteraenderung pro Set. */
    private const val MAX_RELATIVE_STEP = 0.25

    private const val SAMPLE_RATE_HZ = 50.0

    /**
     * Returns an improved [CalibrationProfile] for [correctedReps] performed
     * in [samples], or null when the set is too short, the peak structure is
     * implausible, or the revalidated pipeline does not reproduce the count.
     */
    fun refine(
        samples: List<SensorSample>,
        correctedReps: Int,
        profile: CalibrationProfile,
    ): CalibrationProfile? {
        if (correctedReps < 1 || samples.size < SAMPLE_RATE_HZ) return null
        val signal = project(samples, profile.rotationAxis, profile.gyroBias)

        // P0-Fix: es muessen GENAU correctedReps plausible Peaks gefunden
        // werden - eine Teil- oder Uebererkennung darf nichts lernen.
        val peaks = detectPeaks(signal, correctedReps + 1)
        if (peaks.size != correctedReps) return null

        // Jeder Kandidat muss zweiphasig sein (signiertes GP): um den Peak
        // herum muessen sowohl positive als auch negative Anteile liegen.
        val plausible = peaks.all { isTwoPhasePeak(signal, it) }
        if (!plausible) return null

        // Rep duration from the spacing between consecutive corrected peaks.
        val intervals = (1 until peaks.size).map { peaks[it] - peaks[it - 1] }
        val newDurationSamples = if (intervals.isNotEmpty()) median(intervals.map { it.toDouble() }) else null
        val newDurationMs = newDurationSamples?.let { it * (1_000.0 / SAMPLE_RATE_HZ) }

        // Prominence implied by the corrected peaks.
        val newProminence = median(peaks.map { prominenceAt(signal, it) })

        // Template re-extracted around the corrected peaks.
        val newTemplate = extractTemplate(signal, peaks)

        val candidate =
            profile.copy(
                expectedProminence = blend(profile.expectedProminence, newProminence),
                expectedDurationMs =
                    if (newDurationMs != null) {
                        blend(profile.expectedDurationMs, newDurationMs)
                    } else {
                        profile.expectedDurationMs
                    },
                repTemplate = newTemplate ?: profile.repTemplate,
                // Umbauplan Phase 7.4: der Refiner erzeugt nur noch Kandidaten.
                // Aktivierung erst nach genug validierten Sets (Promotion).
                revision = profile.revision + 1,
                parentRevision = profile.revision,
                status = ProfileStatus.CANDIDATE,
                validatedSetCount = 0,
            )

        // Umbauplan Phase 7.3: begrenzte Parameteraenderung pro Set.
        if (!withinStepLimits(profile, candidate)) return null

        // Umbauplan Phase 7.3: das angepasste Profil muss den bestaetigten
        // Count durch die ECHTE Live-Pipeline reproduzieren.
        if (!revalidates(samples, correctedReps, candidate)) return null

        return candidate
    }

    /**
     * Runs [samples] through the real [ExerciseEnginePipeline] configured
     * with [candidate]. Only returns true when it counts exactly
     * [correctedReps] reps.
     */
    private fun revalidates(
        samples: List<SensorSample>,
        correctedReps: Int,
        candidate: CalibrationProfile,
    ): Boolean {
        val pipeline =
            ExerciseEnginePipeline(
                ExerciseEngineConfig(
                    sampleRateHz = SAMPLE_RATE_HZ,
                    rotationAxis = candidate.rotationAxis,
                    gyroBias = candidate.gyroBias,
                    expectedProminence = candidate.expectedProminence,
                    expectedDurationMs = candidate.expectedDurationMs,
                    detectionThreshold = candidate.detectionThreshold,
                    hasValidCalibration = true,
                ),
            ).also { it.setTemplate(candidate.repTemplate) }
        for (s in samples) {
            pipeline.processSample(
                s.timestampMs,
                s.gx,
                s.gy,
                s.gz,
                s.ax,
                s.ay,
                s.az,
            )
        }
        return pipeline.repCount.value == correctedReps
    }

    /** Umbauplan Phase 7.3: begrenzte relative Parameteraenderung. */
    private fun withinStepLimits(
        old: CalibrationProfile,
        new: CalibrationProfile,
    ): Boolean {
        fun limited(
            oldValue: Double,
            newValue: Double,
        ): Boolean = abs(newValue - oldValue) <= max(oldValue, 1e-9) * MAX_RELATIVE_STEP
        return limited(old.expectedProminence, new.expectedProminence) &&
            limited(old.expectedDurationMs, new.expectedDurationMs)
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

    /** Umbauplan Phase 4/7: both half-waves around the peak are required. */
    private fun isTwoPhasePeak(
        signal: DoubleArray,
        index: Int,
    ): Boolean {
        val half = (SAMPLE_RATE_HZ * 0.6).toInt() // ~0.6 s window each side
        val start = max(0, index - half)
        val end = min(signal.size, index + half)
        var sawPositive = false
        var sawNegative = false
        for (i in start until end) {
            if (signal[i] > 0) sawPositive = true
            if (signal[i] < 0) sawNegative = true
        }
        return sawPositive && sawNegative
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
