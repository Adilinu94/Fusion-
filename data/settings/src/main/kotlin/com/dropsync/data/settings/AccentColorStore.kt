package com.dropsync.data.settings

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.dropsync.core.model.AccentColor
import com.dropsync.domain.settings.AccentColorRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.accentColorDataStore by preferencesDataStore(name = "accent_settings")

/**
 * DataStore-Persistenz der Akzentfarbe. Als stabiler Enum-String
 * gespeichert; unbekannte/fehlende Werte fallen auf [AccentColor.LIME]
 * zurueck (die Markenfarbe).
 */
class AccentColorStore(
    private val context: Context,
) : AccentColorRepository {
    override val accentColor: Flow<AccentColor> =
        context.accentColorDataStore.data.map { prefs ->
            prefs[KEY_ACCENT]
                ?.let { runCatching { AccentColor.valueOf(it) }.getOrNull() }
                ?: AccentColor.LIME
        }

    override suspend fun setAccentColor(color: AccentColor) {
        context.accentColorDataStore.edit { prefs ->
            prefs[KEY_ACCENT] = color.name
        }
    }

    companion object {
        private val KEY_ACCENT = stringPreferencesKey("accent_color")
    }
}
