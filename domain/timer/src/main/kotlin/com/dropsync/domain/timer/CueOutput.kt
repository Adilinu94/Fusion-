package com.dropsync.domain.timer

/**
 * Cue-Ausgabe als Schnittstelle (Bauplan Schritt 7.6): getrennte
 * Implementierungen fuer TTS, Haptik und Signalton liegen in
 * :data:timer (Schritt 8). Der Use Case kennt keine TTS- oder
 * Vibrator-API. Jeder Aufruf traegt die Cue-Session-ID; ein Callback
 * darf nur handeln, wenn sie der aktiven Sitzung entspricht (8.3).
 */
interface CueOutput {
    /** Spricht die Restzeit an (z. B. "60 Sekunden"). */
    fun speak(
        cueSessionId: String,
        secondsRemaining: Int,
    )

    /** Kurze, vordefinierte Haptik; No-Op ohne Geraetefaehigkeit (8.5). */
    fun haptic(cueSessionId: String)

    /** Kurzer Abschluss-Signalton. */
    fun tone(cueSessionId: String)

    /**
     * Bei Abbruch: TTS stoppen und aktives Ducking sofort rueckgaengig
     * machen (Schritt 7.7).
     */
    fun stopAll(cueSessionId: String)
}

/** No-Op-Testfake (Schritt 7.6). */
class NoOpCueOutput : CueOutput {
    override fun speak(
        cueSessionId: String,
        secondsRemaining: Int,
    ) = Unit

    override fun haptic(cueSessionId: String) = Unit

    override fun tone(cueSessionId: String) = Unit

    override fun stopAll(cueSessionId: String) = Unit
}
