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
 *
 * Umbauplan Phase 4: durations are milliseconds, not sample counts.
 *
 * B1 (RC-18, Stufe 1): ROM und Tempo werden EINSEITIG bewertet. Ermuedung
 * senkt die Prominenz und verlaengert die Dauer — solche Abweichungen sind
 * erwartbar und werden WEIT toleriert ([fatigueTolerance]); Abweichungen in
 * die verdaechtige Richtung (groesser/schneller als erwartet) bleiben eng
 * ([suspiciousTolerance]). Vorher galt beidseitig 100 % Toleranz, wodurch
 * 45 % Gewicht das Satzende systematisch bestrafte. Die Plan-Zahlen
 * 0.45/0.20 sind hier als DELTAS zur alten 1.0 gelesen (1.45 = +0.45 in
 * Ermuedungsrichtung, 0.80 = -0.20 gegen oben): die wörtliche Lesart waere
 * strenger als der Ist-Zustand gewesen und haette die Korpus-Gates
 * gebrochen (siehe BAUPLAN, B1, Abweichungsnotiz). Das Driftmodell
 * (Baustein B) folgt erst in Stufe 2; hier gibt es nur die Asymmetrie.
 */
class QualityScorer(
    expectedProminence: Double = 50.0,
    expectedDurationMs: Double = 1_000.0,
    private val weightCorrelation: Double = 0.40,
    private val weightRom: Double = 0.25,
    private val weightTempo: Double = 0.20,
    private val weightSymmetry: Double = 0.15,
    private val minScore: Double = 0.55,
    /**
     * B1 (RC-18): weite Toleranz in Ermuedungsrichtung (Prominenz faellt,
     * Dauer steigt): 40 % Velocity-Loss sind Normalfall (Pareja-Blanco
     * et al. 2017) und muessen den Score tragen koennen. Plan-Name:
     * `toleranceBelow` (die weite Seite liegt fuer die Prominenz unten,
     * fuer die Dauer oben).
     */
    private val fatigueTolerance: Double = 1.45,
    /** B1 (RC-18): enge Toleranz gegen Schwung/Fremdbewegung. */
    private val suspiciousTolerance: Double = 0.80,
) {
    init {
        require(fatigueTolerance > 0.0) { "fatigueTolerance must be > 0" }
        require(suspiciousTolerance > 0.0) { "suspiciousTolerance must be > 0" }
    }

    var expectedProminence: Double = expectedProminence
        private set
    var expectedDurationMs: Double = expectedDurationMs
        private set

    fun score(
        correlation: Double,
        prominence: Double,
        durationMs: Long,
        durationRatio: Double,
    ): QualityResult {
        val corrScore = ((correlation + 1.0) / 2.0).coerceIn(0.0, 1.0)

        val romRatio = if (expectedProminence > 0) prominence / expectedProminence else 1.0
        // B1: Prominenz in Ermuedungsrichtung = unter der Erwartung.
        val romScore = oneSidedScore(romRatio, fatigueIncreasesRatio = false)

        val tempoRatio =
            if (expectedDurationMs > 0) durationMs / expectedDurationMs else 1.0
        // B1: Dauer in Ermuedungsrichtung = ueber der Erwartung.
        val tempoScore = oneSidedScore(tempoRatio, fatigueIncreasesRatio = true)

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

    /**
     * B1 (RC-18): 1.0 bei exakter Erwartung, linear fallend bis 0 ausserhalb
     * der jeweiligen Toleranz. [fatigueIncreasesRatio] sagt, in welche
     * Richtung Ermuedung das Verhaeltnis verschiebt: Prominenz senkt es,
     * Dauer hebt es. Nur die Ermuedungsseite bekommt die weite Toleranz —
     * eine ploetzlich groessere/schnellere Rep bleibt verdaechtig.
     */
    private fun oneSidedScore(
        ratio: Double,
        fatigueIncreasesRatio: Boolean,
    ): Double {
        val deviation = ratio - 1.0
        val fatigueDeviation = if (fatigueIncreasesRatio) deviation else -deviation
        val tolerance = if (fatigueDeviation >= 0.0) fatigueTolerance else suspiciousTolerance
        return (1.0 - kotlin.math.abs(deviation) / tolerance).coerceIn(0.0, 1.0)
    }

    fun updateExpectations(
        expectedProminence: Double? = null,
        expectedDurationMs: Double? = null,
    ) {
        expectedProminence?.let { this.expectedProminence = it }
        expectedDurationMs?.let { this.expectedDurationMs = it }
    }
}
