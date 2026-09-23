package com.dropsync.data.playback

import android.media.AudioFormat
import android.media.AudioTrack
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dropsync.domain.playback.AudioTimestamp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Durchreichung des echten `AudioTrack.getTimestamp()`-Readers: Der Reader
 * uebernimmt `(systemTimeNs, framePosition)` unveraendert in das
 * Domain-Paar und reicht die Abspielkopf-Position durch. Das Warm-up-Gate
 * (getTimestamp() == false, erste Sekunden nach play()) ist unter
 * Robolectric nicht simulierbar — der Schatten liefert `true` mit
 * Nullwerten, deshalb dokumentiert der Test genau diese Durchreichung.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class AudioTrackTimestampReaderTest {
    private fun track(): AudioTrack =
        AudioTrack
            .Builder()
            .setAudioFormat(
                AudioFormat
                    .Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(48_000)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build(),
            ).setBufferSizeInBytes(4_096)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

    @Test
    fun `Timestamp des Tracks wird als Domain-Paar durchgereicht`() {
        val reader = AudioTrackTimestampReader(track())
        assertTrue(reader.isTimestampValid())
        assertEquals(
            AudioTimestamp(systemTimeNs = 0L, framePosition = 0L),
            reader.readTimestamp(),
        )
    }

    @Test
    fun `playbackHeadPosition reicht die Position des Tracks durch`() {
        val track = track()
        val reader = AudioTrackTimestampReader(track)
        assertEquals(track.playbackHeadPosition.toLong(), reader.playbackHeadPosition())
    }
}
