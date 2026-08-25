package com.dropsync.feature.progress

import com.dropsync.domain.workout.ExerciseTarget
import com.dropsync.domain.workout.FlatSet
import com.dropsync.domain.workout.TargetEvaluator
import java.util.Calendar

/**
 * Eine Zeile des Ziele-Tiles (UI-Vertrag R2/R5). Traegt die *Distanz*, nicht
 * den Stand: `10 kg fehlen` statt `90 kg (Ziel 100 kg)`.
 *
 * [missingWeightMilliKg] und [missingReps] sind 0, wenn die jeweilige
 * Bedingung erfuellt ist. Beide 0 heisst [reached] — die UI muss die
 * Fallunterscheidung nicht selbst rechnen.
 *
 * [filledDots] ist der Fortschritt als Zehnerteilung (R5): ein gefuellter
 * Punkt = 10 % geschlossene Restdistanz. Bewusst hier und nicht in der UI,
 * damit die Rundung getestet ist.
 */
data class ProgressGoalRow(
    val exerciseId: Long,
    val exerciseName: String,
    val reached: Boolean,
    val missingWeightMilliKg: Long,
    val missingReps: Int,
    val hasAnySet: Boolean,
    val staleWeeks: Boolean,
    val filledDots: Int,
    /** Sortierschluessel: groessere offene Restdistanz der beiden Dimensionen (R5). */
    val remainingFraction: Float,
    val lastSetEpochMs: Long?,
)

/**
 * Ziele-Tile des Dashboards (UI-Vertrag R5, Entscheidung 14).
 *
 * Zeigt Bewegung, nicht Bestand: Uebungen **ohne** Ziel erscheinen nicht,
 * sonst wird die Liste mit jeder neuen Uebung laenger und sagt weniger.
 * Reihenfolge: erst Uebungen mit Ziel nach Naehe zum Ziel (Goal-Gradient,
 * das Fast-Geschaffte zuerst), erreichte Ziele am Ende der Gruppe, danach
 * die laenger nicht trainierten.
 */
data class ProgressGoalsUiState(
    val rows: List<ProgressGoalRow>,
    val staleRows: List<ProgressGoalRow>,
    val reachedCount: Int,
    val totalCount: Int,
) {
    /** Tile-Sichtbarkeit: ohne Ziel nur die Hinweiszeile (UI-Vertrag Ausblenden). */
    val hasAnyGoal: Boolean
        get() = totalCount > 0

    companion object {
        private const val DOTS = 10
        private const val STALE_WEEKS = 8

        val Empty = ProgressGoalsUiState(emptyList(), emptyList(), 0, 0)

        fun from(
            targets: List<ExerciseTarget>,
            sets: List<FlatSet>,
            exerciseNames: Map<Long, String>,
            now: Calendar,
            fallbackExerciseName: String,
        ): ProgressGoalsUiState {
            if (targets.isEmpty()) return Empty

            val setsByExercise = sets.groupBy { it.exerciseId }
            val staleCutoff =
                (now.clone() as Calendar)
                    .apply { add(Calendar.WEEK_OF_YEAR, -STALE_WEEKS) }
                    .timeInMillis

            val allRows =
                targets.map { target ->
                    val exerciseSets = setsByExercise[target.exerciseId].orEmpty()
                    val status = TargetEvaluator.evaluate(target, exerciseSets)
                    val lastSet = exerciseSets.maxOfOrNull { it.loggedAtEpochMs }

                    // Restdistanz je Dimension als Anteil; die groessere
                    // entscheidet die Sortierung (R5). Beispiel: Ziel
                    // 100 kg x 5, erreicht 90 kg x 5 -> Gewicht 10 % offen,
                    // Reps 0 % -> Sortierwert 10 %.
                    val missingWeight =
                        (target.targetWeightMilliKg - (status.bestWeightMilliKg ?: 0L)).coerceAtLeast(
                            0L,
                        )
                    val missingReps = (target.targetReps - (status.bestReps ?: 0)).coerceAtLeast(0)
                    val weightFraction = missingWeight.toFloat() / target.targetWeightMilliKg
                    val repsFraction = missingReps.toFloat() / target.targetReps
                    val remaining = maxOf(weightFraction, repsFraction).coerceIn(0f, 1f)

                    ProgressGoalRow(
                        exerciseId = target.exerciseId,
                        exerciseName = exerciseNames[target.exerciseId] ?: fallbackExerciseName,
                        reached = status.reached,
                        missingWeightMilliKg = if (status.reached) 0L else missingWeight,
                        missingReps = if (status.reached) 0 else missingReps,
                        hasAnySet = exerciseSets.isNotEmpty(),
                        staleWeeks = lastSet != null && lastSet < staleCutoff,
                        // Erreicht heisst alle Punkte, auch wenn die Reps-
                        // Dimension rechnerisch noch Rest liesse.
                        filledDots =
                            if (status.reached) {
                                DOTS
                            } else {
                                ((1f - remaining) * DOTS).toInt().coerceIn(0, DOTS - 1)
                            },
                        remainingFraction = remaining,
                        lastSetEpochMs = lastSet,
                    )
                }

            // Uebungen ohne Satz gelten nicht als "laenger nicht trainiert" —
            // sie waren noch nie dran und gehoeren in die Hauptliste, damit
            // das gerade gesetzte Ziel sichtbar ist.
            val (stale, active) = allRows.partition { it.staleWeeks }

            return ProgressGoalsUiState(
                rows = active.sortedWith(goalOrder),
                staleRows = stale.sortedWith(goalOrder),
                reachedCount = allRows.count { it.reached },
                totalCount = allRows.size,
            )
        }

        /**
         * Goal-Gradient (R5): Nicht erreichte zuerst, davon das
         * Fast-Geschaffte oben. Bei Gleichstand gewinnt der juengere Satz.
         */
        private val goalOrder =
            compareBy<ProgressGoalRow> { it.reached }
                .thenBy { it.remainingFraction }
                .thenByDescending { it.lastSetEpochMs ?: Long.MIN_VALUE }
    }
}
