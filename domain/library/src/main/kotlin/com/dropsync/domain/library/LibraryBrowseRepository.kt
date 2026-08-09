package com.dropsync.domain.library

import com.dropsync.core.common.AppResult
import com.dropsync.core.model.PlaylistLabel
import com.dropsync.core.model.Song
import kotlinx.coroutines.flow.Flow

/** Aggregiertes Album der Bibliothek (Plan Phase 6). */
data class Album(
    val title: String,
    val artist: String?,
    val trackCount: Int,
    /** Gesamtdauer aller Titel (Poweramp-Header "Anzahl | Dauer"). */
    val totalDurationMs: Long = 0,
)

/** Aggregierter Kuenstler der Bibliothek. */
data class Artist(
    val name: String,
    val trackCount: Int,
    val albumCount: Int,
    val totalDurationMs: Long = 0,
)

/** Aggregiertes Genre der Bibliothek. */
data class Genre(
    val name: String,
    val trackCount: Int,
    val totalDurationMs: Long = 0,
)

/** Ordnerknoten der Ordneransicht (relativer Pfad aus MediaStore). */
data class LibraryFolder(
    val relativePath: String,
    val trackCount: Int,
    val totalDurationMs: Long = 0,
)

/** Nutzerplaylist mit Eintragszahl und optionalem Workout-Label. */
data class Playlist(
    val id: Long,
    val name: String,
    val trackCount: Int,
    val label: PlaylistLabel? = null,
)

/** Sortierschluessel der Titellisten (Plan Phase 6; Poweramp-Umbau erweitert). */
enum class SongSort {
    TITLE,
    FILENAME,
    PATH,
    ARTIST,
    ALBUM,
    DURATION,
    DATE_ADDED,
    LAST_PLAYED,
    PLAY_COUNT,
}

/**
 * Wiedergabestatistik eines Titels fuer client-seitige Sortierung
 * (Poweramp-Umbau: "Nach Abspielzaehler"/"Nach zuletzt gespielt").
 */
data class SongPlayStat(
    val songId: Long,
    val playCount: Int,
    val lastPlayedAtEpochMs: Long?,
)

/**
 * Ergebnis eines M3U-Playlisten-Imports (Plan Phase 6): [importedCount]
 * lokal aufgeloeste Titel wurden in die Playlist uebernommen,
 * [skippedRemote] Stream-URLs und [unresolved] nicht auffindbare
 * lokale Pfade wurden ausgelassen.
 */
data class PlaylistImportResult(
    val playlistId: Long,
    val importedCount: Int,
    val skippedRemote: Int,
    val unresolved: Int,
)

/**
 * Lesende und schreibende Bibliotheksfunktionen jenseits des reinen
 * Scans (Plan Phase 6): Ansichten (Alben/Kuenstler/Genres/Ordner,
 * zuletzt/meistgespielt, Favoriten), Wiedergabestatistik, Favoriten,
 * Playlisten und Volltextsuche. Getrennt von [LibraryRepository], damit
 * der Scan-Vertrag schlank bleibt (Modulregel 3.2).
 */
interface LibraryBrowseRepository {
    val albums: Flow<List<Album>>
    val artists: Flow<List<Artist>>
    val genres: Flow<List<Genre>>
    val folders: Flow<List<LibraryFolder>>

    /** Alle Wiedergabestatistiken; fehlende Titel gelten als nie gespielt. */
    val playStats: Flow<List<SongPlayStat>>

    fun songsByAlbum(album: String): Flow<List<Song>>

    fun songsByArtist(artist: String): Flow<List<Song>>

    fun songsByGenre(genre: String): Flow<List<Song>>

    fun songsByFolder(relativePath: String): Flow<List<Song>>

    fun recentlyAdded(limit: Int = DEFAULT_LIMIT): Flow<List<Song>>

    fun recentlyPlayed(limit: Int = DEFAULT_LIMIT): Flow<List<Song>>

    fun mostPlayed(limit: Int = DEFAULT_LIMIT): Flow<List<Song>>

    /** Zaehlt eine Wiedergabe und aktualisiert den letzten Zeitpunkt. */
    suspend fun recordPlayback(songId: Long): AppResult<Unit>

    /**
     * Sammelt die Gewichtungsdaten (play_stats/Favoriten) fuer [songIds]
     * fuer das intelligente Shuffle (Musik-Workout-Plan A5). Fehlende
     * Statistik zaehlt als nie gespielt (playCount 0, kein Zeitpunkt).
     */
    suspend fun shuffleCandidates(songIds: List<Long>): AppResult<List<ShuffleCandidate>>

    val favorites: Flow<List<Song>>

    fun isFavorite(songId: Long): Flow<Boolean>

    suspend fun setFavorite(
        songId: Long,
        favorite: Boolean,
    ): AppResult<Unit>

    /** Volltextsuche ueber Titel, Kuenstler, Album und Dateiname. */
    suspend fun search(query: String): AppResult<List<Song>>

    val playlists: Flow<List<Playlist>>

    /** Playlisten mit dem gegebenen Label (Musik-Workout-Plan Phase 2). */
    fun playlistsByLabel(label: PlaylistLabel): Flow<List<Playlist>>

    fun songsOfPlaylist(playlistId: Long): Flow<List<Song>>

    /** Setzt oder entfernt (null) das Workout-Label einer Playlist. */
    suspend fun setPlaylistLabel(
        playlistId: Long,
        label: PlaylistLabel?,
    ): AppResult<Unit>

    suspend fun createPlaylist(name: String): AppResult<Long>

    suspend fun renamePlaylist(
        playlistId: Long,
        name: String,
    ): AppResult<Unit>

    suspend fun deletePlaylist(playlistId: Long): AppResult<Unit>

    suspend fun addToPlaylist(
        playlistId: Long,
        songIds: List<Long>,
    ): AppResult<Unit>

    /** Entfernt den Eintrag an [position] und schliesst die Luecke. */
    suspend fun removeFromPlaylist(
        playlistId: Long,
        position: Int,
    ): AppResult<Unit>

    /** Verschiebt einen Eintrag; Positionen bleiben danach lueckenlos. */
    suspend fun moveInPlaylist(
        playlistId: Long,
        fromPosition: Int,
        toPosition: Int,
    ): AppResult<Unit>

    /**
     * Importiert eine M3U/M3U8-Playlist als Nutzerplaylist (Plan Phase 6).
     * Lokale Eintraege werden ueber den Dateinamen gegen vorhandene Songs
     * aufgeloest; Stream-URLs werden gemaess Offline-Grundsatz uebersprungen.
     */
    suspend fun importM3uPlaylist(
        name: String,
        m3uText: String,
    ): AppResult<PlaylistImportResult>

    companion object {
        const val DEFAULT_LIMIT = 100
    }
}
