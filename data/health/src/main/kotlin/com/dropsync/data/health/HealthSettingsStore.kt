package com.dropsync.data.health

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.healthDataStore by preferencesDataStore(name = "health_sync")

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
    ) : ChangesTokenStore {
        private val changesTokenKey = stringPreferencesKey("hc_changes_token")

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
    }
