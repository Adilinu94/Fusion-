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

    /**
     * Drop-Auto je Pause (MP-13, Design 5.2): Musik landet am Pausenende,
     * wenn ein Work-Titel mit brauchbarem Drop bereitsteht. Persistiert,
     * **Default an** — der Schalter im Train-Tab beschriftet das pro Pause.
     */
    val dropAutoEnabled: Flow<Boolean>

    /** Setzt den Drop-Auto-Schalter dauerhaft. */
    suspend fun setDropAutoEnabled(enabled: Boolean)

    companion object {
        /** Produktabsicht: Drop-Auto ist standardmaessig aktiv. */
        const val DEFAULT_DROP_AUTO_ENABLED: Boolean = true
    }
}
