package com.dropsync.data.workout

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dropsync.core.common.AppResult
import com.dropsync.core.database.DropSyncDatabase
import com.dropsync.core.database.RoomTransactionRunner
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
                    transactionRunner = RoomTransactionRunner(db),
                    recomputer = PersonalRecordRecomputer(db.workoutDao(), db.flatSetDao()),
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

    // --- A1/5.7: flacher Satz-Pfad als PR-Quelle --------------------------

    @Test
    fun `flacher satz erzeugt die drei PR-arten ohne session`() =
        runTest {
            // 100 kg x 5 und 100 kg x 8: gleiche Last, das spaetere Volumen
            // (800) ist der Session-Rekord; jeder Satz ist seine eigene
            // Mini-Session (kein Aufsummieren ueber die Historie).
            logSet(weightMilliKg = 100_000_000, reps = 5)
            clock.advanceBy(60_000)
            logSet(weightMilliKg = 100_000_000, reps = 8)

            val records = db.workoutDao().getPersonalRecordsForExercise(exerciseId)
            val byType = records.associateBy { it.type }
            assertEquals(3, records.size)
            assertEquals(100_000_000L, byType.getValue("HIGHEST_LOAD").valueLong)
            assertEquals(8L, byType.getValue("MOST_REPS_AT_LOAD").valueLong)
            assertEquals(100_000_000L * 8, byType.getValue("HIGHEST_SESSION_VOLUME").valueLong)
            assertTrue(
                "flache PRs haben keine Session",
                records.all { it.achievedSessionId == null },
            )
        }

    @Test
    fun `delete set rechnet die PRs aus der resthistorie neu`() =
        runTest {
            logSet(weightMilliKg = 100_000_000, reps = 5)
            clock.advanceBy(60_000)
            val heavierSetId = logSet(weightMilliKg = 90_000_000, reps = 10)
            assertEquals(
                "Volumen-PR liegt zunaechst beim zweiten Satz",
                90_000_000L * 10,
                db
                    .workoutDao()
                    .getPersonalRecordsForExercise(exerciseId)
                    .first { it.type == "HIGHEST_SESSION_VOLUME" }
                    .valueLong,
            )

            repository.deleteSet(heavierSetId)

            val byType =
                db
                    .workoutDao()
                    .getPersonalRecordsForExercise(exerciseId)
                    .associateBy { it.type }
            assertEquals(100_000_000L, byType.getValue("HIGHEST_LOAD").valueLong)
            assertEquals(100_000_000L * 5, byType.getValue("HIGHEST_SESSION_VOLUME").valueLong)
        }

    @Test
    fun `letzter satz entfernt alle PRs`() =
        runTest {
            val setId = logSet(weightMilliKg = 80_000_000, reps = 8)
            assertTrue(db.workoutDao().getPersonalRecordsForExercise(exerciseId).isNotEmpty())

            repository.deleteSet(setId)

            assertTrue(db.workoutDao().getPersonalRecordsForExercise(exerciseId).isEmpty())
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

    // --- Umbauplan Phase 10.5: Eingabevalidierung an der Repo-Grenze --------

    @Test
    fun `negative weight is rejected`() =
        runTest {
            assertTrue(repository.logSet(exerciseId, -1L, 8) is AppResult.Failure)
        }

    @Test
    fun `zero reps is rejected`() =
        runTest {
            assertTrue(repository.logSet(exerciseId, 80_000_000, 0) is AppResult.Failure)
        }

    @Test
    fun `negative reps is rejected`() =
        runTest {
            assertTrue(repository.logSet(exerciseId, 80_000_000, -5) is AppResult.Failure)
        }

    @Test
    fun `absurd weight above one tonne is rejected`() =
        runTest {
            assertTrue(
                repository.logSet(exerciseId, 1_000_000_001L, 1) is AppResult.Failure,
            )
        }

    @Test
    fun `absurd rep count is rejected`() =
        runTest {
            assertTrue(repository.logSet(exerciseId, 80_000_000, 10_001) is AppResult.Failure)
        }

    @Test
    fun `invalid exercise id is rejected`() =
        runTest {
            assertTrue(repository.logSet(-1L, 80_000_000, 8) is AppResult.Failure)
        }

    // --- Befund 5.2: gebuendelter Refetch ----------------------------------

    @Test
    fun `set summaries buendeln letzten satz max volumen und verlauf`() =
        runTest {
            logSet(weightMilliKg = 60_000_000, reps = 10)
            clock.advanceBy(60_000)
            logSet(weightMilliKg = 80_000_000, reps = 8)
            clock.advanceBy(60_000)
            // Darf nicht in die Zusammenfassung dieser Uebung gelangen.
            logSet(exercise = otherExerciseId, weightMilliKg = 200_000_000, reps = 1)

            val summaries = (repository.getSetSummaries(exerciseId, 5) as AppResult.Success).value
            assertEquals(80_000_000L, summaries.lastSet?.weightMilliKg)
            // Max-Volumen: 80kg x 8 = 640.000.000 > 60kg x 10 = 600.000.000.
            assertEquals(80_000_000L * 8, summaries.maxVolumeMilliKg)
            assertEquals(3, summaries.recentSets.size)
            assertEquals(200_000_000L, summaries.recentSets.first().weightMilliKg)
        }

    @Test
    fun `set summaries leerer uebung liefern null und leere liste`() =
        runTest {
            val summaries = (repository.getSetSummaries(exerciseId, 5) as AppResult.Success).value
            assertNull(summaries.lastSet)
            assertNull(summaries.maxVolumeMilliKg)
            assertTrue(summaries.recentSets.isEmpty())
        }
}
