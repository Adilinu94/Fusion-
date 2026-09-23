package com.dropsync.data.settings

import android.content.Context
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStore
import com.dropsync.domain.settings.DebugSettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.debugSettingsDataStore by preferencesDataStore(
    name = "debug_settings",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

/**
 * DataStore-Persistenz des Entwickler-Schalters (P2-17/RC-7). Fehlender Wert
 * heisst „aus" — Diagnose ist ein Werkzeug, kein Standardzustand.
 */
class DebugSettingsStore(
    private val context: Context,
) : DebugSettingsRepository {
    override val diagnosticsEnabled: Flow<Boolean> =
        context.debugSettingsDataStore.data.map { prefs ->
            prefs[KEY_DIAGNOSTICS_ENABLED] ?: false
        }

    override suspend fun setDiagnosticsEnabled(enabled: Boolean) {
        context.debugSettingsDataStore.edit { prefs ->
            prefs[KEY_DIAGNOSTICS_ENABLED] = enabled
        }
    }

    companion object {
        private val KEY_DIAGNOSTICS_ENABLED = booleanPreferencesKey("diagnostics_enabled")
    }
}
