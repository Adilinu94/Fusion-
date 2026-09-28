package com.dropsync.data.playback

import android.content.Context
import android.net.Uri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dropsync.domain.audio.MixPreset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import kotlin.math.PI
import kotlin.math.sin

/**
 * Geraetetest fuer den isolierten Dual-Player-PoC.
 *
 * Beide Player teilen die Medien-AudioAttributes, fordern aber absichtlich
 * keinen eigenen Audio-Focus an. Damit wird parallele Wiedergabe getestet,
 * nicht die Produktionsstrategie fuer Focus oder MediaSession. Auf einem
 * Emulator ist die Audioausgabe kein Hoertest; dazu ist ein physisches
 * Geraet mit angeschlossenem Ausgabeweg noetig.
 */
@RunWith(AndroidJUnit4::class)
class DualPlayerCrossfadeInstrumentedTest {
    @Test
    fun twoLocalMedia3PlayersOverlapAndCompleteIndependentVolumeRamp() =
        runBlocking {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val firstFile = makeWaveFile(context, "crossfade-a.wav", 440.0)
            val secondFile = makeWaveFile(context, "crossfade-b.wav", 660.0)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            var outgoing: ExoPlayer? = null
            var incoming: ExoPlayer? = null

            try {
                withContext(Dispatchers.Main.immediate) {
                    val attributes =
                        AudioAttributes
                            .Builder()
                            .setUsage(C.USAGE_MEDIA)
                            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                            .build()
                    outgoing =
                        ExoPlayer
                            .Builder(context)
                            .setLooper(android.os.Looper.getMainLooper())
                            .build()
                            .apply {
                                setAudioAttributes(attributes, false)
                                setMediaItem(MediaItem.fromUri(Uri.fromFile(firstFile)))
                                prepare()
                            }
                    incoming =
                        ExoPlayer
                            .Builder(context)
                            .setLooper(android.os.Looper.getMainLooper())
                            .build()
                            .apply {
                                setAudioAttributes(attributes, false)
                                setMediaItem(MediaItem.fromUri(Uri.fromFile(secondFile)))
                                prepare()
                            }
                }

                val outgoingPlayer = requireNotNull(outgoing)
                val incomingPlayer = requireNotNull(incoming)
                awaitReady(outgoingPlayer)
                awaitReady(incomingPlayer)

                withContext(Dispatchers.Main.immediate) {
                    outgoingPlayer.play()
                    val controller = DualPlayerCrossfadeController(scope, stepMs = 50L)
                    controller.start(
                        outgoing = Media3CrossfadeVolumeTarget(outgoingPlayer),
                        incoming = Media3CrossfadeVolumeTarget(incomingPlayer),
                        durationSeconds = 1,
                        preset = MixPreset.FADE,
                    )
                    delay(250L)
                    assertTrue(
                        "incoming Media3 player should be playing during overlap",
                        incomingPlayer.isPlaying,
                    )
                    assertTrue(
                        "outgoing Media3 player should still be playing during overlap",
                        outgoingPlayer.isPlaying,
                    )
                    assertTrue(
                        "incoming player should have an intermediate ramp gain",
                        incomingPlayer.volume in 0.05f..0.95f,
                    )
                    assertTrue(
                        "outgoing player should have an intermediate ramp gain",
                        outgoingPlayer.volume in 0.05f..0.95f,
                    )
                    delay(850L)
                    assertEquals(1f, incomingPlayer.volume, 0.001f)
                    assertEquals(0f, outgoingPlayer.volume, 0.001f)
                    assertEquals(Player.STATE_IDLE, outgoingPlayer.playbackState)
                }
            } finally {
                withContext(Dispatchers.Main.immediate) {
                    outgoing?.release()
                    incoming?.release()
                    scope.cancel()
                }
                firstFile.delete()
                secondFile.delete()
            }
        }

    private suspend fun awaitReady(player: Player) {
        withContext(Dispatchers.Main.immediate) {
            val deadline = System.nanoTime() + READY_TIMEOUT_NS
            while (player.playbackState != Player.STATE_READY && System.nanoTime() < deadline) {
                delay(20L)
            }
            assertEquals("local WAV must prepare", Player.STATE_READY, player.playbackState)
        }
    }

    private fun makeWaveFile(
        context: Context,
        name: String,
        frequencyHz: Double,
    ): File {
        val sampleRate = 48_000
        val sampleCount = sampleRate * 3
        val dataBytes = sampleCount * Short.SIZE_BYTES
        val file = File(context.cacheDir, name)
        BufferedOutputStream(FileOutputStream(file)).use { output ->
            fun writeAscii(value: String) = output.write(value.toByteArray(Charsets.US_ASCII))

            fun writeLe16(value: Int) {
                output.write(value and 0xff)
                output.write((value ushr 8) and 0xff)
            }

            fun writeLe32(value: Int) {
                writeLe16(value and 0xffff)
                writeLe16(value ushr 16)
            }

            writeAscii("RIFF")
            writeLe32(36 + dataBytes)
            writeAscii("WAVE")
            writeAscii("fmt ")
            writeLe32(16)
            writeLe16(1)
            writeLe16(1)
            writeLe32(sampleRate)
            writeLe32(sampleRate * Short.SIZE_BYTES)
            writeLe16(Short.SIZE_BYTES)
            writeLe16(16)
            writeAscii("data")
            writeLe32(dataBytes)
            repeat(sampleCount) { index ->
                val sample = (sin(2.0 * PI * frequencyHz * index / sampleRate) * 0.2 * Short.MAX_VALUE).toInt()
                writeLe16(sample and 0xffff)
            }
        }
        return file
    }

    private companion object {
        const val READY_TIMEOUT_NS = 5_000_000_000L
    }
}
