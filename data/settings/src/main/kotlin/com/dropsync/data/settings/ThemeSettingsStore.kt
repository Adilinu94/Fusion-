package com.dropsync.data.settings

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.dropsync.core.model.ThemeMode
import com.dropsync.domain.settings.ThemeSettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.themeSettingsDataStore by preferencesDataStore(name = "theme_settings")

/**
 * DataStore-Persistenz des App-Designs. Als stabiler Enum-String
 * gespeichert; unbekannte/fehlende Werte fallen auf [ThemeMode.SYSTEM]
 * zurueck (die App folgt dann dem Systemdesign).
 */
class ThemeSettingsStore(
    private val context: Context,
) : ThemeSettingsRepository {
    override val themeMode: Flow<ThemeMode> =
        context.themeSettingsDataStore.data.map { prefs ->
            prefs[KEY_THEME_MODE]
                ?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() }
                ?: ThemeMode.SYSTEM
        }

    override suspend fun setThemeMode(mode: ThemeMode) {
        context.themeSettingsDataStore.edit { prefs ->
            prefs[KEY_THEME_MODE] = mode.name
        }
    }

    companion object {
        private val KEY_THEME_MODE = stringPreferencesKey("theme_mode")
    }
}
