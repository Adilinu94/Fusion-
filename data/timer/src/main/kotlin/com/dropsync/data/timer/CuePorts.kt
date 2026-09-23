package com.dropsync.data.timer

/**
 * Adapter-Ports der Cue-Ausgabe (Bauplan Schritt 8): AndroidCueOutput
 * komponiert TTS, Haptik und Beeps ueber diese Schnittstelle — die
 * echten Adapter bleiben austauschbar und die Komposition wird ohne
 * Android-Systemdienste testbar.
 */
interface CueSpeaker {
    /** false => Timer laeuft mit Haptik/Ton weiter (Schritt 8.1). */
    fun isAvailable(): Boolean

    /** Liefert true, wenn die Ansage uebergeben wurde. */
    fun speak(
        cueSessionId: String,
        text: String,
    ): Boolean

    /** Stoppt laufende Ansagen sofort (Schritt 7.7). */
    fun stop()
}

interface CueHaptics {
    fun tick()

    fun completion()
}

interface CueBeeps {
    fun shortBeep()

    fun goBeep()
}
