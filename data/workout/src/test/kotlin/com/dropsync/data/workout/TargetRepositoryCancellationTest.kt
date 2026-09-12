package com.dropsync.data.workout

import com.dropsync.core.common.DispatcherProvider
import com.dropsync.core.database.dao.ExerciseTargetDao
import com.dropsync.core.database.entity.ExerciseTargetEntity
import com.dropsync.core.testing.FakeClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.fail
import org.junit.Test

/**
 * Abbruch-Test (Ausbauplan A3): Eine `CancellationException` aus der
 * Datenquelle muss propagieren — auch bei aktivem Job. Ohne den
 * Cancellation-Rethrow verwandelt `catch (e: Exception)` den Abbruch in
 * `AppResult.failure` (Gegenbeweis: Test rot ohne Fix).
 *
 * Hintergrund: An den strukturierten Job-Grenzen (`withContext`, `await`)
 * fängt die Coroutine-Maschinerie Abbrueche selbst ab — der Unterschied wird
 * genau dort sichtbar, wo kein Grenzschutz greift (Fire-and-forget-Launches
 * mit Fehler-UI, Retry-Logik, `withTimeoutOrNull`, Future-Brücken).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TargetRepositoryCancellationTest {
    private class CancellingExerciseTargetDao : ExerciseTargetDao {
        override suspend fun upsert(target: ExerciseTargetEntity) = Unit

        override suspend fun delete(exerciseId: Long) = Unit

        override suspend fun getForExercise(exerciseId: Long): ExerciseTargetEntity? =
            throw CancellationException("Abbruch aus der Datenquelle bei aktivem Job")

        override fun observeForExercise(exerciseId: Long): Flow<ExerciseTargetEntity?> = flowOf(null)

        override fun observeAll(): Flow<List<ExerciseTargetEntity>> = flowOf(emptyList())
    }

    private val dispatcher = UnconfinedTestDispatcher()
    private val dispatchers: DispatcherProvider =
        object : DispatcherProvider {
            override val io = dispatcher
            override val default = dispatcher
            override val main = dispatcher
        }
    private val clock = FakeClock(initialElapsedRealtimeMs = 50_000, initialEpochMillis = 1_000)

    @Test
    fun `Abbruch aus der Datenquelle wird nicht in AppResult verwandelt`() =
        runTest {
            val repo = TargetRepositoryImpl(CancellingExerciseTargetDao(), clock, dispatchers)
            try {
                repo.getTarget(1L)
                fail("CancellationException muss propagieren, kein AppResult liefern")
            } catch (e: CancellationException) {
                // Erwartet: Abbruch bleibt Abbruch.
            }
        }
}
