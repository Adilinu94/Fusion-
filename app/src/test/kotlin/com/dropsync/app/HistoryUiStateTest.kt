package com.dropsync.app

import com.dropsync.domain.workout.ExerciseInfo
import com.dropsync.domain.workout.FlatSet
import org.junit.Assert.assertEquals
import org.junit.Test

class HistoryUiStateTest {
    @Test
    fun `uses highest volume set per exercise as personal record`() {
        val state =
            HistoryUiState.from(
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
            )

        assertEquals(listOf(2L, 3L), state.personalRecords.map { it.set.id })
        assertEquals(listOf("Kniebeuge", "Kreuzheben"), state.personalRecords.map { it.exerciseName })
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
