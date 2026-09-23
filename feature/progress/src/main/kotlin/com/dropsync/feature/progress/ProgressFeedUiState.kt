package com.dropsync.feature.progress

import com.dropsync.domain.workout.FlatSet
import com.dropsync.domain.workout.PrRecord
import java.util.Calendar

/** Eine Satz-Zeile im Dashboard (Tile 7) bzw. der Alle-Saetze-Liste. */
data class ProgressSetRow(
    val set: FlatSet,
    val exerciseName: String,
)

/** Eine echte PR-Zeile (Befund 3.14/153) fuer Tile 5 des Dashboards. */
data class ProgressPrRow(
    val record: PrRecord,
    val exerciseName: String,
)

/**
 * Zeilen-Feed des Dashboards: die letzten zehn Saetze (Tile 7) und
 * persoenliche Bestwerte der letzten sieben Tage (Tile 5, UI-Vertrag R6).
 *
 * [freshRecords] ist die volumenbasierte Sicht (Satz mit dem hoechsten
 * Volumen je Uebung, juenger als sieben Tage). [newPrRecords] enthaelt die
 * echten PRs aus der personal_records-Tabelle (drei PR-Arten aus dem
 * PrCalculator, Befund 3.14/153), ebenfalls auf die letzten sieben Tage
 * gefiltert — Tile 5 zeigt die echte Sicht. Die Alle-Saetze-Route nutzt
 * seit Befund 5.8 dieselben echten PRs ([AllSetsUiState]).
 */
data class ProgressFeedUiState(
    val recentSets: List<ProgressSetRow>,
    val freshRecords: List<ProgressSetRow>,
    val newPrRecords: List<ProgressPrRow> = emptyList(),
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
            personalRecords: List<PrRecord> = emptyList(),
            prExerciseNames: Map<Long, String> = exerciseNames,
        ): ProgressFeedUiState {
            if (sets.isEmpty() && personalRecords.isEmpty()) return Empty
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
            val newPrRecords =
                personalRecords
                    .filter { it.achievedAtEpochMs >= freshCutoff }
                    .map { ProgressPrRow(it, prExerciseNames[it.exerciseId] ?: fallbackExerciseName) }
                    .sortedByDescending { it.record.achievedAtEpochMs }
            return ProgressFeedUiState(
                recentSets = rows.take(RECENT_SETS),
                freshRecords = freshRecords,
                newPrRecords = newPrRecords,
            )
        }
    }
}
