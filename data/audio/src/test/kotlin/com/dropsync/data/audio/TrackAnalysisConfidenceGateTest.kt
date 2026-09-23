package com.dropsync.data.audio

import androidx.test.core.app.ApplicationProvider
import com.dropsync.core.common.AppResult
import com.dropsync.core.common.Clock
import com.dropsync.core.database.dao.TrackAnalysisDao
import com.dropsync.core.database.entity.TrackAnalysisEntity
import com.dropsync.core.model.Song
import com.dropsync.domain.audio.AnalysisProfile
import com.dropsync.domain.audio.DownbeatConfidence
import com.dropsync.domain.audio.MixConfidence
import com.dropsync.domain.audio.TrackAnalysis
import com.dropsync.domain.audio.TrackAnalyzer
import com.dropsync.domain.audio.WaveformCodec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Verifikation des Konfidenz-Gates an der Leseseite: unsichere BPM/Key
 * erreichen die Features nie, die Rohwerte bleiben aber in der DB.
 */
@RunWith(RobolectricTestRunner::class)
class TrackAnalysisConfidenceGateTest {
    private val dao = FakeTrackAnalysisDao()
    private val repository =
        TrackAnalysisRepositoryImpl(
            trackAnalysisDao = dao,
            analyzer = NoopTrackAnalyzer,
            persister = TrackAnalysisPersister(dao = dao, clock = FixedClock),
            scheduler = NoopScheduler,
            scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher()),
        )

    @Test
    fun `sichere werte kommen durch`() =
        runTest {
            dao.emit(
                entity(
                    bpm = 128f,
                    bpmConfidence = 0.9f,
                    camelotKey = "8A",
                    keyConfidence = 0.9f,
                ),
            )

            val analysis = requireNotNull(repository.observeAnalysis(SONG_ID).first())

            assertEquals(128f, analysis.bpm)
            assertEquals("8A", analysis.camelotKey)
        }

    @Test
    fun `unsicheres bpm wird verworfen der key bleibt`() =
        runTest {
            // Getrennte Schwellen: ein untanzbarer Track kann trotzdem eine
            // klare Tonart haben. Das Gate darf nicht beides gemeinsam
            // wegwerfen.
            dao.emit(
                entity(
                    bpm = 77f,
                    bpmConfidence = 0.18f,
                    camelotKey = "8A",
                    keyConfidence = 0.9f,
                ),
            )

            val analysis = requireNotNull(repository.observeAnalysis(SONG_ID).first())

            assertNull(analysis.bpm)
            assertEquals("8A", analysis.camelotKey)
        }

    @Test
    fun `unsicherer key wird verworfen das bpm bleibt`() =
        runTest {
            dao.emit(
                entity(
                    bpm = 128f,
                    bpmConfidence = 0.9f,
                    camelotKey = "12B",
                    keyConfidence = 0.5f,
                ),
            )

            val analysis = requireNotNull(repository.observeAnalysis(SONG_ID).first())

            assertEquals(128f, analysis.bpm)
            assertNull(analysis.camelotKey)
        }

    @Test
    fun `rohkonfidenz bleibt sichtbar auch wenn der wert gefiltert wird`() =
        runTest {
            // Die Konfidenz selbst wird NICHT gefiltert - sonst koennte die
            // UI nie erklaeren, warum kein BPM da ist.
            dao.emit(entity(bpm = 77f, bpmConfidence = 0.18f))

            val analysis = requireNotNull(repository.observeAnalysis(SONG_ID).first())

            assertNull(analysis.bpm)
            assertEquals(0.18f, analysis.bpmConfidence)
        }

    @Test
    fun `alte zeile ohne konfidenz gilt als unsicher`() =
        runTest {
            // DB v7 kannte bpm_confidence nicht; die Spalte ist dort null.
            // Solche Werte sind nicht vertrauenswuerdig, weil sie aus einem
            // Lauf ohne Konfidenzmessung stammen.
            dao.emit(
                entity(
                    bpm = 128f,
                    bpmConfidence = null,
                    camelotKey = "8A",
                    keyConfidence = null,
                ),
            )

            val analysis = requireNotNull(repository.observeAnalysis(SONG_ID).first())

            assertNull(analysis.bpm)
            assertNull(analysis.camelotKey)
        }

    @Test
    fun `alte mix-version bleibt unsichtbar die waveform aber erhalten`() =
        runTest {
            dao.emit(
                entity(
                    bpm = 128f,
                    bpmConfidence = 0.9f,
                    camelotKey = "8A",
                    keyConfidence = 0.9f,
                ).copy(mixAnalyzerVersion = 0),
            )

            val analysis = requireNotNull(repository.observeAnalysis(SONG_ID).first())

            assertNull(analysis.bpm)
            assertNull(analysis.camelotKey)
            assertEquals(2, analysis.waveformBuckets.size)
        }

    @Test
    fun `waveform bleibt unabhaengig vom konfidenz-gate erhalten`() =
        runTest {
            dao.emit(entity(bpm = 77f, bpmConfidence = 0.1f))

            val analysis = requireNotNull(repository.observeAnalysis(SONG_ID).first())

            assertNotNull(analysis.waveformBuckets)
            assertEquals(2, analysis.waveformBuckets.size)
        }

    @Test
    fun `gate benutzt dieselben schwellen wie die domaene`() =
        runTest {
            // Knapp unter der Schwelle: faellt. Genau auf der Schwelle: haelt.
            dao.emit(entity(bpm = 128f, bpmConfidence = MixConfidence.MIN_BPM_CONFIDENCE))
            assertEquals(
                128f,
                requireNotNull(repository.observeAnalysis(SONG_ID).first()).bpm,
            )

            dao.emit(
                entity(bpm = 128f, bpmConfidence = MixConfidence.MIN_BPM_CONFIDENCE - 0.01f),
            )
            assertNull(requireNotNull(repository.observeAnalysis(SONG_ID).first()).bpm)
        }

    @Test
    fun `unsicherer raster-offset faellt weg der rohwert bleibt`() =
        runTest {
            // B4: Das Snap-Gate sitzt an der Leseseite; genau auf der
            // Schwelle haelt der Offset, knapp darunter faellt er.
            dao.emit(
                entity(
                    downbeatOffsetMs = 137L,
                    downbeatConfidence = DownbeatConfidence.MIN_SNAP_CONFIDENCE,
                ),
            )
            assertEquals(
                137L,
                requireNotNull(repository.observeAnalysis(SONG_ID).first()).downbeatOffsetMs,
            )

            dao.emit(
                entity(
                    downbeatOffsetMs = 137L,
                    downbeatConfidence = DownbeatConfidence.MIN_SNAP_CONFIDENCE - 0.01f,
                ),
            )
            val gated = requireNotNull(repository.observeAnalysis(SONG_ID).first())
            assertNull(gated.downbeatOffsetMs)
            // Die Rohkonfidenz bleibt sichtbar (spaetere Kalibrierung
            // braucht keine Neuanalyse).
            assertNotNull(gated.downbeatConfidence)
        }

    @Test
    fun `veraltete mix-version verbirgt auch den raster-offset`() =
        runTest {
            dao.emit(
                entity(
                    downbeatOffsetMs = 137L,
                    downbeatConfidence = 0.9f,
                    mixAnalyzerVersion = WaveformCodec.MIX_ANALYZER_VERSION - 1,
                ),
            )

            assertNull(requireNotNull(repository.observeAnalysis(SONG_ID).first()).downbeatOffsetMs)
        }

    private fun entity(
        bpm: Float? = null,
        bpmConfidence: Float? = null,
        camelotKey: String? = null,
        keyConfidence: Float? = null,
        downbeatOffsetMs: Long? = null,
        downbeatConfidence: Float? = null,
        mixAnalyzerVersion: Int = WaveformCodec.MIX_ANALYZER_VERSION,
    ) = TrackAnalysisEntity(
        songId = SONG_ID,
        waveformData = byteArrayOf(-10, 10, -20, 20),
        bucketCount = 2,
        analyzerVersion = WaveformCodec.ANALYZER_VERSION,
        mixAnalyzerVersion = mixAnalyzerVersion,
        analyzedAtEpochMs = 1_000L,
        peakLinear = 0.8,
        bpm = bpm,
        bpmConfidence = bpmConfidence,
        camelotKey = camelotKey,
        keyConfidence = keyConfidence,
        downbeatOffsetMs = downbeatOffsetMs,
        downbeatConfidence = downbeatConfidence,
    )

    private companion object {
        const val SONG_ID = 42L
    }
}

/** Wird in diesem Test nie aufgerufen: geprueft wird nur die Leseseite. */
private object NoopTrackAnalyzer : TrackAnalyzer {
    override suspend fun analyze(
        song: Song,
        profile: AnalysisProfile,
    ): AppResult<TrackAnalysis> = error("analyze() darf hier nicht laufen")
}

private object FixedClock : Clock {
    override fun elapsedRealtimeMs(): Long = 1_000L

    override fun epochMillis(): Long = 1_000L
}

/** Dieser Test prueft nur die Leseseite; nichts wird eingeplant. */
private object NoopScheduler : DeferredAnalysisScheduler {
    override fun scheduleMixMetadata(songId: Long) = Unit

    override fun scheduleFullAnalysis(songId: Long) = Unit

    override fun schedulePrewarmWaveform(songId: Long) = Unit

    override fun scheduleOnsetDetection(songId: Long) = Unit
}

private class FakeTrackAnalysisDao : TrackAnalysisDao {
    private val state = MutableStateFlow<TrackAnalysisEntity?>(null)

    fun emit(entity: TrackAnalysisEntity?) {
        state.value = entity
    }

    override suspend fun upsert(entity: TrackAnalysisEntity) {
        state.value = entity
    }

    override suspend fun updateMixMetadata(
        songId: Long,
        bpm: Float?,
        bpmConfidence: Float?,
        camelotKey: String?,
        keyConfidence: Float?,
        integratedLufs: Float?,
        truePeakDb: Float?,
        downbeatOffsetMs: Long?,
        downbeatConfidence: Float?,
        mixAnalyzerVersion: Int,
        analyzedAtEpochMs: Long,
    ): Int {
        val current = state.value?.takeIf { it.songId == songId } ?: return 0
        state.value =
            current.copy(
                bpm = bpm,
                bpmConfidence = bpmConfidence,
                camelotKey = camelotKey,
                keyConfidence = keyConfidence,
                integratedLufs = integratedLufs,
                truePeakDb = truePeakDb,
                downbeatOffsetMs = downbeatOffsetMs,
                downbeatConfidence = downbeatConfidence,
                mixAnalyzerVersion = mixAnalyzerVersion,
                analyzedAtEpochMs = analyzedAtEpochMs,
            )
        return 1
    }

    override suspend fun getBySongId(songId: Long): TrackAnalysisEntity? = state.value?.takeIf { it.songId == songId }

    override suspend fun getBySongIds(songIds: List<Long>): List<TrackAnalysisEntity> =
        listOfNotNull(state.value).filter { it.songId in songIds }

    override fun observeBySongId(songId: Long): Flow<TrackAnalysisEntity?> = state

    override suspend fun deleteOlderThanVersion(minVersion: Int) {
        if ((state.value?.analyzerVersion ?: Int.MAX_VALUE) < minVersion) {
            state.value = null
        }
    }
}
