package com.dropsync.data.playback

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.dropsync.core.model.RestMusicBehavior
import com.dropsync.domain.playback.RestMusicSettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.restMusicSettingsDataStore by preferencesDataStore(name = "rest_music_settings")

/**
 * DataStore-Persistenz des Pausen-Musik-Verhaltens (Musik-Workout-Plan
 * Phase 3). Als stabiler Enum-String gespeichert; unbekannte/fehlende
 * Werte fallen auf [RestMusicBehavior.NORMAL] (Aus) zurueck.
 */
class RestMusicSettingsStore(
    private val context: Context,
) : RestMusicSettingsRepository {
    override val behavior: Flow<RestMusicBehavior> =
        context.restMusicSettingsDataStore.data.map { prefs ->
            prefs[KEY_BEHAVIOR]
                ?.let { runCatching { RestMusicBehavior.valueOf(it) }.getOrNull() }
                ?: RestMusicBehavior.NORMAL
        }

    override suspend fun setBehavior(behavior: RestMusicBehavior) {
        context.restMusicSettingsDataStore.edit { prefs ->
            prefs[KEY_BEHAVIOR] = behavior.name
        }
    }

    companion object {
        private val KEY_BEHAVIOR = stringPreferencesKey("rest_music_behavior")
    }
}
