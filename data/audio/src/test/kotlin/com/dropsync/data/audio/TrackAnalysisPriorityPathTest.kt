package com.dropsync.data.audio

import androidx.test.core.app.ApplicationProvider
import com.dropsync.core.common.AppError
import com.dropsync.core.common.AppResult
import com.dropsync.core.common.Clock
import com.dropsync.core.database.dao.TrackAnalysisDao
import com.dropsync.core.database.entity.TrackAnalysisEntity
import com.dropsync.core.model.Song
import com.dropsync.domain.audio.AnalysisProfile
import com.dropsync.domain.audio.TrackAnalysis
import com.dropsync.domain.audio.TrackAnalyzer
import com.dropsync.domain.audio.WaveformBucket
import com.dropsync.domain.audio.WaveformCodec
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Verifikation des In-Process-Prioritaetspfads (Umbauplan Phase 3):
 * Dedup, Cancel-und-Ueberholen, korrekte Profilwahl und die
 * Fehlerbehandlung, die einen sichtbaren Cache nicht zerstoert.
 *
 * WorkManager wird hier nicht geprueft — die Metadatenstufe laeuft dort
 * und Robolectric hat keinen initialisierten WorkManager. Alle Faelle
 * unten enden deshalb bewusst VOR dem `enqueueMixMetadata`, indem die
 * Metadaten bereits als aktuell gelten.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class TrackAnalysisPriorityPathTest {
    private val dao = FakePriorityDao()
    private val analyzer = ControllableAnalyzer()
    private val scheduler = RecordingScheduler()

    private fun repository(scope: CoroutineScope) =
        TrackAnalysisRepositoryImpl(
            trackAnalysisDao = dao,
            analyzer = analyzer,
            persister = TrackAnalysisPersister(dao = dao, clock = FixedPriorityClock),
            scheduler = scheduler,
            scope = scope,
        )

    @Test
    fun `cache-miss startet stufe eins in-process und schreibt die waveform`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val repo = repository(CoroutineScope(SupervisorJob() + dispatcher))

            repo.requestAnalysis(song(1L))
            // Erst laufen lassen, damit analyze() sein Gate registriert -
            // ein completeAll() davor wuerde ins Leere greifen und der Lauf
            // haengt fuer immer.
            advanceUntilIdle()
            analyzer.completeAll()
            advanceUntilIdle()

            assertEquals(listOf(1L to AnalysisProfile.WAVEFORM_ONLY), analyzer.calls)
            val stored = requireNotNull(dao.getBySongId(1L))
            assertEquals(WaveformCodec.ANALYZER_VERSION, stored.analyzerVersion)
            assertEquals(2, stored.bucketCount)
            // Stufe 2 wird erst NACH dem Waveform-Write eingeplant.
            assertEquals(listOf(1L), scheduler.mixScheduled)
        }

    @Test
    fun `zweiter aufruf zum selben song startet keinen zweiten lauf`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val repo = repository(CoroutineScope(SupervisorJob() + dispatcher))

            repo.requestAnalysis(song(1L))
            advanceUntilIdle() // Lauf haengt im Analyzer, noch nicht fertig.
            repo.requestAnalysis(song(1L))
            advanceUntilIdle()

            assertEquals(1, analyzer.calls.size)

            analyzer.completeAll()
            advanceUntilIdle()
        }

    @Test
    fun `titelwechsel bricht den ueberholten lauf ab`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val repo = repository(CoroutineScope(SupervisorJob() + dispatcher))

            repo.requestAnalysis(song(1L))
            advanceUntilIdle()
            repo.requestAnalysis(song(2L))
            advanceUntilIdle()

            // Beide wurden gestartet, aber Song 1 ist abgebrochen.
            assertEquals(
                listOf(1L to AnalysisProfile.WAVEFORM_ONLY, 2L to AnalysisProfile.WAVEFORM_ONLY),
                analyzer.calls,
            )
            assertTrue("Lauf zu Song 1 muss abgebrochen sein", analyzer.wasCancelled(1L))

            analyzer.completeAll()
            advanceUntilIdle()

            // Der abgebrochene Lauf hinterlaesst KEINEN Cache-Eintrag.
            assertNull(dao.getBySongId(1L))
            assertNotNull(dao.getBySongId(2L))
            // Und er darf auch keine Stufe 2 eingeplant haben.
            assertEquals(listOf(2L), scheduler.mixScheduled)
        }

    @Test
    fun `cache-hit startet nichts und beendet ueberholte laeufe`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val repo = repository(CoroutineScope(SupervisorJob() + dispatcher))

            repo.requestAnalysis(song(1L))
            advanceUntilIdle()

            // Song 2 ist vollstaendig gecacht: kein Lauf, aber Song 1 ist
            // ueberholt, weil der Nutzer jetzt Song 2 ansieht.
            dao.put(currentEntity(songId = 2L))
            repo.requestAnalysis(song(2L))
            advanceUntilIdle()

            assertEquals(1, analyzer.calls.size)
            assertTrue(analyzer.wasCancelled(1L))

            analyzer.completeAll()
            advanceUntilIdle()
        }

    @Test
    fun `dauerhafter fehler wird gecacht damit die ui zurueckfaellt`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val repo = repository(CoroutineScope(SupervisorJob() + dispatcher))
            analyzer.failWith = AppError.MediaUnavailable(mediaStoreId = 1L)

            repo.requestAnalysis(song(1L))
            advanceUntilIdle()
            analyzer.completeAll()
            advanceUntilIdle()

            val stored = requireNotNull(dao.getBySongId(1L))
            assertEquals(0, stored.bucketCount)
            assertEquals(WaveformCodec.ANALYZER_VERSION, stored.analyzerVersion)
        }

    @Test
    fun `voruebergehender fehler hinterlaesst keinen cache-eintrag`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val repo = repository(CoroutineScope(SupervisorJob() + dispatcher))
            analyzer.failWith = AppError.Unknown(debugMessage = "Datei kurz gesperrt")

            repo.requestAnalysis(song(1L))
            advanceUntilIdle()
            analyzer.completeAll()
            advanceUntilIdle()

            // Kein Eintrag: der naechste Aufruf darf es erneut versuchen.
            assertNull(dao.getBySongId(1L))
        }

    @Test
    fun `dauerhafter fehler der stufe eins behaelt vorhandene metadaten`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val repo = repository(CoroutineScope(SupervisorJob() + dispatcher))
            // Alte Waveform-Version, aber gueltige Mix-Metadaten.
            dao.put(
                currentEntity(songId = 1L).copy(
                    analyzerVersion = WaveformCodec.ANALYZER_VERSION - 1,
                    bucketCount = 2,
                    bpm = 128f,
                    bpmConfidence = 0.9f,
                ),
            )
            analyzer.failWith = AppError.MediaUnavailable(mediaStoreId = 1L)

            repo.requestAnalysis(song(1L))
            advanceUntilIdle()
            analyzer.completeAll()
            advanceUntilIdle()

            val stored = requireNotNull(dao.getBySongId(1L))
            assertEquals(0, stored.bucketCount)
            assertEquals(0, stored.waveformData.size)
            assertEquals(128f, stored.bpm)
            assertEquals(WaveformCodec.MIX_ANALYZER_VERSION, stored.mixAnalyzerVersion)
        }

    @Test
    fun `prewarm plant nur fehlende waveforms und nie mix-metadaten`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val repo = repository(CoroutineScope(SupervisorJob() + dispatcher))
            // Song 2 ist bereits vorbereitet, Song 3 nicht.
            dao.put(currentEntity(songId = 2L))

            repo.requestAnalysisPrewarm(listOf(song(2L), song(3L)))
            advanceUntilIdle()

            assertEquals(listOf(3L), scheduler.prewarmScheduled)
            // Prewarming bereitet die Anzeige vor, nicht die Bibliothek:
            // kein Metadatenlauf fuer Werte, die noch niemand sehen will.
            assertTrue(scheduler.mixScheduled.isEmpty())
            // Und nichts davon laeuft in-process.
            assertTrue(analyzer.calls.isEmpty())
        }

    @Test
    fun `prewarm respektiert das limit`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val repo = repository(CoroutineScope(SupervisorJob() + dispatcher))

            repo.requestAnalysisPrewarm(listOf(song(5L), song(6L), song(7L)), limit = 2)
            advanceUntilIdle()

            assertEquals(listOf(5L, 6L), scheduler.prewarmScheduled)
        }

    @Test
    fun `prewarm mit limit null plant nichts`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val repo = repository(CoroutineScope(SupervisorJob() + dispatcher))

            repo.requestAnalysisPrewarm(listOf(song(5L)), limit = 0)
            advanceUntilIdle()

            assertTrue(scheduler.prewarmScheduled.isEmpty())
        }

    private fun song(id: Long) =
        Song(
            mediaStoreId = id,
            contentUri = "content://media/external/audio/media/$id",
            displayName = "song-$id.mp3",
            relativePath = "Music/",
            durationMs = 240_000L,
            sizeBytes = 5_000_000L,
            dateModifiedSeconds = 1_000L,
            title = "Song $id",
            artist = null,
            album = null,
            genre = null,
            isAvailable = true,
        )

    private fun currentEntity(songId: Long) =
        TrackAnalysisEntity(
            songId = songId,
            waveformData = byteArrayOf(-10, 10, -20, 20),
            bucketCount = 2,
            analyzerVersion = WaveformCodec.ANALYZER_VERSION,
            mixAnalyzerVersion = WaveformCodec.MIX_ANALYZER_VERSION,
            analyzedAtEpochMs = 1_000L,
            peakLinear = 0.8,
        )
}

/**
 * Analyzer, der auf Kommando fertig wird. Nur so laesst sich pruefen, was
 * passiert, WAEHREND ein Lauf noch offen ist — genau dort sitzt die
 * Dedup-/Abbruchlogik.
 */
private class ControllableAnalyzer : TrackAnalyzer {
    val calls = mutableListOf<Pair<Long, AnalysisProfile>>()
    var failWith: AppError? = null

    private val gates = mutableMapOf<Long, CompletableDeferred<Unit>>()
    private val cancelled = mutableSetOf<Long>()

    fun completeAll() {
        gates.values.forEach { it.complete(Unit) }
    }

    fun wasCancelled(songId: Long): Boolean = songId in cancelled

    override suspend fun analyze(
        song: Song,
        profile: AnalysisProfile,
    ): AppResult<TrackAnalysis> {
        calls += song.mediaStoreId to profile
        val gate = CompletableDeferred<Unit>()
        gates[song.mediaStoreId] = gate
        try {
            gate.await()
        } catch (cancellation: kotlinx.coroutines.CancellationException) {
            cancelled += song.mediaStoreId
            throw cancellation
        }
        // Nach dem Gate erneut auf Abbruch pruefen: ein ueberholter Lauf
        // darf nichts mehr schreiben.
        return withContext(kotlinx.coroutines.NonCancellable) {
            failWith?.let { return@withContext AppResult.failure(it) }
            AppResult.success(
                TrackAnalysis(
                    waveformBuckets = listOf(WaveformBucket(-10, 10), WaveformBucket(-20, 20)),
                    onsetCandidatesMs = emptyList(),
                    peakLinear = 0.8,
                ),
            )
        }
    }
}

private class FakePriorityDao : TrackAnalysisDao {
    private val rows = MutableStateFlow<Map<Long, TrackAnalysisEntity>>(emptyMap())

    fun put(entity: TrackAnalysisEntity) {
        rows.value = rows.value + (entity.songId to entity)
    }

    override suspend fun upsert(entity: TrackAnalysisEntity) = put(entity)

    override suspend fun updateMixMetadata(
        songId: Long,
        bpm: Float?,
        bpmConfidence: Float?,
        camelotKey: String?,
        keyConfidence: Float?,
        integratedLufs: Float?,
        truePeakDb: Float?,
        mixAnalyzerVersion: Int,
        analyzedAtEpochMs: Long,
    ): Int {
        val current = rows.value[songId] ?: return 0
        put(
            current.copy(
                bpm = bpm,
                bpmConfidence = bpmConfidence,
                camelotKey = camelotKey,
                keyConfidence = keyConfidence,
                integratedLufs = integratedLufs,
                truePeakDb = truePeakDb,
                mixAnalyzerVersion = mixAnalyzerVersion,
                analyzedAtEpochMs = analyzedAtEpochMs,
            ),
        )
        return 1
    }

    override suspend fun getBySongId(songId: Long): TrackAnalysisEntity? = rows.value[songId]

    override suspend fun getBySongIds(songIds: List<Long>): List<TrackAnalysisEntity> =
        songIds.mapNotNull { rows.value[it] }

    override fun observeBySongId(songId: Long): Flow<TrackAnalysisEntity?> = rows.map { it[songId] }

    override suspend fun deleteOlderThanVersion(minVersion: Int) {
        rows.value = rows.value.filterValues { it.analyzerVersion >= minVersion }
    }
}

private object FixedPriorityClock : Clock {
    override fun elapsedRealtimeMs(): Long = 1_000L

    override fun epochMillis(): Long = 1_000L
}

/** Haelt fest, was in die aufschiebbare Lane gegeben wurde. */
private class RecordingScheduler : DeferredAnalysisScheduler {
    val mixScheduled = mutableListOf<Long>()
    val chainScheduled = mutableListOf<Pair<Long, Boolean>>()
    val prewarmScheduled = mutableListOf<Long>()
    val onsetScheduled = mutableListOf<Long>()

    override fun scheduleMixMetadata(songId: Long) {
        mixScheduled += songId
    }

    override fun scheduleWaveformThenMix(
        songId: Long,
        alsoNeedsMix: Boolean,
    ) {
        chainScheduled += songId to alsoNeedsMix
    }

    override fun schedulePrewarmWaveform(songId: Long) {
        prewarmScheduled += songId
    }

    override fun scheduleOnsetDetection(songId: Long) {
        onsetScheduled += songId
    }
}
