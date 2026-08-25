package com.dropsync.domain.workout

import com.dropsync.core.model.PrType
import com.dropsync.core.model.PrValueUnit
import com.training.core.TrainedSegment
import com.training.core.Units
import com.training.core.PrCalculator as CorePrCalculator
import com.training.core.PrType as CorePrType
import com.training.core.PrValueUnit as CorePrValueUnit

/**
 * Vollstaendige PR-Neuberechnung aus der qualifizierten Historie einer
 * Uebung (Bauplan 5.4, Schritt 10.3/10.4).
 *
 * Seit Schritt 5 der Flowtimer-Integration rechnet der gemeinsame Kern
 * ([CorePrCalculator], CONTEXT E6); hier bleibt nur die Uebersetzung
 * zwischen Fusions Millikilogramm-Modell und dem Kilogramm-Vertrag des
 * Kerns (E1). Verhalten unveraendert — insbesondere die Gleichstandsregel
 * (das FRUEHESTE Segment haelt den Rekord) und die drei PR-Arten.
 */
object PrCalculator {
    fun computeAll(segments: List<QualifiedSegment>): List<PrRecord> =
        CorePrCalculator
            .computeAll(segments.map { it.toCore() })
            .map { it.toPrRecord() }

    private fun QualifiedSegment.toCore(): TrainedSegment =
        TrainedSegment(
            loadKg = Units.milliToKg(loadMilliKg),
            reps = reps,
            completedAtEpochMs = completedAtEpochMs,
            loadMultiplier = loadMultiplier,
            sessionId = sessionId,
            sessionStartedAtEpochMs = sessionStartedAtEpochMs,
            clusterId = clusterId,
        )

    /**
     * Kern-Ergebnis -> Fusions [PrRecord]. Der Kern liefert kg als Double;
     * Reps stehen dort ebenfalls als Double, sind aber immer ganzzahlig.
     */
    private fun com.training.core.PrResult.toPrRecord(): PrRecord =
        PrRecord(
            type =
                when (type) {
                    CorePrType.HIGHEST_LOAD -> PrType.HIGHEST_LOAD
                    CorePrType.HIGHEST_SESSION_VOLUME -> PrType.HIGHEST_SESSION_VOLUME
                    CorePrType.MOST_REPS_AT_LOAD -> PrType.MOST_REPS_AT_LOAD
                },
            achievedSessionId = achievedSessionId,
            achievedClusterId = achievedClusterId,
            valueLong =
                when (valueUnit) {
                    CorePrValueUnit.KG -> Units.kgToMilli(value)
                    CorePrValueUnit.REPS -> value.toLong()
                },
            valueUnit =
                when (valueUnit) {
                    CorePrValueUnit.KG -> PrValueUnit.MILLI_KG
                    CorePrValueUnit.REPS -> PrValueUnit.REPS
                },
            comparableLoadMilliKg = comparableLoadKg?.let(Units::kgToMilli),
            achievedAtEpochMs = achievedAtEpochMs,
        )
}
