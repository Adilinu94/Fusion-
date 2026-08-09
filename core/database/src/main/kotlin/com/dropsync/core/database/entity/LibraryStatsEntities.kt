package com.dropsync.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Fts4
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Wiedergabestatistik je Song (Plan Phase 6). Getrennt von der
 * songs-Tabelle, damit ein Rescan die Zaehler nie zuruecksetzt.
 */
@Entity(
    tableName = "play_stats",
    foreignKeys = [
        ForeignKey(
            entity = SongEntity::class,
            parentColumns = ["media_store_id"],
            childColumns = ["song_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class PlayStatEntity(
    @PrimaryKey
    @ColumnInfo(name = "song_id")
    val songId: Long,
    @ColumnInfo(name = "play_count")
    val playCount: Int,
    @ColumnInfo(name = "last_played_at_epoch_ms")
    val lastPlayedAtEpochMs: Long?,
)

/**
 * Favoritenmarkierung (Plan Phase 6). Reine Zuordnung ueber die
 * MediaStore-ID; das Vorhandensein der Zeile bedeutet "favorisiert".
 */
@Entity(
    tableName = "favorites",
    foreignKeys = [
        ForeignKey(
            entity = SongEntity::class,
            parentColumns = ["media_store_id"],
            childColumns = ["song_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class FavoriteEntity(
    @PrimaryKey
    @ColumnInfo(name = "song_id")
    val songId: Long,
    @ColumnInfo(name = "created_at_epoch_ms")
    val createdAtEpochMs: Long,
)

/** Nutzerplaylist (Plan Phase 6); auch Ziel des M3U-Imports. */
@Entity(
    tableName = "playlists",
    indices = [Index(value = ["name"], unique = true)],
)
data class PlaylistEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0,
    @ColumnInfo(name = "name")
    val name: String,
    @ColumnInfo(name = "created_at_epoch_ms")
    val createdAtEpochMs: Long,
    @ColumnInfo(name = "label")
    val label: String? = null,
)

/**
 * Playlisteneintrag (Plan Phase 6). [position] ist die 0-basierte
 * Reihenfolge innerhalb der Playlist; das Repository haelt sie luecken-
 * frei. Songs sind ueber die MediaStore-ID referenziert.
 */
@Entity(
    tableName = "playlist_items",
    foreignKeys = [
        ForeignKey(
            entity = PlaylistEntity::class,
            parentColumns = ["id"],
            childColumns = ["playlist_id"],
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
        Index(value = ["playlist_id"]),
        Index(value = ["song_id"]),
    ],
)
data class PlaylistItemEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0,
    @ColumnInfo(name = "playlist_id")
    val playlistId: Long,
    @ColumnInfo(name = "song_id")
    val songId: Long,
    @ColumnInfo(name = "position")
    val position: Int,
)

/**
 * Volltextindex fuer die Suche (Plan Phase 6). Externe Content-Tabelle
 * ueber [SongEntity]; nach jedem Scan mit `rebuild` neu aufgebaut. Die
 * rowid entspricht der media_store_id der songs-Tabelle.
 */
@Fts4(contentEntity = SongEntity::class)
@Entity(tableName = "song_fts")
data class SongFtsEntity(
    @ColumnInfo(name = "title")
    val title: String?,
    @ColumnInfo(name = "artist")
    val artist: String?,
    @ColumnInfo(name = "album")
    val album: String?,
    @ColumnInfo(name = "display_name")
    val displayName: String,
)
