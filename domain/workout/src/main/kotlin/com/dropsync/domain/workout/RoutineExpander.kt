package com.dropsync.domain.workout

/**
 * Superset-/Triset-Regeln und Routinen-Expansion (Bauplan 9.6/9.7).
 * Gruppenzugehoerigkeit steht an SessionExercise/RoutineExercise, nie an
 * einzelnen Saetzen; eine Gruppe hat exakt 2 oder 3 UNTERSCHIEDLICHE
 * Uebungen.
 */
object SupersetRules {
    /**
     * Prueft alle Gruppen einer Liste (routine- oder sessionweit).
     * [entries] ist Paar aus exerciseId und optionaler Gruppen-ID.
     */
    fun validateGroups(entries: List<Pair<Long, Long?>>): Boolean =
        entries
            .filter { it.second != null }
            .groupBy { it.second }
            .values
            .all { group ->
                val distinctExercises = group.map { it.first }.toSet()
                group.size in 2..3 && distinctExercises.size == group.size
            }
}

/** Eintrag einer Routine fuer die Expansion in eine Session (9.7). */
data class RoutineEntry(
    val exerciseId: Long,
    val orderIndex: Int,
    val supersetGroupId: Long?,
    val targetSets: Int?,
    val targetRepsMin: Int?,
    val targetRepsMax: Int?,
    val restSeconds: Int?,
)

/** Geplante Sessionuebung nach der Expansion. */
data class PlannedSessionExercise(
    val exerciseId: Long,
    val orderIndex: Int,
    val supersetGroupId: Long?,
    val plannedSets: Int,
)

/**
 * Expandiert eine Routine deterministisch in Sessionuebungen:
 * stabile Reihenfolge nach orderIndex, Supersetgruppen bleiben erhalten,
 * keine historischen Gewichte oder Session-IDs (9.7).
 */
object RoutineExpander {
    const val DEFAULT_SETS = 3

    fun expand(entries: List<RoutineEntry>): List<PlannedSessionExercise> {
        require(
            SupersetRules.validateGroups(entries.map { it.exerciseId to it.supersetGroupId }),
        ) { "Ungueltige Supersetgruppe: exakt 2-3 unterschiedliche Uebungen noetig" }
        return entries
            .sortedBy { it.orderIndex }
            .mapIndexed { index, entry ->
                PlannedSessionExercise(
                    exerciseId = entry.exerciseId,
                    orderIndex = index,
                    supersetGroupId = entry.supersetGroupId,
                    plannedSets = entry.targetSets ?: DEFAULT_SETS,
                )
            }
    }
}
