package com.dropsync.feature.progress

import com.dropsync.domain.workout.ExerciseInfo
import com.dropsync.domain.workout.FlatSet
import org.junit.Assert.assertEquals
import org.junit.Test

class AllSetsUiStateTest {
    @Test
    fun `uses highest volume set per exercise as personal record`() {
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
            )

        assertEquals(listOf(2L, 3L), state.personalRecords.map { it.set.id })
        assertEquals(listOf("Kniebeuge", "Kreuzheben"), state.personalRecords.map { it.exerciseName })
        assertEquals(3, state.allSets.size)
    }

    @Test
    fun `unbekannte Uebungs-IDs fallen auf den Fallback-Namen`() {
        val state =
            AllSetsUiState.from(
                sets = listOf(flatSet(id = 7, exerciseId = 99, weightMilliKg = 60_000_000, reps = 3)),
                exercises = emptyList(),
                fallbackExerciseName = "Übung",
            )

        assertEquals(listOf("Übung"), state.allSets.map { it.exerciseName })
    }

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
