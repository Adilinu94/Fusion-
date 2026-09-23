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
fun interface SetLogHaptics {
    /** Kurzer Impuls nach erfolgreichem Satz-Speichern. */
    fun confirm()
}
