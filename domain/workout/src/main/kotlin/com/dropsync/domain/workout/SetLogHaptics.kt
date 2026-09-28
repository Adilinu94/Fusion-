package com.dropsync.domain.workout

/**
 * A1 (Entscheidung 5.6/5.15): Haptik-Port fuer das Satz-Logging. Die
 * Feature-Schicht darf den Android-Adapter nicht importieren
 * (Architekturtest); die Implementierung liegt in :data:timer und feuert
 * einen kurzen Bestaetigungsimpuls (`tick()`, 35 ms) — genau einmal, nur
 * nach erfolgreichem Speichern.
 *
 * Hinweis: `timer_presets.haptics_enabled` ist derzeit ohne Leser
 * (Befundlage A1); sobald es eine echte Cue-Einstellung mit Leser gibt,
 * gehoert dieses Gate in die Implementierung dieses Ports.
 */
interface SetLogHaptics {
    /** Kurzer Impuls nach erfolgreichem Satz-Speichern. */
    fun confirm()

    /**
     * Kurze Bestaetigung fuer eine **einzelne** Aktion: Gewicht ± und
     * erkannte Wiederholung (2026-09-27, Befund 12.3, UI-Hebel 4).
     *
     * Getrennt von [confirm], weil die beiden eine **verschiedene
     * Bedeutung** haben: [confirm] sagt "gespeichert" (ein Ereignis,
     * einmal pro Satz), [tap] sagt "angekommen" (eine Rueckmeldung auf
     * jeden einzelnen Tastendruck bzw. jede gezaehlte Rep). Bei der
     * Rep-Erkennung ist das der wichtigste Moment der ganzen App: der
     * Nutzer liegt auf der Bank und schaut nicht auf den Bildschirm.
     *
     * **Kein `fun interface` mehr:** die SAM-Form ging nur mit einer
     * abstrakten Methode; seit [tap] sind es zwei.
     */
    fun tap()
}
