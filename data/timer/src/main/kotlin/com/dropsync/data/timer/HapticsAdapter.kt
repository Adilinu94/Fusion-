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
) : CueHaptics {
    private val vibrator: Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val manager =
                context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            manager?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }

    override fun tick() {
        val target = vibrator ?: return
        if (!target.hasVibrator()) return
        target.vibrate(VibrationEffect.createOneShot(TICK_MS, VibrationEffect.DEFAULT_AMPLITUDE))
    }

    override fun completion() {
        val target = vibrator ?: return
        if (!target.hasVibrator()) return
        target.vibrate(
            VibrationEffect.createWaveform(COMPLETION_PATTERN_MS, NO_REPEAT),
        )
    }

    /**
     * Kurze Bestaetigung fuer eine **einzelne Aktion** — Gewicht ±, Rep
     * erkannt, Marker gesetzt (2026-09-27, Befund 12.3, UI-Hebel 4).
     *
     * **Warum das die groesste Haptik-Luecke war:** die Haptik saess
     * ausschliesslich am **Timer** (Countdown-Tick, Satzende) und beim
     * Queue-Sortieren. Bei den drei Aktionen, die ein Trainierender am
     * staengsten wiederholt — Gewicht ± (5–10× pro Satz), Rep erkannt
     * (8–15× pro Satz), Marker verschieben — gab es **kein** Feedback.
     *
     * Gerade die Rep-Erkennung ist der Fall, in dem der Blick vom Handy
     * weg **muss**: der Nutzer liegt auf der Bank und schaut nicht auf
     * den Bildschirm. Ohne Rueckmeldung weiss er nicht, ob die
     * Wiederholung gezählt wurde.
     *
     * Bewusst ein **kurzer, leiser** Impuls (12 ms, halbe Amplitude):
     * bei 15 Reps pro Satz waere ein lauter Impuls muedemachend. Die
     * Staerke unterscheidet sich vom Timer-Tick, damit der Nutzer die
     * beiden nicht verwechselt.
     */
    fun tap() {
        val target = vibrator ?: return
        if (!target.hasVibrator()) return
        target.vibrate(
            VibrationEffect.createOneShot(TAP_MS, TAP_AMPLITUDE),
        )
    }

    companion object {
        private const val TICK_MS = 35L
        private val COMPLETION_PATTERN_MS = longArrayOf(0, 80, 60, 80)
        private const val NO_REPEAT = -1

        /**
         * Aktions-Bestaetigung: kuerzer und leiser als der Countdown-Tick
         * (2026-09-27, Befund 12.3). Bei 8–15 Reps pro Satz waere ein
         * 35-ms-Impuls in voller Staerke muedemachend; der Nutzer soll
         * die Reps **zaehlen**, nicht die Vibration spueren.
         */
        private const val TAP_MS = 12L
        private const val TAP_AMPLITUDE = 128
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
