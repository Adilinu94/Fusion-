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

    /**
     * Build-up vor dem Drop (experimentell): Liegt der Drop hinter dem Pausenende, steigt der
     * Titel [DropLandingPlanner.DEFAULT_LEAD_IN_MS] vor dem Drop ein, der Drop faellt weiter aufs
     * Pausenende. **Default aus** - ob der Wechsel mitten in der Pause besser klingt als der
     * Direktsprung zum Go, laesst sich nur hoeren.
     */
    val dropLeadInEnabled: Flow<Boolean>

    /** Schaltet den Build-up dauerhaft ein oder aus. */
    suspend fun setDropLeadInEnabled(enabled: Boolean)

    companion object {
        /** Produktabsicht: Drop-Auto ist standardmaessig aktiv. */
        const val DEFAULT_DROP_AUTO_ENABLED: Boolean = true

        /** Build-up ist standardmaessig aus (Hoertest steht aus). */
        const val DEFAULT_DROP_LEAD_IN_ENABLED: Boolean = false
    }
}
