package com.dropsync.data.health

import android.content.Context
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.healthDataStore by preferencesDataStore(
    name = "health_sync",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

/**
 * Sync-Schalter (B5, Google-Vorgabe „Sync with Health Connect"): Ablauf wie
 * [ChangesTokenStore] als Interface, damit die Source ohne Context testbar
 * bleibt.
 */
internal interface HeartRateSyncSettings {
    /** Aus = jede Synchronisation pausiert; Default an. */
    val heartRateSyncEnabled: Flow<Boolean>

    suspend fun setHeartRateSyncEnabled(enabled: Boolean)
}

/**
 * Persistenz des Changes-Tokens (Plan 3.3/4, Muster DspSettingsStore).
 * Als Interface abstrahiert, damit die Source ohne Android-Context
 * JVM-testbar bleibt.
 */
internal interface ChangesTokenStore {
    suspend fun changesToken(): String?

    suspend fun saveChangesToken(token: String)

    suspend fun clearChangesToken()
}

@Singleton
internal class HealthSettingsStore
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
    ) : ChangesTokenStore,
        HeartRateSyncSettings {
        private val changesTokenKey = stringPreferencesKey("hc_changes_token")
        private val syncEnabledKey = booleanPreferencesKey("hr_sync_enabled")

        override suspend fun changesToken(): String? =
            context.healthDataStore.data
                .map { it[changesTokenKey] }
                .first()

        override suspend fun saveChangesToken(token: String) {
            context.healthDataStore.edit { prefs -> prefs[changesTokenKey] = token }
        }

        override suspend fun clearChangesToken() {
            context.healthDataStore.edit { prefs -> prefs.remove(changesTokenKey) }
        }

        /**
         * Sync-Schalter (B5, Google-Vorgabe): Default an — aus pausiert jede
         * Synchronisation, bis der Nutzer in den Einstellungen fortsetzt.
         */
        override val heartRateSyncEnabled: Flow<Boolean> =
            context.healthDataStore.data.map { prefs ->
                prefs[syncEnabledKey] ?: true
            }

        override suspend fun setHeartRateSyncEnabled(enabled: Boolean) {
            context.healthDataStore.edit { prefs -> prefs[syncEnabledKey] = enabled }
        }
    }
