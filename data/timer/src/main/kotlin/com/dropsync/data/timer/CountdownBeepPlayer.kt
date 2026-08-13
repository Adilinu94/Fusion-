package com.dropsync.data.timer

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.sin

/**
 * Vorgerenderte Countdown-Pieps (Design Phase 7): 3 kurze Beeps fuer
 * 3-2-1 und ein laengerer Beep fuer Go - als echte Audio-Events ueber
 * einen eigenen AudioTrack, nicht als ToneGenerator-/TTS-Callback.
 *
 * Die PCM-Clips werden einmal beim Anlegen vorgerendert (Sinus 880 Hz,
 * Huellkurve gegen Knacksen); der Go-Beep liegt eine Oktave hoeher
 * (1760 Hz), damit er sich klar abhebt. Keine Systemlautstaerke-
 * Aenderung: Der AudioTrack laeuft mit fester, niedriger Lautstaerke
 * und gibt die Ressourcen nach dem Abspielen frei.
 */
class CountdownBeepPlayer {
    private val shortBeep = renderBeep(FREQ_HZ, SHORT_MS, SAMPLE_RATE)
    private val goBeep = renderBeep(GO_FREQ_HZ, GO_MS, SAMPLE_RATE)

    fun shortBeep() = play(shortBeep)

    fun goBeep() = play(goBeep)

    private fun play(pcm: ShortArray) {
        val minBuffer =
            AudioTrack
                .getMinBufferSize(
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                ).coerceAtLeast(pcm.size * 2)
        val track =
            AudioTrack
                .Builder()
                .setAudioAttributes(
                    AudioAttributes
                        .Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                ).setAudioFormat(
                    AudioFormat
                        .Builder()
                        .setSampleRate(SAMPLE_RATE)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                ).setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(minBuffer)
                .build()
        track.write(pcm, 0, pcm.size)
        track.play()
        // Ressourcen nach dem Abspielen freigeben (darf nicht den
        // Audio-Thread blockieren).
        Thread {
            Thread.sleep((pcm.size * 1000L / SAMPLE_RATE) + RELEASE_MARGIN_MS)
            track.release()
        }.start()
    }

    companion object {
        private const val SAMPLE_RATE = 44_100
        private const val FREQ_HZ = 880.0
        private const val GO_FREQ_HZ = 1760.0
        private const val SHORT_MS = 120
        private const val GO_MS = 400
        private const val RELEASE_MARGIN_MS = 200L

        /**
         * Sinus-Beep mit linearer Attack-/Release-Huellkurve (5% je
         * Seite), damit kein Klick entsteht.
         */
        private fun renderBeep(
            freqHz: Double,
            durationMs: Int,
            sampleRate: Int,
        ): ShortArray {
            val count = sampleRate * durationMs / 1000
            val ramp = (count * 0.05).toInt().coerceAtLeast(1)
            return ShortArray(count) { i ->
                val envelope =
                    when {
                        i < ramp -> i.toDouble() / ramp
                        i >= count - ramp -> (count - 1 - i).toDouble() / ramp
                        else -> 1.0
                    }
                val sample = sin(2.0 * PI * freqHz * i / sampleRate) * envelope * AMPLITUDE
                (sample * Short.MAX_VALUE).toInt().toShort()
            }
        }

        private const val AMPLITUDE = 0.6
    }
}
