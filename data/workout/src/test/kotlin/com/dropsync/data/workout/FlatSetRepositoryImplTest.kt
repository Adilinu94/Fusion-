package com.dropsync.data.workout

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dropsync.core.common.AppResult
import com.dropsync.core.database.DropSyncDatabase
import com.dropsync.core.database.entity.ExerciseEntity
import com.dropsync.core.testing.FakeClock
import com.dropsync.core.testing.TestDispatcherProvider
import com.dropsync.domain.workout.FlatSetRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Repository tests for the flat set log (FlowRep Phase 2) against a real
 * in-memory Room database, so the DAO SQL is verified as well.
 */
@RunWith(AndroidJUnit4::class)
class FlatSetRepositoryImplTest {
    private lateinit var db: DropSyncDatabase
    private lateinit var repository: FlatSetRepository
    private val clock = FakeClock(initialEpochMillis = 1_700_000_000_000)

    private var exerciseId: Long = 0
    private var otherExerciseId: Long = 0

    @Before
    fun setUp() =
        runTest {
            val context = ApplicationProvider.getApplicationContext<Context>()
            db =
                Room
                    .inMemoryDatabaseBuilder(context, DropSyncDatabase::class.java)
                    .build()
            repository =
                FlatSetRepositoryImpl(
                    flatSetDao = db.flatSetDao(),
                    clock = clock,
                    dispatchers = TestDispatcherProvider(),
                )
            exerciseId =
                db.exerciseDao().insertExerciseIgnoring(
                    ExerciseEntity(
                        canonicalName = "barbell_back_squat",
                        kind = "STRENGTH",
                        equipment = "BARBELL",
                        isCustom = false,
                        isArchived = false,
                    ),
                )
            otherExerciseId =
                db.exerciseDao().insertExerciseIgnoring(
                    ExerciseEntity(
                        canonicalName = "lat_pulldown",
                        kind = "STRENGTH",
                        equipment = "CABLE",
                        isCustom = false,
                        isArchived = false,
                    ),
                )
        }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun logSet(
        exercise: Long = exerciseId,
        weightMilliKg: Long,
        reps: Int,
    ): Long = (repository.logSet(exercise, weightMilliKg, reps) as AppResult.Success).value

    @Test
    fun `log set persists weight in millikg and reps`() =
        runTest {
            // 80 kg, 8 reps.
            val setId = logSet(weightMilliKg = 80_000_000, reps = 8)
            assertTrue(setId > 0)

            val stored = db.flatSetDao().getById(setId)!!
            assertEquals(exerciseId, stored.exerciseId)
            assertEquals(80_000_000L, stored.weightMilliKg)
            assertEquals(8, stored.reps)
            assertEquals(clock.epochMillis(), stored.loggedAtEpochMs)
        }

    @Test
    fun `last set returns the most recent set of that exercise only`() =
        runTest {
            logSet(weightMilliKg = 60_000_000, reps = 10)
            clock.advanceBy(60_000)
            logSet(weightMilliKg = 80_000_000, reps = 8)
            clock.advanceBy(60_000)
            // Newer set of a different exercise must not leak into the result.
            logSet(exercise = otherExerciseId, weightMilliKg = 200_000_000, reps = 1)

            val last = (repository.getLastSet(exerciseId) as AppResult.Success).value!!
            assertEquals(80_000_000L, last.weightMilliKg)
            assertEquals(8, last.reps)
        }

    @Test
    fun `last set is null for an exercise without sets`() =
        runTest {
            logSet(weightMilliKg = 60_000_000, reps = 10)

            val last = (repository.getLastSet(otherExerciseId) as AppResult.Success).value
            assertNull(last)
        }

    @Test
    fun `max volume is the largest weight times reps product`() =
        runTest {
            // 100 kg x 5 = 500 kg volume.
            logSet(weightMilliKg = 100_000_000, reps = 5)
            clock.advanceBy(60_000)
            // 60 kg x 10 = 600 kg volume: higher volume with lower weight.
            logSet(weightMilliKg = 60_000_000, reps = 10)
            clock.advanceBy(60_000)
            // 120 kg x 3 = 360 kg volume.
            logSet(weightMilliKg = 120_000_000, reps = 3)

            val maxVolume =
                (repository.getMaxVolumeForExercise(exerciseId) as AppResult.Success).value!!
            assertEquals(600_000_000L, maxVolume)
        }

    @Test
    fun `max volume is null for an exercise without sets`() =
        runTest {
            logSet(weightMilliKg = 100_000_000, reps = 5)

            val maxVolume =
                (repository.getMaxVolumeForExercise(otherExerciseId) as AppResult.Success).value
            assertNull(maxVolume)
        }

    @Test
    fun `day volume sums only sets within that day`() =
        runTest {
            val dayStart = clock.epochMillis()
            // Two sets today: 500 + 600 = 1100 kg total.
            logSet(weightMilliKg = 100_000_000, reps = 5)
            clock.advanceBy(60_000)
            logSet(weightMilliKg = 60_000_000, reps = 10)
            // One set the next day, outside the queried window.
            clock.advanceBy(86_400_000)
            logSet(weightMilliKg = 200_000_000, reps = 10)

            val dayVolume =
                (repository.getVolumeForDay(dayStart) as AppResult.Success).value!!
            assertEquals(1_100_000_000L, dayVolume)
        }

    @Test
    fun `recent sets returns newest first across exercises and honors limit`() =
        runTest {
            logSet(weightMilliKg = 50_000_000, reps = 10)
            clock.advanceBy(60_000)
            logSet(exercise = otherExerciseId, weightMilliKg = 70_000_000, reps = 6)
            clock.advanceBy(60_000)
            logSet(weightMilliKg = 90_000_000, reps = 4)

            val recent = (repository.getRecentSets(2) as AppResult.Success).value
            assertEquals(2, recent.size)
            assertEquals(90_000_000L, recent[0].weightMilliKg)
            assertEquals(70_000_000L, recent[1].weightMilliKg)
        }

    @Test
    fun `delete set removes it from the log`() =
        runTest {
            val setId = logSet(weightMilliKg = 80_000_000, reps = 8)

            val result = repository.deleteSet(setId)
            assertTrue(result is AppResult.Success)
            assertNull(db.flatSetDao().getById(setId))
        }

    @Test
    fun `observe sets for exercise streams newest first`() =
        runTest {
            logSet(weightMilliKg = 60_000_000, reps = 10)
            clock.advanceBy(60_000)
            logSet(weightMilliKg = 80_000_000, reps = 8)

            val sets = repository.observeSetsForExercise(exerciseId).first()
            assertEquals(listOf(80_000_000L, 60_000_000L), sets.map { it.weightMilliKg })
        }

    @Test
    fun `volume kg converts millikg times reps to kilograms`() =
        runTest {
            // 80 kg x 8 reps = 640 kg volume.
            val setId = logSet(weightMilliKg = 80_000_000, reps = 8)

            val set = repository.observeAllSets().first().single { it.id == setId }
            assertEquals(640.0, set.volumeKg, 0.0001)
        }
}
