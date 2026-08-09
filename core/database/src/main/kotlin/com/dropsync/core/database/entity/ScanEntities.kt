package com.dropsync.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Virtueller Track aus einem CUE-Sheet (Plan Phase 3): referenziert den
 * Song der grossen Audiodatei; Wiedergabe erfolgt ueber
 * ClippingConfiguration(startMs, endMs). endMs null = bis Dateiende.
 */
@Entity(
    tableName = "cue_tracks",
    foreignKeys = [
        ForeignKey(
            entity = SongEntity::class,
            parentColumns = ["media_store_id"],
            childColumns = ["song_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["song_id", "track_number"], unique = true)],
)
data class CueTrackEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0,
    @ColumnInfo(name = "song_id")
    val songId: Long,
    @ColumnInfo(name = "track_number")
    val trackNumber: Int,
    @ColumnInfo(name = "title")
    val title: String?,
    @ColumnInfo(name = "performer")
    val performer: String?,
    @ColumnInfo(name = "start_ms")
    val startMs: Long,
    @ColumnInfo(name = "end_ms")
    val endMs: Long?,
)

/**
 * Datei aus dem SAF-Ordnerscan (Plan Phase 3): Formate, die der
 * MediaStore nicht indexiert (APE, TAK, TTA, DSF, DFF, ...), sowie
 * CUE-Sheets und Playlisten. Je Scan-Baum wird vollstaendig ersetzt.
 */
@Entity(
    tableName = "saf_files",
    indices = [
        Index(value = ["document_uri"], unique = true),
        Index(value = ["tree_uri"]),
    ],
)
data class SafFileEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0,
    /** SAF-Baum (persistierte Ordnerfreigabe), zu dem die Datei gehoert. */
    @ColumnInfo(name = "tree_uri")
    val treeUri: String,
    @ColumnInfo(name = "document_uri")
    val documentUri: String,
    @ColumnInfo(name = "display_name")
    val displayName: String,
    /** Pfad relativ zum Scan-Baum (Slash-getrennt, ohne Dateinamen). */
    @ColumnInfo(name = "relative_path")
    val relativePath: String,
    @ColumnInfo(name = "size_bytes")
    val sizeBytes: Long,
    @ColumnInfo(name = "last_modified_ms")
    val lastModifiedMs: Long,
    /** Stabiler String aus ScannedFileKind (AUDIO, CUE, PLAYLIST). */
    @ColumnInfo(name = "kind")
    val kind: String,
    /** Stabiler String aus AudioFileFormat; nur bei kind = AUDIO. */
    @ColumnInfo(name = "format")
    val format: String?,
)
