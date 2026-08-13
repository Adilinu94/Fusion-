package com.dropsync.data.timer

import com.dropsync.domain.timer.CueOutput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Cue-Einstellungen einer Session (Bauplan 8.4, Abschnitt 6):
 * Ducking nur 0/50/100; unbekannte Werte lehnt der DuckingController ab.
 */
data class CueSettings(
    val duckingPercent: Int = 50,
    val ttsEnabled: Boolean = true,
    val hapticsEnabled: Boolean = true,
    val completionToneEnabled: Boolean = true,
)

/** Ansagetext fuer die Restzeit; V1 liefert de und en (Schritt 12.7). */
class SpeechTextFormatter(
    private val locale: Locale,
) {
    fun format(secondsRemaining: Int): String {
        val german = locale.language == Locale.GERMAN.language
        return when {
            secondsRemaining <= 0 -> {
                if (german) "Fertig" else "Done"
            }

            secondsRemaining % 60 == 0 -> {
                val minutes = secondsRemaining / 60
                when {
                    minutes == 1 && german -> "1 Minute"
                    minutes == 1 -> "1 minute"
                    german -> "$minutes Minuten"
                    else -> "$minutes minutes"
                }
            }

            german -> {
                "$secondsRemaining Sekunden"
            }

            else -> {
                "$secondsRemaining seconds"
            }
        }
    }
}

/**
 * Komponierte Cue-Ausgabe (Bauplan Schritt 8): TTS mit Ducking, Haptik
 * und Abschlusston. Fehlendes TTS laesst die Session vollstaendig mit
 * Haptik und UI-Countdown laufen (Abnahme Schritt 8).
 */
class AndroidCueOutput(
    private val tts: TtsSpeaker,
    private val haptics: HapticsAdapter,
    private val tonePlayer: CompletionTonePlayer,
    private val beepPlayer: CountdownBeepPlayer,
    private val ducking: DuckingController,
    private val formatter: SpeechTextFormatter,
    private val scope: CoroutineScope,
) : CueOutput {
    /** Wird vor Sessionstart aus dem Preset uebernommen (Schritt 8.4). */
    @Volatile
    var settings: CueSettings = CueSettings()

    override fun speak(
        cueSessionId: String,
        secondsRemaining: Int,
    ) {
        if (!settings.ttsEnabled || !tts.isAvailable()) return
        val text = formatter.format(secondsRemaining)
        scope.launch {
            val ducked = ducking.beginCue(cueSessionId, settings.duckingPercent)
            val spoken = tts.speak(cueSessionId, text)
            if (!spoken && ducked) {
                // Keine Ansage unterwegs => Ducking sofort zuruecknehmen.
                ducking.endCue(cueSessionId)
            }
            // Erfolgreiche Ansagen entducken ueber den
            // UtteranceProgressListener (onCueFinished -> endCue).
        }
    }

    override fun haptic(cueSessionId: String) {
        if (settings.hapticsEnabled) haptics.tick()
    }

    override fun countdownBeep(cueSessionId: String) {
        // Phase 7: kurzer vorgerenderter Beep statt ToneGenerator.
        if (settings.completionToneEnabled) beepPlayer.shortBeep()
    }

    override fun tone(cueSessionId: String) {
        if (settings.hapticsEnabled) haptics.completion()
        // Phase 7: langer Go-Beep als echtes Audio-Event; der alte
        // ToneGenerator ist der Fallback ohne vorgerenderten Clip.
        if (settings.completionToneEnabled) beepPlayer.goBeep()
    }

    override fun stopAll(cueSessionId: String) {
        tts.stop()
        scope.launch { ducking.abort(cueSessionId) }
    }
}
