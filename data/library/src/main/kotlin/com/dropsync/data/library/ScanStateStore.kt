package com.dropsync.data.library

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first

/**
 * Merkt sich den zuletzt verarbeiteten MediaStore-Aenderungsstand
 * (Bauplan Schritt 4.3): unveraenderter Stand => kein Vollscan.
 */
interface ScanStateStore {
    suspend fun lastGeneration(): String?

    suspend fun setLastGeneration(value: String)
}

class DataStoreScanStateStore(
    private val dataStore: DataStore<Preferences>,
) : ScanStateStore {
    override suspend fun lastGeneration(): String? = dataStore.data.first()[KEY_GENERATION]

    override suspend fun setLastGeneration(value: String) {
        dataStore.edit { it[KEY_GENERATION] = value }
    }

    companion object {
        const val DATA_STORE_NAME = "library_scan_state"
        private val KEY_GENERATION = stringPreferencesKey("last_media_store_generation")
    }
}
