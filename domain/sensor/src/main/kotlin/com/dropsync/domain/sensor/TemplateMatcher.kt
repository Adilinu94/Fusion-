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
 *
 * Punkt 5 (Multi-Template): statt eines einzelnen Templates haelt der
 * Matcher einen Pool der letzten [poolSize] bestaetigten Rep-Windows. Der
 * beste NCC-Wert gegen alle Templates gewinnt; [addToPool] erweitert den
 * Pool FIFO. So faengt der Matcher Formdrift (Ermuedung) ab, die das
 * starre Einzel-Template unter die Schwelle druecken wuerde.
 */
class TemplateMatcher(
    private val threshold: Double = 0.7,
    private val poolSize: Int = 5,
) {
    private val templates: MutableList<List<Double>> = mutableListOf()

    /** Sets the learned rep template (any length; resampled internally). */
    fun setTemplate(rawTemplate: List<Double>) {
        if (rawTemplate.size < 4) {
            templates.clear()
            return
        }
        templates.clear()
        normalize(resample(rawTemplate, TEMPLATE_LENGTH))?.let { templates.add(it) }
    }

    /**
     * Punkt 5: nimmt ein bestaetigtes Rep-Window in den Pool auf
     * (normalisiert + resampled, FIFO mit [poolSize] Eintraegen).
     */
    fun addToPool(rawWindow: List<Double>) {
        if (rawWindow.size < 4) return
        val normalized = normalize(resample(rawWindow, TEMPLATE_LENGTH)) ?: return
        templates.add(normalized)
        if (templates.size > poolSize) {
            templates.removeAt(0)
        }
    }

    /**
     * Matches a peak window against the templates. Callers pass
     * `peak.window` directly — never an extended window (Befund-C fix,
     * PHASE_VALIDATOR_FIX_AUDIT_2026-08-05 section 7).
     */
    fun match(window: List<Double>): MatchResult {
        if (templates.isEmpty()) return MatchResult(0.0, accepted = true, noTemplate = true)
        if (window.size < 4) return MatchResult(0.0, accepted = false)
        val normalized =
            normalize(resample(window, TEMPLATE_LENGTH))
                ?: return MatchResult(0.0, accepted = false)
        val bestNcc = templates.maxOf { crossCorrelate(it, normalized) }
        return MatchResult(bestNcc, accepted = bestNcc >= threshold)
    }

    val hasTemplate: Boolean
        get() = templates.isNotEmpty()

    /** Anzahl der Templates im Pool (Punkt 5: fuer Tests). */
    val poolCount: Int
        get() = templates.size

    fun clearTemplate() {
        templates.clear()
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
