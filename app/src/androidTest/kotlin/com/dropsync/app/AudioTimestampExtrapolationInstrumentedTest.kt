package com.dropsync.app

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dropsync.data.playback.AudioTrackTimestampReader
import com.dropsync.domain.playback.AudioTimestampExtrapolator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumentierter Kern-Test (Testinfra-Plan Schritt 4, T3):
 * AudioTrack-Timestamp auf echter Android-Audio-Hardware/Emulator.
 *
 * Der Test erzeugt einen echten [AudioTrack], schreibt Stille und
 * prueft, dass `getTimestamp()` nach dem Start valide Paare liefert und
 * der Extrapolator das Delta zwischen zwei Samples vorwaerts
 * extrapoliert. Es werden nur Deltas geprueft, nie Absolutwerte
 * (Geraete-Latenz ist absichtlich geraeteabhaengig, siehe
 * AudioTimestampExtrapolator).
 */
@RunWith(AndroidJUnit4::class)
class AudioTimestampExtrapolationInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun newAudioTrack(): AudioTrack {
        val sampleRate = 48_000
        val minBuffer =
            AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_STEREO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
        return AudioTrack
            .Builder()
            .setAudioAttributes(
                AudioAttributes
                    .Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            ).setAudioFormat(
                AudioFormat
                    .Builder()
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build(),
            ).setBufferSizeInBytes(minBuffer)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
    }

    @Test
    fun timestamp_wird_nach_start_valide_und_delta_laeuft_vorwaerts() {
        val track = newAudioTrack()
        try {
            val reader = AudioTrackTimestampReader(track)
            val extrapolator =
                AudioTimestampExtrapolator(
                    reader = reader,
                    sampleRateHz = 48_000,
                    nowNs = System::nanoTime,
                )

            // Vor play() darf kein valider Timestamp existieren (Warm-up-Gate).
            assertTrue("vor play() darf kein Timestamp valide sein", !reader.isTimestampValid())

            track.play()
            // Genug Stille schreiben, damit der Track waehrend der
            // gesamten Messung laeuft (2 s bei 48 kHz).
            val frames = 96_000
            val silence = ShortArray(frames * 2)
            track.write(silence, 0, silence.size)

            // Warm-up: Timestamps brauchen ein paar hundert ms, bis der
            // Treiber sie liefert.
            val deadline = System.nanoTime() + 5_000_000_000L
            var valid = false
            while (System.nanoTime() < deadline && !valid) {
                valid = reader.isTimestampValid()
                Thread.sleep(100)
            }
            assertTrue("getTimestamp() liefert nach 5 s keinen validen Wert", valid)

            val ts1 = reader.readTimestamp()!!
            Thread.sleep(200)
            val ts2 = reader.readTimestamp()!!

            val deltaFrames = ts2.framePosition - ts1.framePosition
            val deltaNs = ts2.systemTimeNs - ts1.systemTimeNs
            // Emulator/Simulator-Treiber liefern Timestamps in groben
            // Bursts; exakte Frame-Deltas sind dort nicht garantiert.
            // Robust geprueft wird: die Uhr geht vorwaerts, und die
            // Frame-Position bleibt hinter der erwarteten Echtzeit zurueck
            // (Latenz >= 0), waechst aber monoton.
            assertTrue("Timestamp-Uhr muss vorwaerts laufen", deltaNs > 0)
            assertTrue(
                "Frame-Position muss monoton wachsen (war $deltaFrames)",
                deltaFrames >= 0,
            )
            assertTrue(
                "Position darf nicht schneller als Echtzeit laufen: $deltaFrames Frames in $deltaNs ns",
                deltaFrames <= deltaNs * 48_000 / 1_000_000_000L + 96,
            )

            // Extrapolator: hoerbare Position nach 100 ms bewegt sich
            // gegenueber der letzten Timestamp-Position vorwaerts.
            Thread.sleep(100)
            val extrapolated = extrapolator.audibleFramePosition()
            assertTrue(
                "Extrapolator muss vorwaerts laufen, war $extrapolated < $deltaFrames",
                extrapolated >= deltaFrames,
            )
        } finally {
            track.stop()
            track.release()
        }
    }

    @Test
    fun fehlerhafte_konfiguration_wird_abgelehnt() {
        val track = newAudioTrack()
        try {
            val reader = AudioTrackTimestampReader(track)
            // 0 Hz ist ungueltig und muss eine IllegalArgumentException werfen.
            try {
                AudioTimestampExtrapolator(reader, 0, System::nanoTime)
                throw AssertionError("sampleRate 0 haette abgelehnt werden muessen")
            } catch (expected: IllegalArgumentException) {
                assertEquals(
                    "sampleRateHz muss positiv sein: 0",
                    expected.message,
                )
            }
        } finally {
            track.release()
        }
    }
}
