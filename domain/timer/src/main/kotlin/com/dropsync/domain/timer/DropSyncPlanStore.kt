package com.dropsync.domain.timer

/**
 * Art der DropSync-Sitzung, die einen App-/Service-Kill nicht ueberlebt hat
 * (C13, Entscheidungen 5.8/5.9).
 */
enum class DropSyncPlanKind {
    /** Drop-Auto/geplante Landung einer REST-Pause: wird neu geplant, wenn moeglich. */
    AUTO_LANDING,

    /** Manueller DropRest: wird nicht wiederhergestellt, aber sichtbar gemeldet. */
    MANUAL_DROP_REST,
}

/**
 * Marker einer aktiven DropSync-Sitzung (C13). Die Sitzung selbst ist
 * bewusst nicht persistierbar (die Zeitreferenz ist die Player-Session);
 * der Marker macht sie nach einem Kill wenigstens erkennbar.
 */
data class DropSyncPlanMarker(
    val kind: DropSyncPlanKind,
    val sessionId: String,
)

/**
 * Persistenz-Port fuer den C13-Marker: ueberlebt den Kill, damit der
 * Koordinator beim Start zwischen frischer und rekonstruierter Sitzung
 * unterscheiden kann. Implementiert in `:data:timer` (DataStore).
 */
interface DropSyncPlanStore {
    /** Zuletzt gespeicherter Marker oder `null`. */
    suspend fun load(): DropSyncPlanMarker?

    /** Persistiert [marker] (ueberschreibt den vorherigen). */
    suspend fun save(marker: DropSyncPlanMarker)

    /** Loescht den Marker (Sitzung beendet, Plan quittiert). */
    suspend fun clear()
}
