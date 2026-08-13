package com.dropsync.domain.playback

/**
 * Port fuer das Latenzprofil der aktuellen Audio-Route (Design Phase 6).
 * Implementierung in :data:playback (DataStore + Latenz-Tabellen,
 * Abschnitt 10 des Design-Dokuments).
 */
interface RouteProfileRepository {
    /** Profil fuer die aktuelle Route; null = keine Quelle (UNAVAILABLE). */
    val currentProfile: kotlinx.coroutines.flow.Flow<AudioRouteProfile?>

    /**
     * Latenzabschaetzung der aktuellen Route in ms. Ohne Profil (null)
     * meldet der Aufrufer BEST_EFFORT/UNAVAILABLE und rechnet mit 0.
     */
    suspend fun currentLatencyMs(): Long?

    /** Markiert das aktuelle Profil als unsicher (Route-/Fokuswechsel). */
    suspend fun markStale()

    /** Speichert/verfeinert ein Profil (z. B. nach lokaler Messung). */
    suspend fun upsert(profile: AudioRouteProfile)
}
