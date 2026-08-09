package com.dropsync.data.playback

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.dropsync.domain.playback.PersistedPlayerState
import com.dropsync.domain.playback.RepeatMode
import kotlinx.coroutines.flow.first

/**
 * Persistiert Queue, Shuffle, Repeat, letzten Song und Position nach
 * jeder relevanten Aenderung (Bauplan Schritt 5.5).
 */
interface PlayerStateStore {
    suspend fun read(): PersistedPlayerState?

    suspend fun write(state: PersistedPlayerState)
}

class DataStorePlayerStateStore(
    private val dataStore: DataStore<Preferences>,
) : PlayerStateStore {
    override suspend fun read(): PersistedPlayerState? {
        val prefs = dataStore.data.first()
        val queue = prefs[KEY_QUEUE] ?: return null
        return PersistedPlayerState(
            queueSongIds = decodeQueue(queue),
            currentSongId = prefs[KEY_CURRENT_SONG],
            positionMs = prefs[KEY_POSITION] ?: 0,
            shuffleEnabled = prefs[KEY_SHUFFLE] ?: false,
            repeatMode = decodeRepeatMode(prefs[KEY_REPEAT]),
        )
    }

    override suspend fun write(state: PersistedPlayerState) {
        dataStore.edit { prefs ->
            prefs[KEY_QUEUE] = encodeQueue(state.queueSongIds)
            val current = state.currentSongId
            if (current == null) {
                prefs.remove(KEY_CURRENT_SONG)
            } else {
                prefs[KEY_CURRENT_SONG] = current
            }
            prefs[KEY_POSITION] = state.positionMs
            prefs[KEY_SHUFFLE] = state.shuffleEnabled
            prefs[KEY_REPEAT] = state.repeatMode.name
        }
    }

    companion object {
        const val DATA_STORE_NAME = "player_restore_state"

        private val KEY_QUEUE = stringPreferencesKey("queue_song_ids")
        private val KEY_CURRENT_SONG = longPreferencesKey("current_song_id")
        private val KEY_POSITION = longPreferencesKey("position_ms")
        private val KEY_SHUFFLE = booleanPreferencesKey("shuffle_enabled")
        private val KEY_REPEAT = stringPreferencesKey("repeat_mode")

        /** Kommagetrennte IDs; stabil und ohne JSON-Abhaengigkeit. */
        fun encodeQueue(ids: List<Long>): String = ids.joinToString(",")

        fun decodeQueue(encoded: String): List<Long> =
            encoded
                .split(',')
                .mapNotNull { it.trim().toLongOrNull() }

        /** Unbekannte Werte fallen sicher auf OFF zurueck. */
        fun decodeRepeatMode(name: String?): RepeatMode =
            RepeatMode.entries.firstOrNull { it.name == name } ?: RepeatMode.OFF
    }
}
