package com.dropsync.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Lokaler Song aus MediaStore (Bauplan 5.1, Abschnitt 6).
 * Primaerschluessel ist die MediaStore-ID; Dateipfade sind nie Schluessel.
 */
@Entity(tableName = "songs")
data class SongEntity(
    @PrimaryKey
    @ColumnInfo(name = "media_store_id")
    val mediaStoreId: Long,
    @ColumnInfo(name = "content_uri")
    val contentUri: String,
    @ColumnInfo(name = "display_name")
    val displayName: String,
    @ColumnInfo(name = "relative_path")
    val relativePath: String,
    @ColumnInfo(name = "duration_ms")
    val durationMs: Long,
    @ColumnInfo(name = "size_bytes")
    val sizeBytes: Long,
    @ColumnInfo(name = "date_modified_seconds")
    val dateModifiedSeconds: Long,
    @ColumnInfo(name = "title")
    val title: String?,
    @ColumnInfo(name = "artist")
    val artist: String?,
    @ColumnInfo(name = "album")
    val album: String?,
    @ColumnInfo(name = "genre")
    val genre: String? = null,
    @ColumnInfo(name = "is_available")
    val isAvailable: Boolean,
    /**
     * SHA-256 ausschliesslich aus dem externen Analyzer-Import (Abschnitt 2);
     * die App berechnet nie selbst einen Hash. Grundlage der Stufe-1-Zuordnung.
     */
    @ColumnInfo(name = "known_sha256")
    val knownSha256: String? = null,
)

/**
 * Importierter oder manueller Songmarker (Abschnitt 6).
 * Die Zuordnung zu einem Song liegt ausschliesslich in [MarkerSongLinkEntity].
 */
@Entity(tableName = "song_markers")
data class SongMarkerEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0,
    /** Fachliche Identitaet des Import-Tracks (Pfad, Name, Groesse, Dauer). */
    @ColumnInfo(name = "source_fingerprint")
    val sourceFingerprint: String,
    @ColumnInfo(name = "label")
    val label: String,
    @ColumnInfo(name = "position_ms")
    val positionMs: Long,
    /** Stabiler String aus MarkerSource (IMPORT, MANUAL). */
    @ColumnInfo(name = "source")
    val source: String,
    @ColumnInfo(name = "is_enabled")
    val isEnabled: Boolean,
    @ColumnInfo(name = "created_at_epoch_ms")
    val createdAtEpochMs: Long,
)

/**
 * Einzige Quelle der Marker-zu-Song-Zuordnung (Bauplan 5.1, Abschnitt 6).
 * Ein nicht zugeordneter Marker hat schlicht keine Linkzeile.
 */
@Entity(
    tableName = "marker_song_links",
    foreignKeys = [
        ForeignKey(
            entity = SongMarkerEntity::class,
            parentColumns = ["id"],
            childColumns = ["marker_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = SongEntity::class,
            parentColumns = ["media_store_id"],
            childColumns = ["song_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["marker_id"], unique = true),
        Index(value = ["song_id"]),
    ],
)
data class MarkerSongLinkEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0,
    @ColumnInfo(name = "marker_id")
    val markerId: Long,
    @ColumnInfo(name = "song_id")
    val songId: Long,
    /** Stabiler String aus LinkMethod (HASH, METADATA, MANUAL). */
    @ColumnInfo(name = "link_method")
    val linkMethod: String,
    @ColumnInfo(name = "linked_at_epoch_ms")
    val linkedAtEpochMs: Long,
)

/**
 * Preset nur fuer normalen Timer und Resttimer (Abschnitt 6);
 * DropSync leitet seine Dauer immer aus Marker und Playerposition ab.
 */
@Entity(
    tableName = "timer_presets",
    indices = [Index(value = ["name"], unique = true)],
)
data class TimerPresetEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0,
    @ColumnInfo(name = "name")
    val name: String,
    @ColumnInfo(name = "duration_ms")
    val durationMs: Long,
    /** Nur 0, 50 oder 100 (Schritt 8.4); Validierung im Repository. */
    @ColumnInfo(name = "ducking_percent")
    val duckingPercent: Int,
    @ColumnInfo(name = "tts_enabled")
    val ttsEnabled: Boolean,
    @ColumnInfo(name = "haptics_enabled")
    val hapticsEnabled: Boolean,
    @ColumnInfo(name = "completion_tone_enabled")
    val completionToneEnabled: Boolean,
)
