package com.dropsync.domain.playback

import com.dropsync.core.model.RestMusicBehavior
import kotlinx.coroutines.flow.Flow

/**
 * Nutzereinstellung, wie sich die Musik in Trainingspausen verhaelt
 * (Musik-Workout-Plan Phase 3). Default ist [RestMusicBehavior.NORMAL] —
 * die App greift nicht ein, Shuffle/Queue laeuft unveraendert weiter.
 */
interface RestMusicSettingsRepository {
    /** Aktuelles Verhalten; Default [RestMusicBehavior.NORMAL]. */
    val behavior: Flow<RestMusicBehavior>

    /** Setzt das Verhalten (in den Einstellungen waehlbar). */
    suspend fun setBehavior(behavior: RestMusicBehavior)
}
