package com.dropsync.domain.library

import com.dropsync.core.common.AppResult
import com.dropsync.core.model.Song
import kotlinx.coroutines.flow.Flow

/**
 * Ergebnis eines Bibliotheksabgleichs (Bauplan Schritt 4).
 * [skippedBecauseUnchanged] ist true, wenn der MediaStore-Aenderungsstand
 * unveraendert war und deshalb kein Vollscan lief (Schritt 4.3).
 */
data class LibraryScanResult(
    val skippedBecauseUnchanged: Boolean,
    val totalSongs: Int,
    val newOrUpdatedSongs: Int,
    val markedUnavailable: Int,
)

/**
 * Vertrag der lokalen Musikbibliothek (ADR-0003).
 * Implementierung liegt in :data:library (MediaStore + Room).
 */
interface LibraryRepository {
    /** Alle bekannten Songs, auch nicht verfuegbare (fuer Historie/Marker). */
    val songs: Flow<List<Song>>

    /** Nur abspielbare Songs. */
    val availableSongs: Flow<List<Song>>

    /**
     * Gleicht die Bibliothek mit MediaStore ab. Ohne [force] wird der
     * Abgleich uebersprungen, wenn sich der MediaStore-Stand nicht
     * geaendert hat. Fehlende Berechtigung liefert
     * AppError.PermissionDenied; es gibt keinen stillen leeren Screen.
     */
    suspend fun refreshLibrary(force: Boolean): AppResult<LibraryScanResult>

    suspend fun getSong(mediaStoreId: Long): AppResult<Song>

    /** Markiert einen Song mit nicht mehr lesbarer URI (Schritt 4.4). */
    suspend fun markUnavailable(mediaStoreId: Long): AppResult<Unit>

    /**
     * Importiert ein CUE-Sheet fuer [songId] (Plan Phase 3): ersetzt alle
     * bisherigen virtuellen Tracks des Songs transaktional. Liefert die
     * Anzahl importierter Tracks; strukturell defekte Sheets schlagen fehl.
     */
    suspend fun importCueSheet(
        songId: Long,
        cueText: String,
    ): AppResult<Int>

    /** Virtuelle CUE-Tracks eines Songs in Tracknummern-Reihenfolge. */
    fun observeCueTracks(songId: Long): Flow<List<CueVirtualTrack>>

    /**
     * SAF-Ordnerscan ("Ordner scannen", Plan Phase 3): indexiert Formate,
     * die MediaStore nicht kennt (APE, TAK, TTA, DSF, DFF, ...), sowie
     * CUE-Sheets und Playlisten unterhalb von [treeUri]. Gefundene
     * CUE-Sheets werden automatisch passenden Songs zugeordnet und
     * importiert. Ein Rescan ersetzt den Baum vollstaendig.
     */
    suspend fun scanFolder(treeUri: String): AppResult<FolderScanResult>

    /** Alle per Ordnerscan indexierten Dateien. */
    val scannedFiles: Flow<List<ScannedFile>>
}
