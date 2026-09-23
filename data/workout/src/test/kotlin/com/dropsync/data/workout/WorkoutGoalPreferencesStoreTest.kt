package com.dropsync.data.workout

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dropsync.domain.workout.WorkoutGoalRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Wochenziel-Persistenz (Schritt 7, Muster RestTimerPreferencesStore):
 * Default 3, Roundtrip und Clamping auf 1..7.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class WorkoutGoalPreferencesStoreTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val store = WorkoutGoalPreferencesStore(context)

    @Before
    fun reset() =
        runTest {
            store.setWeeklyTrainingGoal(WorkoutGoalRepository.DEFAULT_WEEKLY_GOAL)
        }

    @Test
    fun `default ist 3`() =
        runTest {
            assertEquals(3, store.weeklyTrainingGoal.first())
        }

    @Test
    fun `gesetztes Ziel round-tript`() =
        runTest {
            store.setWeeklyTrainingGoal(5)
            assertEquals(5, store.weeklyTrainingGoal.first())
        }

    @Test
    fun `Ziel wird auf 1 bis 7 begrenzt`() =
        runTest {
            store.setWeeklyTrainingGoal(0)
            assertEquals(1, store.weeklyTrainingGoal.first())

            store.setWeeklyTrainingGoal(99)
            assertEquals(7, store.weeklyTrainingGoal.first())
        }
}
