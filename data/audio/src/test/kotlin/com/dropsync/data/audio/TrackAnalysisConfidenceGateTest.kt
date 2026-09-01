package com.dropsync.data.audio

import androidx.test.core.app.ApplicationProvider
import com.dropsync.core.database.dao.TrackAnalysisDao
import com.dropsync.core.database.entity.TrackAnalysisEntity
import com.dropsync.domain.audio.MixConfidence
import com.dropsync.domain.audio.WaveformCodec
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
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
            context = ApplicationProvider.getApplicationContext(),
            trackAnalysisDao = dao,
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

    private fun entity(
        bpm: Float? = null,
        bpmConfidence: Float? = null,
        camelotKey: String? = null,
        keyConfidence: Float? = null,
    ) = TrackAnalysisEntity(
        songId = SONG_ID,
        waveformData = byteArrayOf(-10, 10, -20, 20),
        bucketCount = 2,
        analyzerVersion = WaveformCodec.ANALYZER_VERSION,
        mixAnalyzerVersion = WaveformCodec.MIX_ANALYZER_VERSION,
        analyzedAtEpochMs = 1_000L,
        peakLinear = 0.8,
        bpm = bpm,
        bpmConfidence = bpmConfidence,
        camelotKey = camelotKey,
        keyConfidence = keyConfidence,
    )

    private companion object {
        const val SONG_ID = 42L
    }
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
