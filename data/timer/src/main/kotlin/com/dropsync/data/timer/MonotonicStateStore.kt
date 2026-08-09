package com.dropsync.data.timer

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import kotlinx.coroutines.flow.first

/**
 * Speichert den letzten bekannten monotonen Zeitwert (Bauplan 7.9):
 * Beim Start der Timer-Infrastruktur vergleicht RebootGuard diesen Wert
 * mit `elapsedRealtime()`. Ein Ruecksprung, ein fehlender Wert oder eine
 * inkonsistente Persistenz verwirft jeden persistierten Timer
 * (CANCELLED mit DEVICE_REBOOT_OR_UNKNOWN_CLOCK); Workout-Sessions
 * bleiben unberuehrt.
 */
interface MonotonicStateStore {
    suspend fun lastElapsedRealtimeMs(): Long?

    suspend fun setLastElapsedRealtimeMs(value: Long)
}

class DataStoreMonotonicStateStore(
    private val dataStore: DataStore<Preferences>,
) : MonotonicStateStore {
    override suspend fun lastElapsedRealtimeMs(): Long? = dataStore.data.first()[KEY_LAST_ELAPSED]

    override suspend fun setLastElapsedRealtimeMs(value: Long) {
        dataStore.edit { it[KEY_LAST_ELAPSED] = value }
    }

    companion object {
        const val DATA_STORE_NAME = "timer_monotonic_state"
        private val KEY_LAST_ELAPSED = longPreferencesKey("last_elapsed_realtime_ms")
    }
}
