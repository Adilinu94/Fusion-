package com.dropsync.feature.progress

import com.dropsync.core.model.PrType
import com.dropsync.core.model.PrValueUnit
import com.dropsync.domain.workout.ExerciseInfo
import com.dropsync.domain.workout.FlatSet
import com.dropsync.domain.workout.PrRecord
import org.junit.Assert.assertEquals
import org.junit.Test

class AllSetsUiStateTest {
    @Test
    fun `verwendet echte PRs aus personal records, juengster zuerst`() {
        val state =
            AllSetsUiState.from(
                sets =
                    listOf(
                        flatSet(id = 1, exerciseId = 10, weightMilliKg = 80_000_000, reps = 8),
                        flatSet(id = 2, exerciseId = 10, weightMilliKg = 70_000_000, reps = 10),
                        flatSet(id = 3, exerciseId = 20, weightMilliKg = 100_000_000, reps = 5),
                    ),
                exercises =
                    listOf(
                        ExerciseInfo(10, "squat", "Kniebeuge"),
                        ExerciseInfo(20, "deadlift", "Kreuzheben"),
                    ),
                fallbackExerciseName = "Übung",
                personalRecords =
                    listOf(
                        prRecord(exerciseId = 10, at = 2_000L),
                        prRecord(exerciseId = 20, at = 3_000L),
                    ),
            )

        assertEquals(listOf("Kreuzheben", "Kniebeuge"), state.personalRecords.map { it.exerciseName })
        assertEquals(3, state.allSets.size)
    }

    @Test
    fun `unbekannte Uebungs-IDs fallen auf den Fallback-Namen`() {
        val state =
            AllSetsUiState.from(
                sets = listOf(flatSet(id = 7, exerciseId = 99, weightMilliKg = 60_000_000, reps = 3)),
                exercises = emptyList(),
                fallbackExerciseName = "Übung",
                personalRecords = listOf(prRecord(exerciseId = 99, at = 1L)),
            )

        assertEquals(listOf("Übung"), state.allSets.map { it.exerciseName })
        assertEquals(listOf("Übung"), state.personalRecords.map { it.exerciseName })
    }

    private fun prRecord(
        exerciseId: Long,
        at: Long,
    ) = PrRecord(
        exerciseId = exerciseId,
        type = PrType.HIGHEST_LOAD,
        achievedSessionId = null,
        achievedClusterId = null,
        valueLong = 80_000_000,
        valueUnit = PrValueUnit.MILLI_KG,
        comparableLoadMilliKg = 80_000_000,
        achievedAtEpochMs = at,
    )

    private fun flatSet(
        id: Long,
        exerciseId: Long,
        weightMilliKg: Long,
        reps: Int,
    ) = FlatSet(
        id = id,
        exerciseId = exerciseId,
        weightMilliKg = weightMilliKg,
        reps = reps,
        loggedAtEpochMs = id,
    )
}
