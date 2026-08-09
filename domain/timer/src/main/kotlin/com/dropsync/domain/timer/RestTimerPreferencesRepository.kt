package com.dropsync.domain.timer

import kotlinx.coroutines.flow.Flow

/**
 * Nutzereinstellungen rund um den Resttimer (Musik-Workout-Plan B8/B9):
 * bearbeitbare Schnellwahl-Presets und der optionale Get-Ready-Vorlauf.
 * Reine Domainschnittstelle; die Persistenz (DataStore) liegt in
 * :data:timer.
 */
interface RestTimerPreferencesRepository {
    /** Bearbeitbare Schnellwahl in Sekunden (B8); Default 60/90/120/180. */
    val restPresetsSeconds: Flow<List<Int>>

    suspend fun setRestPresetsSeconds(seconds: List<Int>)

    /** Get-Ready-Vorbereitung an/aus (B9); Default aus. */
    val getReadyEnabled: Flow<Boolean>

    /** Dauer des Get-Ready-Vorlaufs in Sekunden (B9); Default 3. */
    val getReadySeconds: Flow<Int>

    suspend fun setGetReady(
        enabled: Boolean,
        seconds: Int,
    )

    companion object {
        /** Werkseinstellung der Rest-Presets (B8). */
        val DEFAULT_PRESETS_SECONDS: List<Int> = listOf(60, 90, 120, 180)

        /** Werkseinstellung des Get-Ready-Vorlaufs (B9). */
        const val DEFAULT_GET_READY_SECONDS: Int = 3

        /** Grenzen fuer den Vorlauf (sinnvoll klein, damit die Pause stimmt). */
        const val MIN_GET_READY_SECONDS: Int = 1
        const val MAX_GET_READY_SECONDS: Int = 10
    }
}
