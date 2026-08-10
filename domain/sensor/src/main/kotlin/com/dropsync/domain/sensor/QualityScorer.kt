package com.dropsync.domain.sensor

/** Result of the rep quality score (port of quality_scorer.dart). */
data class QualityResult(
    val score: Double,
    val accepted: Boolean,
    val correlationScore: Double,
    val romScore: Double,
    val tempoScore: Double,
    val symmetryScore: Double,
)

/**
 * Weighted rep quality: correlation 40 %, ROM/prominence 25 %, tempo 20 %,
 * symmetry 15 %. A rep counts when score >= minScore. Shadow-pipeline
 * only — no live counting risk (design doc Phase 4 step 6).
 */
class QualityScorer(
    expectedProminence: Double = 50.0,
    expectedDurationSamples: Double = 50.0,
    private val weightCorrelation: Double = 0.40,
    private val weightRom: Double = 0.25,
    private val weightTempo: Double = 0.20,
    private val weightSymmetry: Double = 0.15,
    private val minScore: Double = 0.55,
) {
    var expectedProminence: Double = expectedProminence
        private set
    var expectedDurationSamples: Double = expectedDurationSamples
        private set

    fun score(
        correlation: Double,
        prominence: Double,
        durationSamples: Int,
        durationRatio: Double,
    ): QualityResult {
        val corrScore = ((correlation + 1.0) / 2.0).coerceIn(0.0, 1.0)

        val romRatio = if (expectedProminence > 0) prominence / expectedProminence else 1.0
        val romScore = (1.0 - kotlin.math.abs(romRatio - 1.0)).coerceIn(0.0, 1.0)

        val tempoRatio =
            if (expectedDurationSamples > 0) durationSamples / expectedDurationSamples else 1.0
        val tempoScore = (1.0 - kotlin.math.abs(tempoRatio - 1.0)).coerceIn(0.0, 1.0)

        val symmetryScore = (1.0 - kotlin.math.abs(durationRatio - 0.5) * 2.0).coerceIn(0.0, 1.0)

        val total =
            weightCorrelation * corrScore +
                weightRom * romScore +
                weightTempo * tempoScore +
                weightSymmetry * symmetryScore
        return QualityResult(
            score = total,
            accepted = total >= minScore,
            correlationScore = corrScore,
            romScore = romScore,
            tempoScore = tempoScore,
            symmetryScore = symmetryScore,
        )
    }

    fun updateExpectations(
        expectedProminence: Double? = null,
        expectedDurationSamples: Double? = null,
    ) {
        expectedProminence?.let { this.expectedProminence = it }
        expectedDurationSamples?.let { this.expectedDurationSamples = it }
    }
}
