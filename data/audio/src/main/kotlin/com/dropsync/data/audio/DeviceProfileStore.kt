package com.dropsync.data.audio

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.dropsync.domain.audio.DspConfig
import com.dropsync.domain.audio.DspConfigCodec
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

private val Context.deviceProfileDataStore by preferencesDataStore(name = "audio_device_profiles")

/**
 * Persistenz der Pro-Ausgang-Profile (ADR-0008): je Geraeteschluessel
 * (OutputProfileKey.storageKey) eine komplette DspConfig als String
 * (DspConfigCodec). Defekte Eintraege liefern null und werden beim
 * naechsten Schreiben ueberschrieben.
 */
@Singleton
class DeviceProfileStore
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
    ) {
        suspend fun read(storageKey: String): DspConfig? {
            val prefs = context.deviceProfileDataStore.data.first()
            val encoded = prefs[stringPreferencesKey(storageKey)] ?: return null
            return DspConfigCodec.decode(encoded)
        }

        suspend fun write(
            storageKey: String,
            config: DspConfig,
        ) {
            context.deviceProfileDataStore.edit { prefs ->
                prefs[stringPreferencesKey(storageKey)] = DspConfigCodec.encode(config)
            }
        }

        suspend fun delete(storageKey: String) {
            context.deviceProfileDataStore.edit { prefs ->
                prefs.remove(stringPreferencesKey(storageKey))
            }
        }
    }
