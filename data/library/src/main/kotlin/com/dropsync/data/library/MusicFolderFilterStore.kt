package com.dropsync.data.library

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.dropsync.domain.library.MusicFolderFilterRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * DataStore-Persistenz der Ordnerauswahl (Poweramp-Umbau, Punkt 3). Die
 * ausgeschlossenen Ordner werden als String-Set gehalten; relative Pfade
 * koennen Kommas enthalten, deshalb kein getrennter String wie bei den
 * Ansichts-Schluesseln. Fehlt der Eintrag, ist nichts ausgeschlossen.
 */
class MusicFolderFilterStore(
    private val dataStore: DataStore<Preferences>,
) : MusicFolderFilterRepository {
    override val excludedFolders: Flow<Set<String>> =
        dataStore.data.map { prefs -> prefs[KEY_EXCLUDED] ?: emptySet() }

    override suspend fun setExcludedFolders(paths: Set<String>) {
        dataStore.edit { prefs -> prefs[KEY_EXCLUDED] = paths }
    }

    companion object {
        const val DATA_STORE_NAME = "music_folder_filter"
        private val KEY_EXCLUDED = stringSetPreferencesKey("excluded_folders")
    }
}
