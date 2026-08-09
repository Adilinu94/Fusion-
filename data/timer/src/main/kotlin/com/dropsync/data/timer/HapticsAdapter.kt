package com.dropsync.data.timer

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * Haptikadapter (Bauplan Schritt 8.5): prueft die Geraetefaehigkeit vor
 * jeder Ausgabe und ist ohne Vibrator ein stiller No-Op — keine
 * Fehlermeldungsflut.
 */
class HapticsAdapter(
    context: Context,
) {
    private val vibrator: Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val manager =
                context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            manager?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }

    fun tick() {
        val target = vibrator ?: return
        if (!target.hasVibrator()) return
        target.vibrate(VibrationEffect.createOneShot(TICK_MS, VibrationEffect.DEFAULT_AMPLITUDE))
    }

    fun completion() {
        val target = vibrator ?: return
        if (!target.hasVibrator()) return
        target.vibrate(
            VibrationEffect.createWaveform(COMPLETION_PATTERN_MS, NO_REPEAT),
        )
    }

    companion object {
        private const val TICK_MS = 35L
        private val COMPLETION_PATTERN_MS = longArrayOf(0, 80, 60, 80)
        private const val NO_REPEAT = -1
    }
}

/**
 * Kurzer Abschluss-Signalton ueber ToneGenerator; kein Media3 und keine
 * Systemlautstaerkeaenderung.
 */
class CompletionTonePlayer {
    fun play() {
        val generator = ToneGenerator(AudioManager.STREAM_NOTIFICATION, TONE_VOLUME)
        try {
            generator.startTone(ToneGenerator.TONE_PROP_BEEP2, TONE_DURATION_MS)
        } finally {
            // ToneGenerator gibt native Ressourcen nicht selbst frei.
            Thread {
                Thread.sleep(RELEASE_DELAY_MS)
                generator.release()
            }.start()
        }
    }

    companion object {
        private const val TONE_VOLUME = 80
        private const val TONE_DURATION_MS = 200
        private const val RELEASE_DELAY_MS = 400L
    }
}
