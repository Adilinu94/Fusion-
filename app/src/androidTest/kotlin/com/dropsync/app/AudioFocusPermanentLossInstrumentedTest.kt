package com.dropsync.app

import android.content.Context
import android.media.AudioManager
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.RawResourceDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Instrumentierter Kern-Test (Testinfra-Plan Schritt 4, T3):
 * AudioFocus-Verhalten auf echtem Android (Media3 uebernimmt den Focus).
 *
 * - Permanenter LOSS (z. B. zweiter Musik-Player): Player stoppt
 *   (playWhenReady = false, Zustand PAUSED/BUFFERING, nicht mehr READY).
 * - Der Test baut den Player identisch zur App auf
 *   (`setAudioAttributes(attrs, true)`), spielt ein synthetisches
 *   WAV-Fixture und simuliert den LOSS ueber den System-AudioManager
 *   (`requestAudioFocus(... LOSS)`). Der Emulator/Simulator behandelt
 *   das wie ein echtes Gerät.
 */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class AudioFocusPermanentLossInstrumentedTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun buildPlayer(): ExoPlayer =
        ExoPlayer
            .Builder(context)
            .setAudioAttributes(
                AudioAttributes
                    .Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                true,
            ).setHandleAudioBecomingNoisy(true)
            .build()

    @Test
    fun permanenter_focusverlust_stoppt_die_wiedergabe() {
        // ExoPlayer muss auf dem Main-Thread erzeugt/gesteuert werden;
        // alle Zustandslesungen laufen ueber Listener (Main-Thread).
        lateinit var player: ExoPlayer
        InstrumentationRegistry.getInstrumentation().runOnMainSync { player = buildPlayer() }
        val started = CountDownLatch(1)
        val stoppedAfterLoss = CountDownLatch(1)
        val ready = AtomicReference<Boolean>(false)
        val wasPlaying = AtomicReference<Boolean>(false)
        try {
            player.addListener(
                object : Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        if (playbackState == Player.STATE_READY) {
                            ready.set(true)
                            started.countDown()
                        }
                    }

                    override fun onPlayWhenReadyChanged(
                        playWhenReady: Boolean,
                        reason: Int,
                    ) {
                        // Nach dem Focus-Verlust stoppt Media3 die Wiedergabe.
                        // Nur zaehlen, wenn wir vorher wirklich READY+playing waren.
                        if (!playWhenReady && ready.get()) {
                            wasPlaying.set(true)
                            stoppedAfterLoss.countDown()
                        }
                    }
                },
            )
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                player.setMediaItem(testToneMediaItem())
                player.prepare()
                player.play()
            }
            assertTrue("Player wurde nicht READY", started.await(15, TimeUnit.SECONDS))

            // Permanenter Verlust simuliert (zweiter Musik-Player).
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val request =
                android.media.AudioFocusRequest
                    .Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(
                        android.media.AudioAttributes
                            .Builder()
                            .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build(),
                    ).setOnAudioFocusChangeListener {}
                    .build()
            audioManager.requestAudioFocus(request)

            // Media3 hat auf LOSS reagiert und die Wiedergabe gestoppt.
            assertTrue(
                "Player muss nach LOSS stoppen",
                stoppedAfterLoss.await(5, TimeUnit.SECONDS),
            )
            assertTrue("Player lief vor dem LOSS", wasPlaying.get() == true)
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync { player.release() }
        }
    }

    /** Generiert ein kurzes WAV-Fixture (1 s, 440 Hz, Stereo) zur Laufzeit. */
    private fun testToneMediaItem(): MediaItem {
        val sampleRate = 44_100
        val seconds = 1
        val frames = sampleRate * seconds
        val bytes = java.io.ByteArrayOutputStream()
        val dataSize = frames * 2 * 2 // Stereo, 16 bit

        fun writeString(s: String) = bytes.write(s.toByteArray(Charsets.US_ASCII))

        fun writeIntLE(v: Int) {
            bytes.write(v and 0xFF)
            bytes.write((v shr 8) and 0xFF)
            bytes.write((v shr 16) and 0xFF)
            bytes.write((v shr 24) and 0xFF)
        }

        fun writeShortLE(v: Int) {
            bytes.write(v and 0xFF)
            bytes.write((v shr 8) and 0xFF)
        }
        writeString("RIFF")
        writeIntLE(36 + dataSize)
        writeString("WAVE")
        writeString("fmt ")
        writeIntLE(16) // PCM-Header
        writeShortLE(1) // PCM
        writeShortLE(2) // Stereo
        writeIntLE(sampleRate)
        writeIntLE(sampleRate * 4) // ByteRate
        writeShortLE(4) // BlockAlign
        writeShortLE(16) // BitsPerSample
        writeString("data")
        writeIntLE(dataSize)
        val sample = ByteArray(4)
        for (frame in 0 until frames) {
            val v = (kotlin.math.sin(2.0 * Math.PI * 440.0 * frame / sampleRate) * 8000).toInt()
            sample[0] = (v and 0xFF).toByte()
            sample[1] = ((v shr 8) and 0xFF).toByte()
            sample[2] = sample[0]
            sample[3] = sample[1]
            bytes.write(sample)
        }
        val uri =
            "data:application/octet-stream;base64," +
                android.util.Base64.encodeToString(bytes.toByteArray(), android.util.Base64.NO_WRAP)
        return MediaItem.fromUri(uri)
    }
}
