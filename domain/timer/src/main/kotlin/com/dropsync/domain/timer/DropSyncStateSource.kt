package com.dropsync.domain.timer

import kotlinx.coroutines.flow.StateFlow

/**
 * App-weiter Lesezugriff auf den einen DropSync-Zustand (P1-10,
 * Design 4.5): Der Train-Tab zeigt "DropSync · Track · Ziel in mm:ss",
 * der Mini-Player ein Badge, ohne das Player-Feature zu importieren.
 *
 * Implementiert vom `DropSyncCoordinator` in :feature:player; die
 * Bindung liegt in dessen Hilt-Modul.
 */
interface DropSyncStateSource {
    /** Aktueller Zustand; [DropSyncState.Off], solange kein Plan laeuft. */
    val state: StateFlow<DropSyncState>

    /**
     * P1-10: Bricht den laufenden Plan ab (Konsole: "Plan abbrechen").
     * Die Pause laeuft weiter; Queue und Ducking werden zurueckgenommen.
     * Ohne aktiven Plan ein No-op.
     */
    fun cancelPlan()

    /**
     * C13: Quittiert die einmalige Meldung "Plan verloren"
     * ([DropSyncFailureReason.PLAN_LOST]) und blendet sie aus. Ohne
     * PLAN_LOST ein No-op.
     */
    fun acknowledgePlanLost()

    /**
     * C2 (5.10): Nimmt einen Skip-Override zurueck (Undo): derselbe Plan
     * wird neu geplant, ohne Queue oder Titel anzufassen. Liefert `false`,
     * wenn kein Override offen ist, keine laufende REST-Sitzung existiert
     * oder die Restzeit unter dem Planungs-Minimum liegt (No-op).
     */
    fun replanAfterOverride(): Boolean
}
