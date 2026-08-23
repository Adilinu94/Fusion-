package com.dropsync.data.workout

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.dropsync.domain.workout.WorkoutGoalRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.workoutGoalDataStore by preferencesDataStore(name = "workout_goal_prefs")

/**
 * DataStore-Persistenz des Wochenziels (Schritt 7, Muster
 * RestTimerPreferencesStore): eine Ganzzahl 1..7, Default 3. Der Wert traegt
 * den Wochenring des Progress-Dashboards und bleibt Geraet-einstellung.
 */
class WorkoutGoalPreferencesStore(
    private val context: Context,
) : WorkoutGoalRepository {
    private val weeklyGoalKey = intPreferencesKey("weekly_training_goal")

    override val weeklyTrainingGoal: Flow<Int> =
        context.workoutGoalDataStore.data.map { prefs ->
            prefs[weeklyGoalKey] ?: WorkoutGoalRepository.DEFAULT_WEEKLY_GOAL
        }

    override suspend fun setWeeklyTrainingGoal(days: Int) {
        val clamped =
            days.coerceIn(
                WorkoutGoalRepository.MIN_WEEKLY_GOAL,
                WorkoutGoalRepository.MAX_WEEKLY_GOAL,
            )
        context.workoutGoalDataStore.edit { prefs ->
            prefs[weeklyGoalKey] = clamped
        }
    }
}
