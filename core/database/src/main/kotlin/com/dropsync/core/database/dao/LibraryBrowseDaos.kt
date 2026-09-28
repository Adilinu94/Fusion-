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
     *
     * **Sortierung: alphabetisch, mit Begruendung (2026-09-27, Befund 6.9).**
     *
     * Die naheliegende Aenderung war `ORDER BY bm25(song_fts)`. Sie ist
     * auf Android **nicht moeglich**, und zwar aus zwei Gruenden:
     *
     * 1. `bm25()` gehoert zum FTS5-Index. `song_fts` ist als `@Fts4`
     *    deklariert (`LibraryStatsEntities.kt:122`). Androids SQLite
     *    kennt in diesem Modul kein `bm25` — der Aufruf endet mit
     *    `no such function: bm25` bei **jeder** Suche.
     * 2. Der naheliegende Ersatz `matchinfo('song_fts', 'pcx')` wird von
     *    Room/KSP **gegen eine leere Datenbank** validiert, in der die
     *    FTS-Virtualtabelle nicht existiert: `no such table: matchinfo`.
     *    Die Abfrage scheitert damit schon zur Kompilierzeit, nicht erst
     *    zur Laufzeit.
     *
     * Die Relevanzsortierung bleibt deshalb **eine Zeile in der Abfrage**
     * und wird nicht erzwungen. Stattdessen sortiert
     * `LibraryBrowseRepositoryImpl.search` die Treffer nach der Anzahl
     * der im Titel gefundenen Suchbegriffe — das ist die Unterscheidung,
     * die ein Nutzer erwartet, und sie braucht keine SQLite-Funktion.
     *
     * Die beiden Fallbacks (ODER, LIKE) loesen das halbe Problem
     * bereits: sie sind es, die die Treffer ueberhaupt liefern.
     */
    @Query(
        "SELECT s.* FROM songs s JOIN song_fts f ON s.media_store_id = f.rowid " +
            "WHERE song_fts MATCH :query AND s.is_available = 1 " +
            "ORDER BY s.title COLLATE NOCASE",
    )
    suspend fun search(query: String): List<SongEntity>

    /**
     * Wie [search], aber **ODER** statt UND zwischen den Token.
     *
     * 2026-09-27, Befund 6.9: FTS4 verknuepft mehrere Token per UND.
     * "queen metallica" liefert deshalb null Treffer — obwohl es in der
     * Bibliothek fast immer einen gibt, der nur einen der beiden Begriffe
     * im Titel fuehrt.
     *
     * Der Aufrufer probiert erst [search] (praeziser) und faellt dann auf
     * diese Variante zurueck (breiter). Das liefert die "beste"
     * Trefferliste, ohne die UND-Semantik aufzugeben.
     */
    @Query(
        "SELECT s.* FROM songs s JOIN song_fts f ON s.media_store_id = f.rowid " +
            "WHERE song_fts MATCH :query AND s.is_available = 1 " +
            "ORDER BY s.title COLLATE NOCASE",
    )
    suspend fun searchOr(query: String): List<SongEntity>

    /**
     * Normalisierte Teilstring-Suche (2026-09-27, Befund 6.9).
     *
     * **Das ist die Loesung fuer den Fall, der per Abfrage nicht loesbar
     * war.** FTS4 und `LIKE` vergleichen **Bytes**: „beyonce" findet
     * „Beyoncé" nicht, und `LIKE '%beyonce%'` findet es auch nicht, weil
     * der **gespeicherte** Titel die Diakritika traegt. Eine Abfrage
     * kann den Titel nicht umfalten, ohne die ganze Tabelle zu
     * durchsuchen.
     *
     * `songs` fuehrt darum seit Migration 17 `title_folded`,
     * `artist_folded` und `album_folded` — Diakritika entfernt,
     * kleingeschrieben (`Mappers.foldForSearch`). Diese Abfrage
     * normalisiert die **Eingabe** genauso und vergleicht die beiden
     * Seiten direkt.
     *
     * **Der Index hilft hier nicht**, das ist ehrlich gesagt: `LIKE
     * '%…%'` mit fuehrendem Wildzeichen kann keinen B-Tree-Index nutzen,
     * egal auf welcher Spalte. Die Beschleunigung kommt aus zwei Dingen:
     * der Abfrage bleiben 5.000 Zeilen (statt eines Skans durch die
     * FTS-Virtualtabelle **pro Token**), und das Ergebnis ist eine
     * ehrliche Teilstring-Suche statt "nichts gefunden".
     *
     * **Gross-/Kleinschreibung ueber die Spalte, nicht ueber
     * `COLLATE`:** die gefaltete Form ist bereits kleingeschrieben, und
     * die Eingabe wird es auch. Ein `COLLATE NOCASE` waere damit
     * ueberfluessig und wuerde den Vergleich nur auf eine weitere
     * Funktion legen.
     */
    @Query(
        "SELECT * FROM songs WHERE is_available = 1 AND (" +
            "title_folded LIKE :pattern ESCAPE '\\' OR " +
            "artist_folded LIKE :pattern ESCAPE '\\' OR " +
            "album_folded LIKE :pattern ESCAPE '\\'" +
            ") ORDER BY title COLLATE NOCASE",
    )
    suspend fun searchFolded(pattern: String): List<SongEntity>

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

    /**
     * D5/A1: Statistiken fuer mehrere Titel in EINER IN-Query — der Shuffle
     * braucht sonst je Titel eine eigene Abfrage (N+1).
     */
    @Query("SELECT * FROM play_stats WHERE song_id IN (:songIds)")
    suspend fun getStats(songIds: List<Long>): List<PlayStatEntity>

    /** Alle Statistiken; Grundlage der Sortierung nach Zaehler/zuletzt gespielt. */
    @Query("SELECT * FROM play_stats")
    fun observeAll(): Flow<List<PlayStatEntity>>

    /**
     * Haengt die Abspielstatistik auf eine neue MediaStore-ID um
     * (2026-09-27, Befund 6.7). Ohne das beginnt die Historie des Titels
     * nach dem Verschieben bei null.
     */
    @Query("UPDATE play_stats SET song_id = :newSongId WHERE song_id = :oldSongId")
    suspend fun reassignSong(
        oldSongId: Long,
        newSongId: Long,
    ): Int
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

    /**
     * Haengt die Favoritenmarkierung auf eine neue MediaStore-ID um
     * (2026-09-27, Befund 6.7).
     *
     * `favorites.song_id` ist der Primaerschluessel. Kollidiert die
     * Ziel-ID bereits (der Nutzer hat den Titel am neuen Ort favorisiert
     * und verschiebt die alte Datei dorthin), gibt es kein `REPLACE`:
     * die alte Zeile wird **geloescht**, und die neue bleibt. Das ist
     * richtig — die Markierung existiert dann genau einmal, wie sie sollte.
     */
    @Query("UPDATE OR REPLACE favorites SET song_id = :newSongId WHERE song_id = :oldSongId")
    suspend fun reassignSong(
        oldSongId: Long,
        newSongId: Long,
    ): Int
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

    /** Song-IDs, die bereits in der Playlist stehen (Duplikat-Schutz, UI-Befund 4.2.2). */
    @Query("SELECT song_id FROM playlist_items WHERE playlist_id = :playlistId")
    suspend fun getSongIdsOnce(playlistId: Long): List<Long>

    @Query("SELECT COALESCE(MAX(position), -1) FROM playlist_items WHERE playlist_id = :playlistId")
    suspend fun maxPosition(playlistId: Long): Int

    /**
     * Haengt die Playlist-Eintraege auf eine neue MediaStore-ID um
     * (2026-09-27, Befund 6.7). Der Nutzer sortiert Lieder in Playlists;
     * nach dem Verschieben einer Datei waeren sie sonst aus allen
     * Nutzer-Playlists raus.
     *
     * **Der Kollisionsfall loest `REPLACE` auf:** die UNIQUE-Constraint
     * `(playlist_id, song_id)` aus Migration 16 laesst ein Update auf
     * einen bereits vorhandenen Eintrag **fehlschlagen** — richtig, aber
     * es wuerde die ganze Scan-Transaktion abbrechen. `REPLACE` nimmt
     * stattdessen die bestehende Zeile, das ist genau die gewuenschte
     * Aufloesung: der Titel steht danach genau einmal in der Playlist.
     */
    @Query(
        "UPDATE OR REPLACE playlist_items SET song_id = :newSongId WHERE song_id = :oldSongId",
    )
    suspend fun reassignSong(
        oldSongId: Long,
        newSongId: Long,
    ): Int

    /**
     * Titel einer Playlist, **nur verfuegbare** (2026-09-27, Befund
     * 6.8/P2.5).
     *
     * Vorher standen hier Eintraege zu Dateien, die es nicht mehr gibt
     * (oder die in einem abgewaehlten Ordner liegen). Der Nutzer sieht
     * sie in der Playlist, tippt sie an, und der Player meldet
     * `MediaUnavailable` — eine Playlist, die zu einem Drittel aus
     * Toten besteht, liest sich wie ein Fehler, ist aber keiner.
     *
     * **Warum filtern statt markieren:** `playlist_items` bleibt
     * unangetastet. Nimmt der Nutzer den Ordner wieder in die Auswahl
     * auf, sind die Titel ohne einen erneuten Import wieder da. Das ist
     * der Grund, warum `is_available = 0` ueberhaupt existiert.
     */
    @Query(
        "SELECT s.* FROM playlist_items pi INNER JOIN songs s ON s.media_store_id = pi.song_id " +
            "WHERE pi.playlist_id = :playlistId AND s.is_available = 1 ORDER BY pi.position",
    )
    fun observeSongsOfPlaylist(playlistId: Long): Flow<List<SongEntity>>

    /**
     * D5/A7: Titel aller Playlists mit diesem Label in EINER Abfrage — die
     * Planung lud vorher je Playlist eine eigene Query (N+1). Reihenfolge
     * wie die Einzelabfragen: Playlistname, dann Position; Duplikate ueber
     * mehrere Playlists entfernt der Aufrufer (distinctBy Song-ID).
     *
     * **Auch hier nur verfuegbare Titel** (Befund 6.8/P2.5), aus
     * demselben Grund wie [observeSongsOfPlaylist]: die Pause-Playlist
     * wird von der DropSync-Planung befüllt, und ein nicht abspielbarer
     * Titel dort ist ein Plan, der nie landen kann.
     */
    @Query(
        "SELECT s.* FROM songs s " +
            "INNER JOIN playlist_items pi ON pi.song_id = s.media_store_id " +
            "INNER JOIN playlists p ON p.id = pi.playlist_id " +
            "WHERE p.label = :label AND s.is_available = 1 " +
            "ORDER BY p.name COLLATE NOCASE, pi.position",
    )
    suspend fun getSongsForLabelOnce(label: String): List<SongEntity>
}
