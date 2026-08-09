package com.dropsync.data.workout

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dropsync.core.database.DropSyncDatabase
import com.dropsync.core.database.RoomTransactionRunner
import com.dropsync.core.database.entity.ExerciseEntity
import com.dropsync.core.database.entity.SetRoleEntity
import com.dropsync.core.database.entity.SongEntity
import com.dropsync.core.model.PrType
import com.dropsync.core.model.RestMode
import com.dropsync.core.model.SessionStatus
import com.dropsync.core.model.SetRole
import com.dropsync.core.model.Song
import com.dropsync.core.testing.FakeClock
import com.dropsync.core.testing.TestDispatcherProvider
import com.dropsync.domain.playback.PersistedPlayerState
import com.dropsync.domain.playback.PlaybackRepository
import com.dropsync.domain.playback.PlaybackState
import com.dropsync.domain.playback.RepeatMode
import com.dropsync.domain.workout.RestPref
import com.dropsync.domain.workout.SegmentInput
import com.dropsync.domain.workout.SwapStrategy
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Fake fuer die optionale Musikverknuepfung (11.1): liefert einen frei
 * einstellbaren snapshotNow-Zustand; alle Kommandos sind No-ops.
 */
private class FakePlaybackRepository : PlaybackRepository {
    var snapshot: PlaybackState = PlaybackState()

    override val state: Flow<PlaybackState> = flowOf(PlaybackState())

    override suspend fun setQueue(
        songs: List<Song>,
        startIndex: Int,
        playWhenReady: Boolean,
    ): com.dropsync.core.common.AppResult<Unit> =
        com.dropsync.core.common.AppResult
            .success(Unit)

    override suspend fun play(): com.dropsync.core.common.AppResult<Unit> =
        com.dropsync.core.common.AppResult
            .success(Unit)

    override suspend fun pause(): com.dropsync.core.common.AppResult<Unit> =
        com.dropsync.core.common.AppResult
            .success(Unit)

    override suspend fun seekTo(positionMs: Long): com.dropsync.core.common.AppResult<Unit> =
        com.dropsync.core.common.AppResult
            .success(Unit)

    override suspend fun skipToNext(): com.dropsync.core.common.AppResult<Unit> =
        com.dropsync.core.common.AppResult
            .success(Unit)

    override suspend fun skipToPrevious(): com.dropsync.core.common.AppResult<Unit> =
        com.dropsync.core.common.AppResult
            .success(Unit)

    override suspend fun skipToQueueIndex(index: Int): com.dropsync.core.common.AppResult<Unit> =
        com.dropsync.core.common.AppResult
            .success(Unit)

    override suspend fun moveInQueue(
        fromIndex: Int,
        toIndex: Int,
    ): com.dropsync.core.common.AppResult<Unit> =
        com.dropsync.core.common.AppResult
            .success(Unit)

    override suspend fun removeFromQueue(index: Int): com.dropsync.core.common.AppResult<Unit> =
        com.dropsync.core.common.AppResult
            .success(Unit)

    override suspend fun playNext(song: Song): com.dropsync.core.common.AppResult<Unit> =
        com.dropsync.core.common.AppResult
            .success(Unit)

    override suspend fun addToQueueEnd(song: Song): com.dropsync.core.common.AppResult<Unit> =
        com.dropsync.core.common.AppResult
            .success(Unit)

    override suspend fun setShuffle(enabled: Boolean): com.dropsync.core.common.AppResult<Unit> =
        com.dropsync.core.common.AppResult
            .success(Unit)

    override suspend fun setRepeatMode(mode: RepeatMode): com.dropsync.core.common.AppResult<Unit> =
        com.dropsync.core.common.AppResult
            .success(Unit)

    override suspend fun lastPersistedState(): PersistedPlayerState? = null

    override suspend fun snapshotNow(): com.dropsync.core.common.AppResult<PlaybackState> =
        com.dropsync.core.common.AppResult
            .success(snapshot)

    override suspend fun crossfadeTo(
        song: Song,
        startPositionMs: Long,
    ): com.dropsync.core.common.AppResult<Unit> =
        com.dropsync.core.common.AppResult
            .success(Unit)
}

/**
 * Repository-Tests gegen eine echte In-Memory-Room-DB: prueft damit
 * auch die SQL der qualifizierten Historie (Bauplan 5.4, Schritt 10).
 */
@RunWith(AndroidJUnit4::class)
class WorkoutRepositoryImplTest {
    private lateinit var db: DropSyncDatabase
    private lateinit var repository: WorkoutRepositoryImpl
    private val clock = FakeClock(initialEpochMillis = 1_700_000_000_000)
    private val playback = FakePlaybackRepository()

    private var exerciseId: Long = 0

    @Before
    fun setUp() =
        runTest {
            val context = ApplicationProvider.getApplicationContext<Context>()
            db =
                Room
                    .inMemoryDatabaseBuilder(context, DropSyncDatabase::class.java)
                    .build()
            repository =
                WorkoutRepositoryImpl(
                    workoutDao = db.workoutDao(),
                    routineDao = db.routineDao(),
                    exerciseDao = db.exerciseDao(),
                    transactionRunner = RoomTransactionRunner(db),
                    clock = clock,
                    dispatchers = TestDispatcherProvider(),
                    playbackRepository = playback,
                )
            // Lookup-Tabelle fuellen, die produktiv der ExerciseSeeder liefert
            // (FK set_clusters.set_role -> set_roles.id, RESTRICT).
            db.exerciseDao().insertSetRolesIgnoring(SetRole.entries.map { SetRoleEntity(it.name) })
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
        }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun startSessionWithExercise(): Pair<Long, Long> {
        val sessionId = (repository.startSession(null, null) as com.dropsync.core.common.AppResult.Success).value
        val sessionExerciseId =
            (repository.addExercise(sessionId, exerciseId, null) as com.dropsync.core.common.AppResult.Success).value
        return sessionId to sessionExerciseId
    }

    @Test
    fun `satzabschluss speichert segmente cluster und prs atomar`() =
        runTest {
            val (sessionId, sessionExerciseId) = startSessionWithExercise()

            // Abnahme Schritt 10: 30 kg pro Hand, 10 Wdh, Multiplikator 2.
            val result =
                repository.completeCluster(
                    sessionExerciseId,
                    SetRole.WORKING,
                    listOf(SegmentInput(30_000, 2, 10)),
                    note = null,
                )
            assertTrue(result is com.dropsync.core.common.AppResult.Success)

            val records = db.workoutDao().getPersonalRecordsForExercise(exerciseId)
            val volumePr = records.single { it.type == PrType.HIGHEST_SESSION_VOLUME.name }
            assertEquals(600_000, volumePr.valueLong) // 600 kg
            assertEquals(sessionId, volumePr.achievedSessionId)
            val loadPr = records.single { it.type == PrType.HIGHEST_LOAD.name }
            assertEquals(60_000, loadPr.valueLong) // 2 x 30 kg effektiv
        }

    @Test
    fun `dropset ist ein arbeitsset mit summiertem volumen`() =
        runTest {
            val (_, sessionExerciseId) = startSessionWithExercise()

            repository.completeCluster(
                sessionExerciseId,
                SetRole.WORKING,
                listOf(
                    SegmentInput(80_000, 1, 8),
                    SegmentInput(60_000, 1, 6),
                    SegmentInput(40_000, 1, 10),
                ),
                note = null,
            )

            val clusters = db.workoutDao().getClustersForSessionExercise(sessionExerciseId)
            assertEquals(1, clusters.size) // genau ein Arbeitsset
            assertEquals(3, db.workoutDao().countSegments(clusters.single().id))

            val volumePr =
                db
                    .workoutDao()
                    .getPersonalRecordsForExercise(exerciseId)
                    .single { it.type == PrType.HIGHEST_SESSION_VOLUME.name }
            assertEquals(80_000L * 8 + 60_000L * 6 + 40_000L * 10, volumePr.valueLong)
        }

    @Test
    fun `gleichstand in spaeterer session erzeugt keine neue pr`() =
        runTest {
            val (firstSessionId, firstExercise) = startSessionWithExercise()
            repository.completeCluster(
                firstExercise,
                SetRole.WORKING,
                listOf(SegmentInput(100_000, 1, 5)),
                null,
            )
            repository.completeSession(firstSessionId)
            clock.advanceBy(86_400_000) // naechster Tag

            val (_, secondExercise) = startSessionWithExercise()
            repository.completeCluster(
                secondExercise,
                SetRole.WORKING,
                listOf(SegmentInput(100_000, 1, 5)),
                null,
            )

            val loadPr =
                db
                    .workoutDao()
                    .getPersonalRecordsForExercise(exerciseId)
                    .single { it.type == PrType.HIGHEST_LOAD.name }
            // Rekord bleibt bei der ersten Session (Gleichstand).
            assertEquals(firstSessionId, loadPr.achievedSessionId)
        }

    @Test
    fun `warmup qualifiziert nie fuer volumen oder prs`() =
        runTest {
            val (_, sessionExerciseId) = startSessionWithExercise()
            repository.completeCluster(
                sessionExerciseId,
                SetRole.WARMUP,
                listOf(SegmentInput(200_000, 1, 10)),
                null,
            )

            assertTrue(db.workoutDao().getPersonalRecordsForExercise(exerciseId).isEmpty())
        }

    @Test
    fun `discard zaehlt nicht mehr fuer prs loescht aber nichts`() =
        runTest {
            val (sessionId, sessionExerciseId) = startSessionWithExercise()
            repository.completeCluster(
                sessionExerciseId,
                SetRole.WORKING,
                listOf(SegmentInput(120_000, 1, 5)),
                null,
            )
            repository.discardSession(sessionId)
            // Neuberechnung nach Korrektur (10.4).
            repository.recomputeRecords(exerciseId)

            assertTrue(db.workoutDao().getPersonalRecordsForExercise(exerciseId).isEmpty())
            // Daten existieren weiter; nur der Status ist DISCARDED (9.8).
            assertEquals(
                SessionStatus.DISCARDED.name,
                db.workoutDao().getSession(sessionId)!!.status,
            )
            assertEquals(1, db.workoutDao().getClustersForSessionExercise(sessionExerciseId).size)
        }

    @Test
    fun `prefill liefert die werte des letzten abgeschlossenen clusters`() =
        runTest {
            val (_, sessionExerciseId) = startSessionWithExercise()
            repository.completeCluster(
                sessionExerciseId,
                SetRole.WORKING,
                listOf(SegmentInput(80_000, 1, 8)),
                null,
            )
            clock.advanceBy(60_000)
            repository.completeCluster(
                sessionExerciseId,
                SetRole.WORKING,
                listOf(SegmentInput(85_000, 1, 6)),
                null,
            )

            val prefill =
                (
                    repository.lastCompletedClusterPrefill(exerciseId)
                        as com.dropsync.core.common.AppResult.Success
                ).value
            assertEquals(listOf(SegmentInput(85_000, 1, 6)), prefill)
        }

    @Test
    fun `ungueltiger multiplikator wird vor der transaktion abgelehnt`() =
        runTest {
            val (_, sessionExerciseId) = startSessionWithExercise()
            val result =
                repository.completeCluster(
                    sessionExerciseId,
                    SetRole.WORKING,
                    listOf(SegmentInput(80_000, 3, 8)),
                    null,
                )
            assertTrue(result is com.dropsync.core.common.AppResult.Failure)
            assertTrue(db.workoutDao().getClustersForSessionExercise(sessionExerciseId).isEmpty())
        }

    @Test
    fun `undo loescht cluster und berechnet prs vollstaendig neu`() =
        runTest {
            // 12.5: Satzabschluss hat eine klar sichtbare Rueckgaengig-Aktion.
            val (_, sessionExerciseId) = startSessionWithExercise()
            repository.completeCluster(
                sessionExerciseId,
                SetRole.WORKING,
                listOf(SegmentInput(80_000, 1, 8)),
                null,
            )
            clock.advanceBy(60_000)
            val secondClusterId =
                (
                    repository.completeCluster(
                        sessionExerciseId,
                        SetRole.WORKING,
                        listOf(SegmentInput(120_000, 1, 3)),
                        null,
                    ) as com.dropsync.core.common.AppResult.Success
                ).value

            val undo = repository.undoCompleteCluster(secondClusterId)
            assertTrue(undo is com.dropsync.core.common.AppResult.Success)

            // Der 120-kg-Rekord ist weg; 80 kg aus Cluster 1 haelt wieder.
            val highestLoad =
                db
                    .workoutDao()
                    .getPersonalRecordsForExercise(exerciseId)
                    .single { it.type == PrType.HIGHEST_LOAD.name }
            assertEquals(80_000L, highestLoad.valueLong)
            assertEquals(1, db.workoutDao().getClustersForSessionExercise(sessionExerciseId).size)
        }

    private suspend fun insertSong(mediaStoreId: Long) {
        db.songDao().upsertAll(
            listOf(
                SongEntity(
                    mediaStoreId = mediaStoreId,
                    contentUri = "content://media/external/audio/media/$mediaStoreId",
                    displayName = "track_$mediaStoreId.mp3",
                    relativePath = "Music/",
                    durationMs = 240_000,
                    sizeBytes = 1_000_000,
                    dateModifiedSeconds = 1_700_000_000,
                    title = "Track $mediaStoreId",
                    artist = null,
                    album = null,
                    isAvailable = true,
                ),
            ),
        )
    }

    @Test
    fun `session referenziert den damals laufenden song per snapshot`() =
        runTest {
            // Abnahme Schritt 11: abgeschlossener Satz kann optional den
            // damals laufenden Song referenzieren (11.1, append-only).
            val (sessionId, sessionExerciseId) = startSessionWithExercise()
            insertSong(mediaStoreId = 42)
            repository.completeCluster(
                sessionExerciseId,
                SetRole.WORKING,
                listOf(SegmentInput(80_000, 1, 8)),
                null,
            )

            val id =
                (
                    repository.recordPlaybackSnapshot(
                        sessionId = sessionId,
                        songId = 42,
                        markerId = null,
                        positionMs = 93_000,
                    ) as com.dropsync.core.common.AppResult.Success
                ).value
            assertTrue(id > 0)

            val snapshots =
                (
                    repository.getPlaybackSnapshots(sessionId)
                        as com.dropsync.core.common.AppResult.Success
                ).value
            assertEquals(1, snapshots.size)
            assertEquals(42L, snapshots.single().songId)
            assertEquals(93_000L, snapshots.single().positionMs)
            assertEquals(null, snapshots.single().markerId)
        }

    @Test
    fun `snapshot ohne bekannte session oder song schlaegt fehl`() =
        runTest {
            val (sessionId, _) = startSessionWithExercise()
            // Unbekannte Session.
            assertTrue(
                repository.recordPlaybackSnapshot(9_999, 42, null, 0)
                    is com.dropsync.core.common.AppResult.Failure,
            )
            // Unbekannter Song verletzt den Fremdschluessel (11.1).
            assertTrue(
                repository.recordPlaybackSnapshot(sessionId, 4_242, null, 0)
                    is com.dropsync.core.common.AppResult.Failure,
            )
            val snapshots =
                (
                    repository.getPlaybackSnapshots(sessionId)
                        as com.dropsync.core.common.AppResult.Success
                ).value
            assertTrue(snapshots.isEmpty())
        }

    @Test
    fun `satzabschluss erfasst laufenden song best effort`() =
        runTest {
            // Schritt 11.1: beim Satzabschluss wird der gerade laufende Song
            // best-effort als Snapshot erfasst.
            val (sessionId, sessionExerciseId) = startSessionWithExercise()
            insertSong(mediaStoreId = 7)
            playback.snapshot =
                PlaybackState(isPlaying = true, currentSongId = 7, positionMs = 111_000)

            val result =
                repository.completeCluster(
                    sessionExerciseId,
                    SetRole.WORKING,
                    listOf(SegmentInput(80_000, 1, 8)),
                    null,
                )
            assertTrue(result is com.dropsync.core.common.AppResult.Success)

            val snapshots =
                (
                    repository.getPlaybackSnapshots(sessionId)
                        as com.dropsync.core.common.AppResult.Success
                ).value
            assertEquals(1, snapshots.size)
            assertEquals(7L, snapshots.single().songId)
            assertEquals(111_000L, snapshots.single().positionMs)
        }

    @Test
    fun `fehlgeschlagene snapshot erfassung laesst den satz nie fehlschlagen`() =
        runTest {
            // Unbekannter Song verletzt den FK; der Fehler wird geschluckt.
            val (sessionId, sessionExerciseId) = startSessionWithExercise()
            playback.snapshot = PlaybackState(isPlaying = true, currentSongId = 9_999)

            val result =
                repository.completeCluster(
                    sessionExerciseId,
                    SetRole.WORKING,
                    listOf(SegmentInput(80_000, 1, 8)),
                    null,
                )
            assertTrue(result is com.dropsync.core.common.AppResult.Success)

            val snapshots =
                (
                    repository.getPlaybackSnapshots(sessionId)
                        as com.dropsync.core.common.AppResult.Success
                ).value
            assertTrue(snapshots.isEmpty())
        }

    @Test
    fun `restpref roundtrip pro uebung`() =
        runTest {
            // Abschnitt 8: Restdauer und Modus werden pro Uebung gemerkt.
            val empty = (repository.getRestPref(exerciseId) as com.dropsync.core.common.AppResult.Success).value
            assertEquals(null, empty)

            repository.setRestPref(exerciseId, 120, RestMode.DROPSYNC)
            assertEquals(
                RestPref(120, RestMode.DROPSYNC),
                (repository.getRestPref(exerciseId) as com.dropsync.core.common.AppResult.Success).value,
            )

            // Upsert ueberschreibt die bestehende Praeferenz.
            repository.setRestPref(exerciseId, 60, RestMode.NORMAL)
            assertEquals(
                RestPref(60, RestMode.NORMAL),
                (repository.getRestPref(exerciseId) as com.dropsync.core.common.AppResult.Success).value,
            )
        }

    private suspend fun insertSecondExercise(): Long =
        db.exerciseDao().insertExerciseIgnoring(
            ExerciseEntity(
                canonicalName = "barbell_bench_press",
                kind = "STRENGTH",
                equipment = "BARBELL",
                isCustom = false,
                isArchived = false,
            ),
        )

    @Test
    fun `swap keep behaelt saetze und haengt neue uebung an`() =
        runTest {
            val secondExerciseId = insertSecondExercise()
            val (sessionId, sessionExerciseId) = startSessionWithExercise()
            repository.completeCluster(
                sessionExerciseId,
                SetRole.WORKING,
                listOf(SegmentInput(100_000, 1, 5)),
                null,
            )

            val result =
                repository.swapSessionExercise(sessionExerciseId, secondExerciseId, SwapStrategy.KEEP)
            assertTrue(result is com.dropsync.core.common.AppResult.Success)

            val rows = db.workoutDao().getSessionExercisesRaw(sessionId)
            assertEquals(listOf(exerciseId, secondExerciseId), rows.map { it.exerciseId })
            // Historie der alten Uebung bleibt unveraendert (9.5).
            assertEquals(1, db.workoutDao().getClustersForSessionExercise(sessionExerciseId).size)
            assertFalse(db.workoutDao().getPersonalRecordsForExercise(exerciseId).isEmpty())
            assertTrue(db.workoutDao().getPersonalRecordsForExercise(secondExerciseId).isEmpty())
        }

    @Test
    fun `swap move ordnet saetze der neuen uebung zu und berechnet prs neu`() =
        runTest {
            val secondExerciseId = insertSecondExercise()
            val (_, sessionExerciseId) = startSessionWithExercise()
            repository.completeCluster(
                sessionExerciseId,
                SetRole.WORKING,
                listOf(SegmentInput(100_000, 1, 5)),
                null,
            )

            val result =
                repository.swapSessionExercise(sessionExerciseId, secondExerciseId, SwapStrategy.MOVE)
            assertTrue(result is com.dropsync.core.common.AppResult.Success)

            assertEquals(
                secondExerciseId,
                db.workoutDao().getSessionExercise(sessionExerciseId)!!.exerciseId,
            )
            // PRs wandern mit: alte Uebung leer, neue haelt 100 kg (10.4).
            assertTrue(db.workoutDao().getPersonalRecordsForExercise(exerciseId).isEmpty())
            val loadPr =
                db
                    .workoutDao()
                    .getPersonalRecordsForExercise(secondExerciseId)
                    .single { it.type == PrType.HIGHEST_LOAD.name }
            assertEquals(100_000L, loadPr.valueLong)
        }

    @Test
    fun `swap discard verwirft saetze dieser session`() =
        runTest {
            val secondExerciseId = insertSecondExercise()
            val (_, sessionExerciseId) = startSessionWithExercise()
            repository.completeCluster(
                sessionExerciseId,
                SetRole.WORKING,
                listOf(SegmentInput(100_000, 1, 5)),
                null,
            )

            val result =
                repository.swapSessionExercise(sessionExerciseId, secondExerciseId, SwapStrategy.DISCARD)
            assertTrue(result is com.dropsync.core.common.AppResult.Success)

            assertEquals(
                secondExerciseId,
                db.workoutDao().getSessionExercise(sessionExerciseId)!!.exerciseId,
            )
            assertTrue(db.workoutDao().getClustersForSessionExercise(sessionExerciseId).isEmpty())
            assertTrue(db.workoutDao().getPersonalRecordsForExercise(exerciseId).isEmpty())
            assertTrue(db.workoutDao().getPersonalRecordsForExercise(secondExerciseId).isEmpty())
        }

    @Test
    fun `repeat last session kopiert die uebungen der letzten session`() =
        runTest {
            val (sessionId, sessionExerciseId) = startSessionWithExercise()
            repository.completeCluster(
                sessionExerciseId,
                SetRole.WORKING,
                listOf(SegmentInput(100_000, 1, 5)),
                null,
            )
            repository.completeSession(sessionId)
            clock.advanceBy(86_400_000)

            val newSessionId =
                (repository.repeatLastSession() as com.dropsync.core.common.AppResult.Success).value
            assertTrue(newSessionId != sessionId)
            assertEquals(
                SessionStatus.ACTIVE.name,
                db.workoutDao().getSession(newSessionId)!!.status,
            )
            val rows = db.workoutDao().getSessionExercisesRaw(newSessionId)
            assertEquals(listOf(exerciseId), rows.map { it.exerciseId })
            // Nur die Struktur wird kopiert, keine Saetze (9.6).
            assertTrue(db.workoutDao().getClustersForSessionExercise(rows.single().id).isEmpty())
        }

    @Test
    fun `routine aus session uebernimmt saetze und restpref`() =
        runTest {
            val (sessionId, sessionExerciseId) = startSessionWithExercise()
            repository.completeCluster(
                sessionExerciseId,
                SetRole.WORKING,
                listOf(SegmentInput(100_000, 1, 5)),
                null,
            )
            clock.advanceBy(60_000)
            repository.completeCluster(
                sessionExerciseId,
                SetRole.WORKING,
                listOf(SegmentInput(100_000, 1, 5)),
                null,
            )
            repository.setRestPref(exerciseId, 120, RestMode.NORMAL)
            repository.completeSession(sessionId)

            val routineId =
                (
                    repository.createRoutineFromSession(sessionId, "Push Day")
                        as com.dropsync.core.common.AppResult.Success
                ).value
            val detail =
                (
                    repository.getRoutineDetail(routineId, "en")
                        as com.dropsync.core.common.AppResult.Success
                ).value

            assertEquals("Push Day", detail.name)
            val entry = detail.exercises.single()
            assertEquals(exerciseId, entry.exerciseId)
            // targetSets = Anzahl abgeschlossener Arbeitscluster (9.7).
            assertEquals(2, entry.targetSets)
            assertEquals(120, entry.restSeconds)
        }
}
