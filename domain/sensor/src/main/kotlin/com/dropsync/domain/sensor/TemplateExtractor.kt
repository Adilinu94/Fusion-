package com.dropsync.domain.sensor

/**
 * Extracts a rep template from calibration reps (port of
 * template_extractor.dart): resample each window to 64 samples, normalize,
 * take the per-index median (robust against outliers), normalize again.
 */
object TemplateExtractor {
    const val TEMPLATE_LENGTH = 64
    const val MIN_REPS = 2

    /** Returns a normalized 64-sample template or null if too few reps. */
    fun extract(windows: List<List<Double>>): List<Double>? {
        if (windows.size < MIN_REPS) return null
        val normalized =
            windows.map { TemplateExtractorMath.normalize(TemplateExtractorMath.resample(it, TEMPLATE_LENGTH)) }
        val template =
            List(TEMPLATE_LENGTH) { i ->
                median(normalized.map { it[i] }.sorted())
            }
        return TemplateExtractorMath.normalize(template)
    }
}

/** Shared resample/normalize helpers (non-null flavour for extraction). */
internal object TemplateExtractorMath {
    fun resample(
        input: List<Double>,
        targetLength: Int,
    ): List<Double> {
        if (input.size == targetLength) return input.toList()
        if (input.size < 2) {
            return List(targetLength) { input.firstOrNull() ?: 0.0 }
        }
        val result = DoubleArray(targetLength)
        val ratio = (input.size - 1).toDouble() / (targetLength - 1)
        for (i in 0 until targetLength) {
            val srcIndex = i * ratio
            val lower = srcIndex.toInt()
            val upper = kotlin.math.ceil(srcIndex).toInt()
            val frac = srcIndex - lower
            result[i] =
                if (lower == upper || upper >= input.size) {
                    input[lower.coerceIn(0, input.size - 1)]
                } else {
                    input[lower] * (1.0 - frac) + input[upper] * frac
                }
        }
        return result.toList()
    }

    /** (x - mean) / std; zero vector for a constant signal. */
    fun normalize(input: List<Double>): List<Double> {
        if (input.isEmpty()) return emptyList()
        val mean = input.sum() / input.size
        val variance = input.sumOf { (it - mean) * (it - mean) } / input.size
        val std = kotlin.math.sqrt(variance)
        if (std < 1e-10) return List(input.size) { 0.0 }
        return input.map { (it - mean) / std }
    }
}

private fun median(sorted: List<Double>): Double {
    if (sorted.isEmpty()) return 0.0
    val mid = sorted.size / 2
    return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2.0
}
