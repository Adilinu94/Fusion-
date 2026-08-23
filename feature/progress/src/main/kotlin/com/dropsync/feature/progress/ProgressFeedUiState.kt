package com.dropsync.feature.progress

import com.dropsync.domain.workout.FlatSet
import java.util.Calendar

/** Eine Satz-Zeile im Dashboard (Tile 7) bzw. der Alle-Saetze-Liste. */
data class ProgressSetRow(
    val set: FlatSet,
    val exerciseName: String,
)

/**
 * Zeilen-Feed des Dashboards: die letzten zehn Saetze (Tile 7) und
 * persoenliche Bestwerte der letzten sieben Tage (Tile 5, UI-Vertrag R6).
 *
 * Ein Bestwert ist der Satz mit dem hoechsten Volumen je Uebung (E5, dieselbe
 * Definition wie FlatSetDao.getMaxVolumeForExercise) — ist dieser Rekordsatz
 * juenger als sieben Tage, hat er den bisherigen Rekord gerade abgeloest und
 * wird als neue Zeile genannt. Das Dashboard feiert ihn bewusst nicht (R6):
 * Der Konfetti-Moment liegt im TrainScreen, hier gibt es nur die Zeile.
 */
data class ProgressFeedUiState(
    val recentSets: List<ProgressSetRow>,
    val freshRecords: List<ProgressSetRow>,
) {
    companion object {
        private const val RECENT_SETS = 10
        private const val FRESH_RECORD_WINDOW_DAYS = 7

        val Empty = ProgressFeedUiState(emptyList(), emptyList())

        fun from(
            sets: List<FlatSet>,
            exerciseNames: Map<Long, String>,
            now: Calendar,
            fallbackExerciseName: String,
        ): ProgressFeedUiState {
            if (sets.isEmpty()) return Empty
            // Saetze kommen absteigend vom Repository (juengster zuerst).
            val rows = sets.map { ProgressSetRow(it, exerciseNames[it.exerciseId] ?: fallbackExerciseName) }
            val freshCutoff =
                (now.clone() as Calendar)
                    .apply { add(Calendar.DAY_OF_YEAR, -FRESH_RECORD_WINDOW_DAYS) }
                    .timeInMillis
            val freshRecords =
                rows
                    .groupBy { it.set.exerciseId }
                    .values
                    .mapNotNull { exerciseRows -> exerciseRows.maxByOrNull { it.set.volumeKg } }
                    .filter { it.set.loggedAtEpochMs >= freshCutoff }
                    .sortedByDescending { it.set.loggedAtEpochMs }
            return ProgressFeedUiState(
                recentSets = rows.take(RECENT_SETS),
                freshRecords = freshRecords,
            )
        }
    }
}
