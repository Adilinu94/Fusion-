package com.dropsync.domain.library

/**
 * Persistierter virtueller Track aus einem importierten CUE-Sheet
 * (Plan Phase 3). Referenziert den Song der grossen Audiodatei;
 * [endMs] null = bis Dateiende.
 */
data class CueVirtualTrack(
    val id: Long,
    val songId: Long,
    val trackNumber: Int,
    val title: String?,
    val performer: String?,
    val startMs: Long,
    val endMs: Long?,
)

/** Art einer Datei aus dem SAF-Ordnerscan. */
enum class ScannedFileKind {
    AUDIO,
    CUE,
    PLAYLIST,
}

/**
 * Datei aus dem SAF-Ordnerscan (Plan Phase 3): Formate, die der
 * MediaStore nicht indexiert, plus CUE-Sheets und Playlisten.
 */
data class ScannedFile(
    val id: Long,
    val treeUri: String,
    val documentUri: String,
    val displayName: String,
    val relativePath: String,
    val sizeBytes: Long,
    val lastModifiedMs: Long,
    val kind: ScannedFileKind,
    val format: AudioFileFormat?,
)

/** Ergebnis eines SAF-Ordnerscans ("Ordner scannen"). */
data class FolderScanResult(
    val audioFiles: Int,
    val cueSheets: Int,
    val playlists: Int,
    /** Ueber gefundene CUE-Sheets automatisch importierte virtuelle Tracks. */
    val importedCueTracks: Int,
)
