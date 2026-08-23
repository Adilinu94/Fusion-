package com.dropsync.core.testing

import com.dropsync.domain.workout.WorkoutGoalRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * [WorkoutGoalRepository]-Fake: Werkseinstellungen, konfigurierbar
 * über Constructor-Parameter. Rein JVM.
 */
class FakeWorkoutGoalRepository(
    goal: Int = WorkoutGoalRepository.DEFAULT_WEEKLY_GOAL,
) : WorkoutGoalRepository {
    override val weeklyTrainingGoal: Flow<Int> = flowOf(goal)

    override suspend fun setWeeklyTrainingGoal(days: Int) = Unit
}
