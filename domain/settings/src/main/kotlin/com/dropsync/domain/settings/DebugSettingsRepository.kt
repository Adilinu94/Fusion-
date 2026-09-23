package com.dropsync.domain.settings

import kotlinx.coroutines.flow.Flow

/**
 * P2-17/RC-7: Entwickler-Schalter der App. Der Diagnose-Abschnitt in den
 * Einstellungen ist bewusst hinter einem Schalter versteckt (Design 8.4:
 * "Diagnose Werte optional hinter Entwickler Schalter"), damit Technik den
 * normalen Alltag nicht belastet.
 *
 * Default `false`.
 */
interface DebugSettingsRepository {
    /** `true`, wenn die Diagnose-Werte in den Einstellungen sichtbar sind. */
    val diagnosticsEnabled: Flow<Boolean>

    /** Schaltet den Diagnose-Abschnitt an oder aus. */
    suspend fun setDiagnosticsEnabled(enabled: Boolean)
}
