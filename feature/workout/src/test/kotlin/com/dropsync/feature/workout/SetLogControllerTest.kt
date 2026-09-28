package com.dropsync.feature.workout

import app.cash.turbine.test
import com.dropsync.core.common.AppError
import com.dropsync.core.common.AppResult
import com.dropsync.domain.workout.FlatSet
import com.dropsync.domain.workout.FlatSetRepository
import com.dropsync.domain.workout.MusicContext
import com.dropsync.domain.workout.SetLogHaptics
import com.dropsync.domain.workout.SetSummaries
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A1 (T-1): Vertrag des [SetLogController] — Log/Undo/Ereignisse/Haptik.
 * Der Controller haelt keinen Scope; die Tests rufen die suspendierenden
 * Methoden direkt im [runTest]-Kontext.
 */
class SetLogControllerTest {
    private val repository = RecordingFlatSetRepository()
    private val haptics = RecordingHaptics()
    private val controller = SetLogController(repository, haptics)

    @Test
    fun `logSet emittiert Logged und feuert die haptik genau einmal`() =
        runTest {
            controller.events.test {
                val result = controller.logSet(exerciseId = 7L, weightMilliKg = 100_000, reps = 8)
                assertEquals(42L, (result as AppResult.Success).value)
                val event = awaitItem()
                assertEquals(SetLogEvent.Logged(setId = 42L, exerciseId = 7L), event)
                assertEquals("Haptik genau einmal", 1, haptics.count)
                cancelAndIgnoreRemainingEvents()
            }
        }

    /**
     * 2026-09-27, Befund 13.4: der Controller reicht den Musikbezug
     * **unveraendert** an das Repository durch.
     *
     * Das ist die Luecke, die `PlaybackSnapshotEntity` offenliess: sie
     * existierte als eigene Tabelle, wurde aber ueber einen Pfad
     * geschrieben, den der Produktivpfad nie nimmt. Ein Parameter, der
     * den ganzen Weg bis in die Transaktion geht, kann das nicht mehr.
     */
    @Test
    fun `logSet reicht den musikbezug unveraendert durch`() =
        runTest {
            val context =
                MusicContext.of(
                    songId = 4711L,
                    positionMs = 42_000L,
                    activeMarkerId = 88L,
                )
            controller.logSet(
                exerciseId = 7L,
                weightMilliKg = 100_000,
                reps = 8,
                musicContext = context,
            )
            assertEquals(context, repository.lastMusicContext)
        }

    /** Ohne Musikbezug bleibt der Default, es wird nichts erfunden. */
    @Test
    fun `logSet ohne musikbezug schreibt keinen bezug`() =
        runTest {
            controller.logSet(exerciseId = 7L, weightMilliKg = 100_000, reps = 8)
            assertEquals(MusicContext.NONE, repository.lastMusicContext)
        }

    /**
     * `MusicContext.of` mit `songId = null` ist immer leer: es gibt kein
     * "Ziel ohne Titel". Das verhindert, dass ein Marker ohne zugehoerigen
     * Song in der Historie landet.
     */
    @Test
    fun `music context ohne song id ist leer`() {
        assertEquals(
            MusicContext.NONE,
            MusicContext.of(songId = null, positionMs = 1_000L, activeMarkerId = 88L),
        )
    }

    @Test
    fun `log-fehler emittiert LogFailed und keine haptik`() =
        runTest {
            repository.logResult = AppResult.failure(AppError.DatabaseFailure("logSet"))
            controller.events.test {
                val result = controller.logSet(exerciseId = 7L, weightMilliKg = 100_000, reps = 8)
                assertTrue(result is AppResult.Failure)
                assertEquals(SetLogEvent.LogFailed, awaitItem())
                assertEquals("kein Feedback fuer nichts", 0, haptics.count)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `undo loescht den satz und emittiert Undone`() =
        runTest {
            controller.events.test {
                controller.logSet(exerciseId = 7L, weightMilliKg = 100_000, reps = 8)
                // Erst das Logged-Ereignis des Logs abholen.
                assertTrue(awaitItem() is SetLogEvent.Logged)

                val outcome = controller.undoLast()
                assertEquals(UndoOutcome(exerciseId = 7L, reps = 8), (outcome as AppResult.Success).value)
                assertEquals(listOf(42L), repository.deleted)
                assertEquals(SetLogEvent.Undone(exerciseId = 7L), awaitItem())
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `undo ohne offenes log ist ein no-op`() =
        runTest {
            assertNull(controller.undoLast())
            assertTrue(repository.deleted.isEmpty())
        }

    @Test
    fun `clearUndo verwirft ein offenes undo`() =
        runTest {
            controller.logSet(exerciseId = 7L, weightMilliKg = 100_000, reps = 8)

            controller.clearUndo()

            assertNull(controller.undoLast())
            assertTrue(repository.deleted.isEmpty())
        }

    @Test
    fun `zweiter log ersetzt das undo-ziel`() =
        runTest {
            controller.logSet(exerciseId = 7L, weightMilliKg = 100_000, reps = 8)
            controller.logSet(exerciseId = 7L, weightMilliKg = 90_000, reps = 10)

            val outcome = controller.undoLast()

            assertEquals(
                "Undo trifft nur den letzten Satz",
                listOf(43L),
                repository.deleted,
            )
            assertEquals(10, (outcome as AppResult.Success).value.reps)
        }

    @Test
    fun `undo-fehler laesst das undo offen`() =
        runTest {
            controller.logSet(exerciseId = 7L, weightMilliKg = 100_000, reps = 8)
            repository.deleteResult = AppResult.failure(AppError.DatabaseFailure("deleteSet"))

            val failed = controller.undoLast()
            assertTrue(failed is AppResult.Failure)

            repository.deleteResult = AppResult.Success(Unit)
            val retried = controller.undoLast()
            assertTrue("zweiter Versuch muss greifen", retried is AppResult.Success)
            assertEquals(listOf(42L, 42L), repository.deleted)
        }

    @Test
    fun `event-kanal puffert ein log ohne collector`() =
        runTest {
            // Kein Collector aktiv: der gepufferte Kanal darf das Ereignis
            // nicht verlieren (Snackbar kommt nach der Recomposition).
            controller.logSet(exerciseId = 7L, weightMilliKg = 100_000, reps = 8)

            controller.events.test {
                assertEquals(SetLogEvent.Logged(setId = 42L, exerciseId = 7L), awaitItem())
                cancelAndIgnoreRemainingEvents()
            }
        }

    private class RecordingFlatSetRepository : FlatSetRepository {
        var logResult: AppResult<Long> = AppResult.Success(42L)
        var deleteResult: AppResult<Unit> = AppResult.Success(Unit)
        val deleted = mutableListOf<Long>()
        private var nextId = 42L

        /**
         * Der zuletzt durchgereichten Musikbezug (2026-09-27, Befund
         * 13.4). Der Test prueft, dass der Controller ihn **unveraendert**
         * weitergibt — er darf nichts daran anreichern oder verwerfen.
         */
        var lastMusicContext: MusicContext? = null
            private set

        override fun observeSetsForExercise(exerciseId: Long): Flow<List<FlatSet>> = flowOf(emptyList())

        override fun observeAllSets(): Flow<List<FlatSet>> = flowOf(emptyList())

        override fun observeRecentSets(limit: Int): Flow<List<FlatSet>> = flowOf(emptyList())

        override suspend fun getLastSet(exerciseId: Long): AppResult<FlatSet?> = AppResult.Success(null)

        override suspend fun logSet(
            exerciseId: Long,
            weightMilliKg: Long,
            reps: Int,
            musicContext: MusicContext,
        ): AppResult<Long> {
            lastMusicContext = musicContext
            val result = logResult
            if (result is AppResult.Success && result.value == 42L) {
                // Zaehlt bei jedem Erfolg hoch (42, 43, ...), damit der
                // Undo-Test das richtige Ziel sieht.
                val id = nextId
                nextId++
                return AppResult.Success(id)
            }
            return result
        }

        override suspend fun deleteSet(setId: Long): AppResult<Unit> {
            deleted += setId
            return deleteResult
        }

        override suspend fun getMaxVolumeForExercise(exerciseId: Long): AppResult<Long?> = AppResult.Success(null)

        override suspend fun getVolumeForDay(dayStart: Long): AppResult<Long?> = AppResult.Success(null)

        override suspend fun getRecentSets(limit: Int): AppResult<List<FlatSet>> = AppResult.Success(emptyList())

        override suspend fun getSetSummaries(
            exerciseId: Long,
            recentLimit: Int,
        ): AppResult<SetSummaries> = AppResult.Success(SetSummaries(null, null, emptyList()))
    }

    private class RecordingHaptics : SetLogHaptics {
        var count = 0
        var tapCount = 0

        override fun confirm() {
            count++
        }

        override fun tap() {
            tapCount++
        }
    }
}
