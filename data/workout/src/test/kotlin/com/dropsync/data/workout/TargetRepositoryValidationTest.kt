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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Validierung und Persistenz des Uebungsziels (Statuszeile der
 * Train-Konsole): 0 kg/0 Reps sind kein Ziel, Extremwerte werden
 * abgewiesen, ein gueltiges Ziel round-tript.
 */
@RunWith(AndroidJUnit4::class)
class TargetRepositoryValidationTest {
    private lateinit var db: DropSyncDatabase
    private lateinit var repository: TargetRepositoryImpl

    @Before
    fun setUp() =
        runTest {
            val context = ApplicationProvider.getApplicationContext<Context>()
            db =
                Room
                    .inMemoryDatabaseBuilder(context, DropSyncDatabase::class.java)
                    .build()
            repository =
                TargetRepositoryImpl(
                    targetDao = db.exerciseTargetDao(),
                    clock = FakeClock(initialEpochMillis = 1_700_000_000_000),
                    dispatchers = TestDispatcherProvider(),
                )
        }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `setTarget lehnt ungueltige Uebungs-ID ab`() =
        runTest {
            assertTrue(repository.setTarget(0, 100_000, 5) is AppResult.Failure)
        }

    @Test
    fun `setTarget lehnt Gewicht null ab`() =
        runTest {
            assertTrue(repository.setTarget(1, 0, 5) is AppResult.Failure)
        }

    @Test
    fun `setTarget lehnt Gewicht oberhalb des Maximums ab`() =
        runTest {
            assertTrue(repository.setTarget(1, Long.MAX_VALUE, 5) is AppResult.Failure)
        }

    @Test
    fun `setTarget lehnt Wiederholungen null ab`() =
        runTest {
            assertTrue(repository.setTarget(1, 100_000, 0) is AppResult.Failure)
        }

    @Test
    fun `setTarget speichert ein gueltiges Ziel`() =
        runTest {
            val exerciseId =
                db.exerciseDao().insertExerciseIgnoring(
                    ExerciseEntity(
                        canonicalName = "barbell_bench_press",
                        kind = "STRENGTH",
                        equipment = "BARBELL",
                        isCustom = false,
                        isArchived = false,
                    ),
                )

            val result = repository.setTarget(exerciseId, 80_000, 8)
            assertTrue(result is AppResult.Success)

            val target = repository.observeTarget(exerciseId).first()
            assertNotNull(target)
            assertEquals(80_000L, target!!.targetWeightMilliKg)
            assertEquals(8, target.targetReps)
        }
}
