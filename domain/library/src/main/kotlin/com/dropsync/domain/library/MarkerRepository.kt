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
     * D5/A7: aktive Marker fuer mehrere Songs in EINER Abfrage — die
     * DropSync-Planung lud vorher je Work-Titel eine eigene Query (N+1).
     * Rueckgabe je Song-ID, aufsteigend nach Position; Songs ohne aktive
     * Marker fehlen in der Map.
     */
    suspend fun getEnabledMarkersForSongs(songIds: List<Long>): AppResult<Map<Long, List<SongMarker>>>

    /**
     * D5/A8: aktive Marker eines Songs als Flow — das Drop-Rest-Gate
     * beobachtet Marker-Aenderungen (Room invalidiert), statt sie im
     * 500-ms-Takt neu zu laden. Die erste Emission ist der aktuelle Stand.
     */
    fun observeEnabledMarkersForSong(songId: Long): Flow<List<SongMarker>>

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
     * isEnabled = false) fuer die Review-Liste im Music-Bereich —
     * dieselbe Interaktion wie bei [unmatchedMarkers]: Bestaetigen
     * aktiviert, Verwerfen loescht ([deleteMarker]).
     */
    val pendingAutoDetectedMarkers: Flow<List<SongMarker>>

    /** Bestaetigt einen Kandidaten: setzt isEnabled = true. */
    suspend fun confirmMarker(markerId: Long): AppResult<Unit>

    /**
     * C4: Songs mit mindestens einem aktiven Marker. Grundlage der
     * Drop-Abdeckung auf Music Home/Playlist ("9/12 Drops"); eine
     * Gesamtabfrage statt einer Zaehlung je Titel.
     */
    val songsWithEnabledMarkers: Flow<Set<Long>>

    /**
     * C4 (U-3): setzt isEnabled eines Markers direkt — das Rueckgaengig
     * des Bestaetigens (zurueck in die Review-Liste).
     */
    suspend fun setMarkerEnabled(
        markerId: Long,
        enabled: Boolean,
    ): AppResult<Unit>

    /**
     * C4 (U-3): stellt einen verworfenen Marker wieder her (neue ID,
     * gleiche Quelle/Label/Position/Zuordnung). Das Rueckgaengig des
     * Verwerfens; ohne Linkzeile bleibt der Marker "nicht zugeordnet".
     */
    suspend fun restoreMarker(marker: SongMarker): AppResult<Unit>

    /**
     * Verschiebt einen Marker an eine neue Position (Marker/Waveform-Plan
     * Phase 4, "Drag"): Label und isEnabled bleiben, nur die Position
     * aendert sich; wirkt sofort auf die naechste Drop-Landung.
     */
    suspend fun moveMarker(
        markerId: Long,
        newPositionMs: Long,
    ): AppResult<Unit>

    /**
     * Benennt einen Marker um (P2-21, Marker-Sheet im Now-Playing):
     * Position, Quelle und isEnabled bleiben; nur das Label aendert sich.
     * Ein leeres Label wird abgelehnt (der Aufrufer faellt auf den alten
     * Wert zurueck).
     */
    suspend fun renameMarker(
        markerId: Long,
        newLabel: String,
    ): AppResult<Unit>
}
