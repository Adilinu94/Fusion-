package com.dropsync.data.library

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.dropsync.domain.library.LibraryListConfig
import com.dropsync.domain.library.LibraryViewConfig
import com.dropsync.domain.library.LibraryViewPreferencesRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * DataStore-Persistenz der Bibliotheksansichts-Konfiguration (Plan Phase 6.4).
 * Reihenfolge und ausgeblendete Ansichten werden als komma-getrennte
 * Schluessellisten gespeichert (die Schluessel sind Enum-Namen ohne Komma).
 * Fehlt der Eintrag, liefert [config] null = noch nicht konfiguriert.
 */
class LibraryViewPreferencesStore(
    private val dataStore: DataStore<Preferences>,
) : LibraryViewPreferencesRepository {
    override val config: Flow<LibraryViewConfig?> =
        dataStore.data.map { prefs ->
            val order =
                prefs[KEY_ORDER]
                    ?.takeIf { it.isNotEmpty() }
                    ?.split(SEP)
                    ?: return@map null
            val hidden =
                prefs[KEY_HIDDEN]
                    ?.takeIf { it.isNotEmpty() }
                    ?.split(SEP)
                    ?.toSet()
                    ?: emptySet()
            LibraryViewConfig(orderedKeys = order, hiddenKeys = hidden)
        }

    override suspend fun setConfig(config: LibraryViewConfig) {
        dataStore.edit { prefs ->
            prefs[KEY_ORDER] = config.orderedKeys.joinToString(SEP)
            prefs[KEY_HIDDEN] = config.hiddenKeys.joinToString(SEP)
        }
    }

    override val smartShuffleEnabled: Flow<Boolean> =
        dataStore.data.map { prefs -> prefs[KEY_SMART_SHUFFLE] ?: false }

    override suspend fun setSmartShuffleEnabled(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[KEY_SMART_SHUFFLE] = enabled }
    }

    // Listen-Optionen pro Kategorie (Poweramp-Umbau): ein String-Eintrag
    // "sortKey|desc|viewModeKey" je Kategorie-Schluessel; die Schluessel
    // sind Enum-Namen ohne Trennzeichen.
    override fun listConfig(categoryKey: String): Flow<LibraryListConfig?> =
        dataStore.data.map { prefs ->
            val raw = prefs[listKey(categoryKey)] ?: return@map null
            val parts = raw.split(LIST_SEP)
            if (parts.size != 3) return@map null
            LibraryListConfig(
                sortKey = parts[0],
                descending = parts[1].toBoolean(),
                viewModeKey = parts[2],
            )
        }

    override suspend fun setListConfig(
        categoryKey: String,
        config: LibraryListConfig,
    ) {
        dataStore.edit { prefs ->
            prefs[listKey(categoryKey)] =
                listOf(config.sortKey, config.descending.toString(), config.viewModeKey)
                    .joinToString(LIST_SEP)
        }
    }

    private fun listKey(categoryKey: String) = stringPreferencesKey("list_config_$categoryKey")

    companion object {
        const val DATA_STORE_NAME = "library_view_prefs"
        private const val SEP = ","
        private const val LIST_SEP = "|"
        private val KEY_ORDER = stringPreferencesKey("view_order")
        private val KEY_HIDDEN = stringPreferencesKey("view_hidden")
        private val KEY_SMART_SHUFFLE = booleanPreferencesKey("smart_shuffle_enabled")
    }
}
