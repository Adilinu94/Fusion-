package com.dropsync.data.playback

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.dropsync.domain.playback.PersistedPlayerState
import com.dropsync.domain.playback.PersistedQueueEntry
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
            queueEntries = decodeQueue(queue),
            currentSongId = prefs[KEY_CURRENT_SONG],
            positionMs = prefs[KEY_POSITION] ?: 0,
            shuffleEnabled = prefs[KEY_SHUFFLE] ?: false,
            repeatMode = decodeRepeatMode(prefs[KEY_REPEAT]),
        )
    }

    override suspend fun write(state: PersistedPlayerState) {
        dataStore.edit { prefs ->
            prefs[KEY_QUEUE] = encodeQueue(state.queueEntries)
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

        /**
         * Trennzeichen fuer [encodeQueue]. Kommas und das Trennzeichen
         * selbst kommen in einer mediaId nicht vor: reguläre Items sind
         * reine Ziffernfolgen, CUE-IDs sind `cue:<id>:<nr>`. Damit ist das
         * Format eindeutig parsebar und braucht kein JSON.
         */
        private const val SEP = '\u001F'

        /** Legacy-Trenner der ersten Fassung (nur noch lesend). */
        private const val LEGACY_SEP = ','

        /**
         * Kodiert die Queue als `<mediaId>[<SEP><songId>]` je Eintrag.
         *
         * 2026-09-27: Vorher wurden nur `Long`-IDs kodiert, wodurch
         * CUE-Tracks (`cue:<songId>:<nr>`) verloren gingen. [songId] wird
         * nur geschrieben, wenn es von der mediaId abweicht — reguläre
         * Eintraege sind dadurch genauso kurz wie vorher.
         */
        fun encodeQueue(entries: List<PersistedQueueEntry>): String =
            entries.joinToString(SEP.toString()) { entry ->
                val derived = songIdOf(entry.mediaId)
                if (entry.songId == null || entry.songId == derived) {
                    entry.mediaId
                } else {
                    "${entry.mediaId}$SEP${entry.songId}"
                }
            }

        /**
         * Dekodiert das Queue-Format. Akzeptiert sowohl das aktuelle
         * Format als auch die Legacy-Liste kommagetrennter Song-IDs, damit
         * ein nach dem Update noch vorhandener Zustand nicht verloren geht.
         */
        fun decodeQueue(encoded: String): List<PersistedQueueEntry> {
            if (encoded.contains(SEP)) {
                return encoded
                    .split(SEP)
                    .mapNotNull { raw ->
                        if (raw.isEmpty()) {
                            null
                        } else {
                            val parts = raw.split(SEP.toString(), limit = 2)
                            if (parts.size == 2) {
                                PersistedQueueEntry(parts[0], parts[1].toLongOrNull())
                            } else {
                                PersistedQueueEntry(parts[0], songIdOf(parts[0]))
                            }
                        }
                    }
            }
            // Legacy: reine Song-IDs, kommagetrennt.
            return encoded
                .split(LEGACY_SEP)
                .mapNotNull { it.trim().toLongOrNull() }
                .map { PersistedQueueEntry(it.toString(), it) }
        }

        /**
         * Leitet die Song-ID aus einer mediaId ab. Fuer CUE-Tracks ist das
         * die ID der zugrunde liegenden Datei (`cue:123:1` -> 123), damit
         * Play-Stats und Marker-Zuordnung weiterhin funktionieren.
         */
        fun songIdOf(mediaId: String): Long? {
            mediaId.toLongOrNull()?.let { return it }
            if (mediaId.startsWith(MediaItemFactory.CUE_MEDIA_ID_PREFIX)) {
                return mediaId
                    .removePrefix(MediaItemFactory.CUE_MEDIA_ID_PREFIX)
                    .substringBefore(':')
                    .toLongOrNull()
            }
            return null
        }

        /** Unbekannte Werte fallen sicher auf OFF zurueck. */
        fun decodeRepeatMode(name: String?): RepeatMode =
            RepeatMode.entries.firstOrNull { it.name == name } ?: RepeatMode.OFF
    }
}
