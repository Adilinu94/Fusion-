package com.dropsync.feature.library

import com.dropsync.domain.audio.WaveformBucket
import com.dropsync.domain.playback.PlaybackState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Phase 8: Library-Waveform-Grundlagen - Fortschritt des laufenden
 * Titels und Bucket-Normalisierung, pur und ohne Android.
 */
class LibraryWaveformMathTest {
    @Test
    fun `fortschritt ohne laufenden titel ist null`() {
        assertNull(progressFromState(PlaybackState()))
    }

    @Test
    fun `fortschritt klemmt auf null bis eins`() {
        val progress =
            progressFromState(
                PlaybackState(
                    currentSongId = 7L,
                    positionMs = 30_000L,
                    durationMs = 60_000L,
                ),
            )
        assertEquals(CurrentProgress(7L, 0.5f), progress)
        assertEquals(
            CurrentProgress(7L, 1f),
            progressFromState(
                PlaybackState(currentSongId = 7L, positionMs = 99_000L, durationMs = 60_000L),
            ),
        )
    }

    @Test
    fun `fortschritt bei unbekannter dauer ist null`() {
        assertEquals(
            CurrentProgress(7L, 0f),
            progressFromState(PlaybackState(currentSongId = 7L, positionMs = 100L)),
        )
    }

    @Test
    fun `buckets werden auf minus eins bis eins normalisiert`() {
        val normalized =
            normalizeBuckets(
                listOf(
                    WaveformBucket(min = (-127).toByte(), max = 127.toByte()),
                    WaveformBucket(min = 0, max = 64),
                ),
            )
        assertEquals(listOf(-1f to 1f, 0f to 64f / 127f), normalized)
    }

    @Test
    fun `leere buckets liefern null`() {
        assertNull(normalizeBuckets(emptyList()))
    }
}
