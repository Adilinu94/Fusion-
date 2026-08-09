package com.dropsync.domain.settings

import com.dropsync.core.model.AccentColor
import kotlinx.coroutines.flow.Flow

/**
 * Nutzereinstellung fuer die Akzentfarbe (in den Einstellungen waehlbar).
 * Default ist [AccentColor.LIME] — die Markenfarbe. Die Akzentfarbe gilt
 * gleichermassen in Hell- und Dunkelmodus.
 */
interface AccentColorRepository {
    /** Aktuell gewaehlte Akzentfarbe; Default [AccentColor.LIME]. */
    val accentColor: Flow<AccentColor>

    /** Setzt die Akzentfarbe (Marken-Lime / Blau). */
    suspend fun setAccentColor(color: AccentColor)
}
