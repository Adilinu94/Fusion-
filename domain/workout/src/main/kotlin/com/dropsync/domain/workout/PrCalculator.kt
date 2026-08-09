package com.dropsync.domain.workout

import com.dropsync.core.model.PrType
import com.dropsync.core.model.PrValueUnit

/**
 * Vollstaendige PR-Neuberechnung aus der qualifizierten Historie einer
 * Uebung (Bauplan 5.4, Schritt 10.3/10.4).
 *
 * Eine inkrementelle Ruecknahme wird bewusst nicht angenommen: Sowohl
 * der Satzabschluss als auch jede Korrektur berechnen die Uebung
 * komplett neu; damit verschwinden falsche alte PRs automatisch.
 *
 * Gleichstand erzeugt nie eine neue PR: Bei mehreren Segmenten mit
 * demselben Maximalwert haelt das FRUEHESTE den Rekord.
 */
object PrCalculator {
    fun computeAll(segments: List<QualifiedSegment>): List<PrRecord> {
        if (segments.isEmpty()) return emptyList()
        val records = mutableListOf<PrRecord>()

        // 1. Hoechste Last: effektive Last strikt groesser als alles davor.
        val byLoad =
            segments.maxByOrNullStable(
                value = { WorkoutMath.effectiveLoadMilliKg(it.loadMilliKg, it.loadMultiplier) },
            )
        if (byLoad != null) {
            records +=
                PrRecord(
                    type = PrType.HIGHEST_LOAD,
                    achievedSessionId = byLoad.sessionId,
                    achievedClusterId = byLoad.clusterId,
                    valueLong = WorkoutMath.effectiveLoadMilliKg(byLoad.loadMilliKg, byLoad.loadMultiplier),
                    valueUnit = PrValueUnit.MILLI_KG,
                    comparableLoadMilliKg = null,
                    achievedAtEpochMs = byLoad.completedAtEpochMs,
                )
        }

        // 2. Hoechstes Session-Volumen: Summe je Session; bei Gleichstand
        //    haelt die aeltere Session den Rekord.
        val volumeBySession =
            segments
                .groupBy { it.sessionId }
                .mapValues { (_, group) ->
                    group.sumOf {
                        WorkoutMath.segmentVolumeMilliKg(it.loadMilliKg, it.loadMultiplier, it.reps)
                    }
                }
        val bestSession =
            volumeBySession.entries
                .sortedWith(
                    compareByDescending<Map.Entry<Long, Long>> { it.value }
                        .thenBy { sessionStart(segments, it.key) },
                ).first()
        records +=
            PrRecord(
                type = PrType.HIGHEST_SESSION_VOLUME,
                achievedSessionId = bestSession.key,
                achievedClusterId = null,
                valueLong = bestSession.value,
                valueUnit = PrValueUnit.MILLI_KG,
                comparableLoadMilliKg = null,
                achievedAtEpochMs =
                    segments
                        .filter { it.sessionId == bestSession.key }
                        .maxOf { it.completedAtEpochMs },
            )

        // 3. Meiste Wiederholungen bei identischer effektiver Last:
        //    ein Rekord je Lastwert.
        segments
            .groupBy { WorkoutMath.effectiveLoadMilliKg(it.loadMilliKg, it.loadMultiplier) }
            .forEach { (load, group) ->
                val best = group.maxByOrNullStable(value = { it.reps.toLong() }) ?: return@forEach
                records +=
                    PrRecord(
                        type = PrType.MOST_REPS_AT_LOAD,
                        achievedSessionId = best.sessionId,
                        achievedClusterId = best.clusterId,
                        valueLong = best.reps.toLong(),
                        valueUnit = PrValueUnit.REPS,
                        comparableLoadMilliKg = load,
                        achievedAtEpochMs = best.completedAtEpochMs,
                    )
            }

        return records
    }

    private fun sessionStart(
        segments: List<QualifiedSegment>,
        sessionId: Long,
    ): Long = segments.first { it.sessionId == sessionId }.sessionStartedAtEpochMs

    /**
     * Maximum mit stabiler Gleichstandsregel: bei gleichem Wert gewinnt
     * das frueheste Segment (Abschlusszeit, dann Cluster-ID).
     */
    private fun List<QualifiedSegment>.maxByOrNullStable(value: (QualifiedSegment) -> Long): QualifiedSegment? =
        sortedWith(
            compareByDescending(value)
                .thenBy { it.completedAtEpochMs }
                .thenBy { it.clusterId },
        ).firstOrNull()
}
