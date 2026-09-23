package com.dropsync.domain.sensor

import kotlinx.coroutines.flow.StateFlow

/**
 * RC-7: Zugriff auf den Diagnose-Snapshot des zuletzt gestoppten Sets,
 * ueber ViewModel-Grenzen hinweg. Der Train-Pfad schreibt beim Stoppen, das
 * Diagnose-Panel in den Einstellungen liest. Bewusst nur der LETZTE Wert im
 * Speicher — die Historie gehoert nicht in dieses Panel.
 */
interface SetDiagnosticsLog {
    /** Letzter aufgezeichneter Snapshot; null, solange kein Satz gestoppt wurde. */
    val last: StateFlow<SetDiagnostics?>

    /** Zeichnet den Snapshot eines gestoppten Sets auf. */
    fun record(diagnostics: SetDiagnostics)
}
