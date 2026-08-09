package com.dropsync.core.database.dao

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.dropsync.core.database.entity.FavoriteEntity
import com.dropsync.core.database.entity.PlayStatEntity
import com.dropsync.core.database.entity.PlaylistEntity
import com.dropsync.core.database.entity.PlaylistItemEntity
import com.dropsync.core.database.entity.SongEntity
import kotlinx.coroutines.flow.Flow

/** Aggregierte Albumzeile (Plan Phase 6, Ansicht "Alben"). */
data class AlbumRow(
    @ColumnInfo(name = "album") val album: String,
    @ColumnInfo(name = "artist") val artist: String?,
    @ColumnInfo(name = "track_count") val trackCount: Int,
    @ColumnInfo(name = "total_duration_ms") val totalDurationMs: Long = 0,
)

/** Aggregierte Kuenstlerzeile (Ansicht "Kuenstler"). */
data class ArtistRow(
    @ColumnInfo(name = "artist") val artist: String,
    @ColumnInfo(name = "track_count") val trackCount: Int,
    @ColumnInfo(name = "album_count") val albumCount: Int,
    @ColumnInfo(name = "total_duration_ms") val totalDurationMs: Long = 0,
)

/** Aggregierte Genrezeile (Ansicht "Genres"). */
data class GenreRow(
    @ColumnInfo(name = "genre") val genre: String,
    @ColumnInfo(name = "track_count") val trackCount: Int,
    @ColumnInfo(name = "total_duration_ms") val totalDurationMs: Long = 0,
)

/** Aggregierte Ordnerzeile aus relative_path (Ordneransicht). */
data class FolderRow(
    @ColumnInfo(name = "relative_path") val relativePath: String,
    @ColumnInfo(name = "track_count") val trackCount: Int,
    @ColumnInfo(name = "total_duration_ms") val totalDurationMs: Long = 0,
)

/** Playlist samt Eintragszahl (Ansicht "Playlisten"). */
data class PlaylistRow(
    @ColumnInfo(name = "id") val id: Long,
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "label") val label: String?,
    @ColumnInfo(name = "track_count") val trackCount: Int,
)

/**
 * Lesende Bibliotheksansichten (Plan Phase 6). Alben/Kuenstler/Genres
 * werden aus der songs-Tabelle aggregiert (aus MediaStore normalisiert);
 * die Ordneransicht nutzt relative_path. Alle Ansichten zeigen nur
 * verfuegbare Songs.
 */
@Dao
interface LibraryBrowseDao {
    @Query(
        "SELECT album AS album, MAX(artist) AS artist, COUNT(*) AS track_count, " +
            "SUM(duration_ms) AS total_duration_ms FROM songs " +
            "WHERE is_available = 1 AND album IS NOT NULL AND album != '' " +
            "GROUP BY album ORDER BY album COLLATE NOCASE",
    )
    fun observeAlbums(): Flow<List<AlbumRow>>

    @Query(
        "SELECT artist AS artist, COUNT(*) AS track_count, COUNT(DISTINCT album) AS album_count, " +
            "SUM(duration_ms) AS total_duration_ms " +
            "FROM songs WHERE is_available = 1 AND artist IS NOT NULL AND artist != '' " +
            "GROUP BY artist ORDER BY artist COLLATE NOCASE",
    )
    fun observeArtists(): Flow<List<ArtistRow>>

    @Query(
        "SELECT genre AS genre, COUNT(*) AS track_count, SUM(duration_ms) AS total_duration_ms FROM songs " +
            "WHERE is_available = 1 AND genre IS NOT NULL AND genre != '' " +
            "GROUP BY genre ORDER BY genre COLLATE NOCASE",
    )
    fun observeGenres(): Flow<List<GenreRow>>

    @Query(
        "SELECT relative_path AS relative_path, COUNT(*) AS track_count, " +
            "SUM(duration_ms) AS total_duration_ms FROM songs " +
            "WHERE is_available = 1 GROUP BY relative_path ORDER BY relative_path COLLATE NOCASE",
    )
    fun observeFolders(): Flow<List<FolderRow>>

    @Query(
        "SELECT * FROM songs WHERE is_available = 1 AND album = :album ORDER BY title COLLATE NOCASE",
    )
    fun observeSongsByAlbum(album: String): Flow<List<SongEntity>>

    @Query(
        "SELECT * FROM songs WHERE is_available = 1 AND artist = :artist ORDER BY title COLLATE NOCASE",
    )
    fun observeSongsByArtist(artist: String): Flow<List<SongEntity>>

    @Query(
        "SELECT * FROM songs WHERE is_available = 1 AND genre = :genre ORDER BY title COLLATE NOCASE",
    )
    fun observeSongsByGenre(genre: String): Flow<List<SongEntity>>

    @Query(
        "SELECT * FROM songs WHERE is_available = 1 AND relative_path = :relativePath " +
            "ORDER BY display_name COLLATE NOCASE",
    )
    fun observeSongsByFolder(relativePath: String): Flow<List<SongEntity>>

    @Query(
        "SELECT * FROM songs WHERE is_available = 1 ORDER BY date_modified_seconds DESC LIMIT :limit",
    )
    fun observeRecentlyAdded(limit: Int): Flow<List<SongEntity>>

    @Query(
        "SELECT s.* FROM songs s INNER JOIN play_stats p ON p.song_id = s.media_store_id " +
            "WHERE s.is_available = 1 AND p.last_played_at_epoch_ms IS NOT NULL " +
            "ORDER BY p.last_played_at_epoch_ms DESC LIMIT :limit",
    )
    fun observeRecentlyPlayed(limit: Int): Flow<List<SongEntity>>

    @Query(
        "SELECT s.* FROM songs s INNER JOIN play_stats p ON p.song_id = s.media_store_id " +
            "WHERE s.is_available = 1 AND p.play_count > 0 " +
            "ORDER BY p.play_count DESC LIMIT :limit",
    )
    fun observeMostPlayed(limit: Int): Flow<List<SongEntity>>

    /**
     * Volltextsuche ueber song_fts (Plan Phase 6). [query] muss bereits
     * FTS4-Syntax sein (z. B. mit Praefix-Stern); die rowid entspricht
     * der media_store_id.
     */
    @Query(
        "SELECT s.* FROM songs s JOIN song_fts f ON s.media_store_id = f.rowid " +
            "WHERE song_fts MATCH :query AND s.is_available = 1 ORDER BY s.title COLLATE NOCASE",
    )
    suspend fun search(query: String): List<SongEntity>

    /** Baut den Volltextindex neu auf (nach jedem Scan aufzurufen). */
    @Query("INSERT INTO song_fts(song_fts) VALUES('rebuild')")
    suspend fun rebuildSearchIndex()
}

/** Wiedergabestatistik (Plan Phase 6): Zaehler und letzter Zeitpunkt. */
@Dao
interface PlayStatDao {
    @Query(
        "UPDATE play_stats SET play_count = play_count + 1, last_played_at_epoch_ms = :atEpochMs " +
            "WHERE song_id = :songId",
    )
    suspend fun incrementIfExists(
        songId: Long,
        atEpochMs: Long,
    ): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfMissing(stat: PlayStatEntity)

    @Query("SELECT * FROM play_stats WHERE song_id = :songId")
    suspend fun getStat(songId: Long): PlayStatEntity?

    /** Alle Statistiken; Grundlage der Sortierung nach Zaehler/zuletzt gespielt. */
    @Query("SELECT * FROM play_stats")
    fun observeAll(): Flow<List<PlayStatEntity>>
}

/** Favoriten (Plan Phase 6). */
@Dao
interface FavoriteDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun add(favorite: FavoriteEntity)

    @Query("DELETE FROM favorites WHERE song_id = :songId")
    suspend fun remove(songId: Long)

    @Query("SELECT EXISTS(SELECT 1 FROM favorites WHERE song_id = :songId)")
    fun observeIsFavorite(songId: Long): Flow<Boolean>

    @Query(
        "SELECT s.* FROM songs s INNER JOIN favorites f ON f.song_id = s.media_store_id " +
            "WHERE s.is_available = 1 ORDER BY f.created_at_epoch_ms DESC",
    )
    fun observeFavorites(): Flow<List<SongEntity>>
}

/** Playlisten und ihre Eintraege (Plan Phase 6; auch M3U-Importziel). */
@Dao
interface PlaylistDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertPlaylist(playlist: PlaylistEntity): Long

    @Query("UPDATE playlists SET name = :name WHERE id = :id")
    suspend fun renamePlaylist(
        id: Long,
        name: String,
    )

    @Query("DELETE FROM playlists WHERE id = :id")
    suspend fun deletePlaylist(id: Long)

    /** Setzt oder loescht (null) das Label einer Playlist (Musik-Workout-Plan Phase 2). */
    @Query("UPDATE playlists SET label = :label WHERE id = :id")
    suspend fun setLabel(
        id: Long,
        label: String?,
    )

    @Query("SELECT id FROM playlists WHERE name = :name")
    suspend fun getPlaylistIdByName(name: String): Long?

    @Query(
        "SELECT p.id AS id, p.name AS name, p.label AS label, COUNT(pi.id) AS track_count FROM playlists p " +
            "LEFT JOIN playlist_items pi ON pi.playlist_id = p.id " +
            "GROUP BY p.id, p.name, p.label ORDER BY p.name COLLATE NOCASE",
    )
    fun observePlaylists(): Flow<List<PlaylistRow>>

    /** Playlisten mit einem bestimmten Label (Musik-Workout-Plan Phase 2). */
    @Query(
        "SELECT p.id AS id, p.name AS name, p.label AS label, COUNT(pi.id) AS track_count FROM playlists p " +
            "LEFT JOIN playlist_items pi ON pi.playlist_id = p.id " +
            "WHERE p.label = :label " +
            "GROUP BY p.id, p.name, p.label ORDER BY p.name COLLATE NOCASE",
    )
    fun observePlaylistsByLabel(label: String): Flow<List<PlaylistRow>>

    @Insert
    suspend fun insertItem(item: PlaylistItemEntity): Long

    @Insert
    suspend fun insertItems(items: List<PlaylistItemEntity>)

    @Query("DELETE FROM playlist_items WHERE id = :itemId")
    suspend fun deleteItem(itemId: Long)

    @Query("UPDATE playlist_items SET position = :position WHERE id = :itemId")
    suspend fun updateItemPosition(
        itemId: Long,
        position: Int,
    )

    @Query("DELETE FROM playlist_items WHERE playlist_id = :playlistId")
    suspend fun deleteItemsOfPlaylist(playlistId: Long)

    @Query("SELECT * FROM playlist_items WHERE playlist_id = :playlistId ORDER BY position")
    suspend fun getItemsOnce(playlistId: Long): List<PlaylistItemEntity>

    @Query("SELECT COALESCE(MAX(position), -1) FROM playlist_items WHERE playlist_id = :playlistId")
    suspend fun maxPosition(playlistId: Long): Int

    @Query(
        "SELECT s.* FROM playlist_items pi INNER JOIN songs s ON s.media_store_id = pi.song_id " +
            "WHERE pi.playlist_id = :playlistId ORDER BY pi.position",
    )
    fun observeSongsOfPlaylist(playlistId: Long): Flow<List<SongEntity>>
}
