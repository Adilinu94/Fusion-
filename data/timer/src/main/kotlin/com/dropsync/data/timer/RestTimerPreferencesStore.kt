package com.dropsync.data.timer

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.dropsync.domain.timer.RestTimerPreferencesRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.restTimerPrefsDataStore by preferencesDataStore(name = "rest_timer_prefs")

/**
 * DataStore-Persistenz der Resttimer-Einstellungen (Musik-Workout-Plan
 * B8/B9): Presets als komma-getrennte Sekundenliste, Get-Ready als
 * Schalter + Dauer. Defaults liefern 60/90/120/180 s und Vorlauf aus.
 */
class RestTimerPreferencesStore(
    private val context: Context,
) : RestTimerPreferencesRepository {
    private val presetsKey = stringPreferencesKey("rest_presets_seconds")
    private val getReadyEnabledKey = booleanPreferencesKey("get_ready_enabled")
    private val getReadySecondsKey = intPreferencesKey("get_ready_seconds")

    override val restPresetsSeconds: Flow<List<Int>> =
        context.restTimerPrefsDataStore.data.map { prefs ->
            prefs[presetsKey]?.let(::parsePresets)
                ?: RestTimerPreferencesRepository.DEFAULT_PRESETS_SECONDS
        }

    override suspend fun setRestPresetsSeconds(seconds: List<Int>) {
        val cleaned = seconds.filter { it > 0 }.distinct().sorted()
        context.restTimerPrefsDataStore.edit { prefs ->
            prefs[presetsKey] = cleaned.joinToString(",")
        }
    }

    override val getReadyEnabled: Flow<Boolean> =
        context.restTimerPrefsDataStore.data.map { prefs ->
            prefs[getReadyEnabledKey] ?: false
        }

    override val getReadySeconds: Flow<Int> =
        context.restTimerPrefsDataStore.data.map { prefs ->
            prefs[getReadySecondsKey] ?: RestTimerPreferencesRepository.DEFAULT_GET_READY_SECONDS
        }

    override suspend fun setGetReady(
        enabled: Boolean,
        seconds: Int,
    ) {
        val clamped =
            seconds.coerceIn(
                RestTimerPreferencesRepository.MIN_GET_READY_SECONDS,
                RestTimerPreferencesRepository.MAX_GET_READY_SECONDS,
            )
        context.restTimerPrefsDataStore.edit { prefs ->
            prefs[getReadyEnabledKey] = enabled
            prefs[getReadySecondsKey] = clamped
        }
    }

    private fun parsePresets(raw: String): List<Int> =
        raw
            .split(",")
            .mapNotNull { it.trim().toIntOrNull() }
            .filter { it > 0 }
            .ifEmpty { RestTimerPreferencesRepository.DEFAULT_PRESETS_SECONDS }
}
