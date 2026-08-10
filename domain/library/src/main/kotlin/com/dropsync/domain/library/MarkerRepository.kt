package com.dropsync.domain.library

import com.dropsync.core.common.AppResult
import com.dropsync.core.model.SongMarker
import kotlinx.coroutines.flow.Flow

/** Ergebnisbericht eines Imports (Bauplan 6.1, Schritt 6.4). */
data class ImportReport(
    val added: Int,
    val updated: Int,
    val unmatched: Int,
    val rejectedViolations: List<ImportViolation>,
) {
    val wasRejected: Boolean get() = rejectedViolations.isNotEmpty()
}

/**
 * Vertrag fuer Markerimport und -zuordnung (ADR-0003, Bauplan Schritt 6).
 * Implementierung in :data:library; der Import ist transaktional.
 */
interface MarkerRepository {
    /** Nicht zugeordnete Marker fuer die manuelle Zuordnung in Settings. */
    val unmatchedMarkers: Flow<List<SongMarker>>

    /**
     * Importiert ein validiertes Dokument. Ein ungueltiges Dokument
     * veraendert keine Tabelle; der Bericht nennt hinzugefuegte,
     * aktualisierte, nicht zugeordnete und abgelehnte Eintraege.
     */
    suspend fun importDocument(
        schemaVersion: Int,
        tracks: List<ImportedTrack>,
    ): AppResult<ImportReport>

    /** Manuelle, bestaetigte Zuordnung (Schritt 6.6); speichert den Link. */
    suspend fun linkManually(
        markerId: Long,
        songId: Long,
    ): AppResult<Unit>

    /** Aktive Marker eines Songs, aufsteigend nach Position. */
    suspend fun getEnabledMarkersForSong(songId: Long): AppResult<List<SongMarker>>

    /**
     * Legt einen manuellen Marker samt Zuordnung an (Marker/Waveform-Plan
     * Phase 4, "Tap-to-Mark"): SongMarker(source = MANUAL, isEnabled = true)
     * und Link (MANUAL) entstehen in einer Transaktion. Der Fingerprint
     * wird aus den bekannten Songfeldern abgeleitet (Pfad/Name/Groesse/
     * Dauer), keine neue Fingerprint-Logik.
     */
    suspend fun createManualMarker(
        songId: Long,
        label: String,
        positionMs: Long,
    ): AppResult<SongMarker>

    /** Loescht einen Marker; die Zuordnung faellt per Cascade mit. */
    suspend fun deleteMarker(markerId: Long): AppResult<Unit>

    /**
     * Unbestaetigte Onset-Kandidaten (Phase 5: source = AUTO_DETECTED,
     * isEnabled = false) fuer die Review-Liste in den Einstellungen —
     * dieselbe Interaktion wie bei [unmatchedMarkers]: Bestaetigen
     * aktiviert, Verwerfen loescht ([deleteMarker]).
     */
    val pendingAutoDetectedMarkers: Flow<List<SongMarker>>

    /** Bestaetigt einen Kandidaten: setzt isEnabled = true. */
    suspend fun confirmMarker(markerId: Long): AppResult<Unit>

    /**
     * Verschiebt einen Marker an eine neue Position (Marker/Waveform-Plan
     * Phase 4, "Drag"): Label und isEnabled bleiben, nur die Position
     * aendert sich; wirkt sofort auf die naechste Drop-Landung.
     */
    suspend fun moveMarker(
        markerId: Long,
        newPositionMs: Long,
    ): AppResult<Unit>
}
