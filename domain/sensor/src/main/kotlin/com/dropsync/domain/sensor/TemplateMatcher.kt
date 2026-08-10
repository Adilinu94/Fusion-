package com.dropsync.domain.sensor

import kotlin.math.min
import kotlin.math.sqrt

/** Result of a template match (port of template_matcher.dart MatchResult). */
data class MatchResult(
    /** NCC in [-1, 1]; 1 = perfect match. */
    val correlation: Double,
    val accepted: Boolean,
    val noTemplate: Boolean = false,
)

/**
 * Template matching via normalized cross-correlation (NCC).
 *
 * Resamples the window to [TEMPLATE_LENGTH], normalizes both signals
 * (mean 0, std 1) and computes NCC = sum(a[i]*b[i]) / N. O(N) per match,
 * fine for realtime at 50 Hz.
 */
class TemplateMatcher(
    private val threshold: Double = 0.7,
) {
    private var template: List<Double>? = null

    /** Sets the learned rep template (any length; resampled internally). */
    fun setTemplate(rawTemplate: List<Double>) {
        if (rawTemplate.size < 4) {
            template = null
            return
        }
        template = normalize(resample(rawTemplate, TEMPLATE_LENGTH))
    }

    /**
     * Matches a peak window against the template. Callers pass
     * `peak.window` directly — never an extended window (Befund-C fix,
     * PHASE_VALIDATOR_FIX_AUDIT_2026-08-05 section 7).
     */
    fun match(window: List<Double>): MatchResult {
        val tpl = template ?: return MatchResult(0.0, accepted = true, noTemplate = true)
        if (window.size < 4) return MatchResult(0.0, accepted = false)
        val normalized =
            normalize(resample(window, TEMPLATE_LENGTH))
                ?: return MatchResult(0.0, accepted = false)
        val ncc = crossCorrelate(tpl, normalized)
        return MatchResult(ncc, accepted = ncc >= threshold)
    }

    val hasTemplate: Boolean
        get() = template != null

    fun clearTemplate() {
        template = null
    }

    companion object {
        /** Fixed template length (64 samples = 1.28 s at 50 Hz). */
        const val TEMPLATE_LENGTH = 64

        internal fun resample(
            input: List<Double>,
            targetLength: Int,
        ): List<Double> {
            if (input.size == targetLength) return input.toList()
            val result = DoubleArray(targetLength)
            val ratio = (input.size - 1).toDouble() / (targetLength - 1)
            for (i in 0 until targetLength) {
                val srcPos = i * ratio
                val srcIdx = srcPos.toInt()
                val frac = srcPos - srcIdx
                result[i] =
                    if (srcIdx >= input.size - 1) {
                        input[input.size - 1]
                    } else {
                        input[srcIdx] * (1.0 - frac) + input[srcIdx + 1] * frac
                    }
            }
            return result.toList()
        }

        /** Mean 0, std 1; null for a (near-)constant signal. */
        internal fun normalize(input: List<Double>): List<Double>? {
            val n = input.size
            if (n == 0) return null
            val mean = input.sum() / n
            val variance = input.sumOf { (it - mean) * (it - mean) } / n
            val std = sqrt(variance)
            if (std < 1e-10) return null
            return input.map { (it - mean) / std }
        }

        internal fun crossCorrelate(
            a: List<Double>,
            b: List<Double>,
        ): Double {
            val n = min(a.size, b.size)
            if (n == 0) return 0.0
            var sum = 0.0
            for (i in 0 until n) sum += a[i] * b[i]
            return (sum / n).coerceIn(-1.0, 1.0)
        }
    }
}
