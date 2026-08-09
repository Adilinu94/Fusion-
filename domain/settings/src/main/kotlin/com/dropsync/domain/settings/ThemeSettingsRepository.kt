package com.dropsync.domain.settings

import com.dropsync.core.model.ThemeMode
import kotlinx.coroutines.flow.Flow

/**
 * Nutzereinstellung fuer das App-Design (in den Einstellungen waehlbar).
 * Default ist [ThemeMode.SYSTEM] — die App folgt dem hellen/dunklen
 * Systemdesign.
 */
interface ThemeSettingsRepository {
    /** Aktuell gewaehlter Design-Modus; Default [ThemeMode.SYSTEM]. */
    val themeMode: Flow<ThemeMode>

    /** Setzt den Design-Modus (Systemdesign folgen / Hell / Dunkel). */
    suspend fun setThemeMode(mode: ThemeMode)
}
