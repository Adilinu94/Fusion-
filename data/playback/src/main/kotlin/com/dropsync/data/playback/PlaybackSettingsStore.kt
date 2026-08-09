package com.dropsync.data.playback

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.playbackSettingsDataStore by preferencesDataStore(name = "playback_settings")

/**
 * Nutzeroptionen der Wiedergabe (Plan Phase 4), getrennt vom
 * Wiederherstellungszustand in [PlayerStateStore].
 */
@Singleton
class PlaybackSettingsStore
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
    ) {
        private val resumeOnBluetoothKey = booleanPreferencesKey("resume_on_bluetooth_connect")

        /** Option "Bei BT-Verbindung automatisch fortsetzen" (Standard aus). */
        val resumeOnBluetoothConnect: Flow<Boolean> =
            context.playbackSettingsDataStore.data.map { prefs ->
                prefs[resumeOnBluetoothKey] ?: false
            }

        suspend fun setResumeOnBluetoothConnect(enabled: Boolean) {
            context.playbackSettingsDataStore.edit { prefs ->
                prefs[resumeOnBluetoothKey] = enabled
            }
        }
    }
