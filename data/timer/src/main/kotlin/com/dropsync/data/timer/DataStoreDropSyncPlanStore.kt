package com.dropsync.data.timer

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.dropsync.domain.timer.DropSyncPlanKind
import com.dropsync.domain.timer.DropSyncPlanMarker
import com.dropsync.domain.timer.DropSyncPlanStore
import kotlinx.coroutines.flow.first

/**
 * DataStore-Persistenz fuer den C13-Marker (Entscheidungen 5.8/5.9):
 * Der Marker ueberlebt App-/Service-Kills und erlaubt dem Koordinator
 * beim Start die Unterscheidung "frisch" vs. "rekonstruiert".
 *
 * Bewusst zwei primitive Preference-Keys statt JSON: Der Marker ist ein
 * einzelnes Paar (Art, Sitzungs-ID) und hat kein Schema-Wachstum.
 * Unlesbare Werte werden als "kein Marker" behandelt (defensiv).
 */
class DataStoreDropSyncPlanStore(
    private val dataStore: DataStore<Preferences>,
) : DropSyncPlanStore {
    override suspend fun load(): DropSyncPlanMarker? {
        val prefs = dataStore.data.first()
        val kind =
            prefs[KEY_KIND]?.let { raw ->
                DropSyncPlanKind.entries.firstOrNull { it.name == raw }
            } ?: return null
        val sessionId = prefs[KEY_SESSION_ID]?.takeIf { it.isNotBlank() } ?: return null
        return DropSyncPlanMarker(kind = kind, sessionId = sessionId)
    }

    override suspend fun save(marker: DropSyncPlanMarker) {
        dataStore.edit {
            it[KEY_KIND] = marker.kind.name
            it[KEY_SESSION_ID] = marker.sessionId
        }
    }

    override suspend fun clear() {
        dataStore.edit {
            it.remove(KEY_KIND)
            it.remove(KEY_SESSION_ID)
        }
    }

    companion object {
        const val DATA_STORE_NAME = "dropsync_plan"
        private val KEY_KIND = stringPreferencesKey("dropsync_plan_kind")
        private val KEY_SESSION_ID = stringPreferencesKey("dropsync_plan_session_id")
    }
}
