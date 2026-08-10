package com.dropsync.domain.sensor

/** Result of phase validation (port of phase_validator.dart PhaseResult). */
data class PhaseResult(
    val valid: Boolean,
    val positiveDuration: Int,
    val negativeDuration: Int,
    /** positive / (positive + negative); ideal ~0.5. */
    val durationRatio: Double,
    val rejectionReason: String? = null,
)

/**
 * Validates the phase structure of a rep peak: a valid rep has a
 * concentric (g_p > 0) and an eccentric (g_p < 0) phase with a sane
 * duration ratio. Pure-envelope windows pass the simplified check.
 */
class PhaseValidator(
    private val minDurationRatio: Double = 0.15,
    private val maxDurationRatio: Double = 0.85,
    private val minPhaseSamples: Int = 2,
) {
    fun validate(window: List<Double>): PhaseResult {
        if (window.size < 4) {
            return PhaseResult(
                valid = false,
                positiveDuration = 0,
                negativeDuration = 0,
                durationRatio = 0.0,
                rejectionReason = "Window zu kurz (< 4 Samples)",
            )
        }

        var positiveCount = 0
        var negativeCount = 0
        for (v in window) {
            if (v > 0) {
                positiveCount++
            } else if (v < 0) {
                negativeCount++
            }
        }
        val total = positiveCount + negativeCount

        // Single phase only (e.g. pure envelope): accept, phase info comes
        // from the sign of the smoothed g_p elsewhere.
        if (negativeCount == 0 || positiveCount == 0) {
            return PhaseResult(
                valid = true,
                positiveDuration = positiveCount,
                negativeDuration = negativeCount,
                durationRatio = if (total > 0) positiveCount.toDouble() / total else 0.5,
            )
        }

        val ratio = positiveCount.toDouble() / total
        if (positiveCount < minPhaseSamples) {
            return PhaseResult(false, positiveCount, negativeCount, ratio, "Positive Phase zu kurz")
        }
        if (negativeCount < minPhaseSamples) {
            return PhaseResult(false, positiveCount, negativeCount, ratio, "Negative Phase zu kurz")
        }
        if (ratio < minDurationRatio || ratio > maxDurationRatio) {
            return PhaseResult(false, positiveCount, negativeCount, ratio, "Phasen-Verhaeltnis zu asymmetrisch")
        }
        return PhaseResult(true, positiveCount, negativeCount, ratio)
    }
}
