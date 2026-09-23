package com.dropsync.core.testing

import com.dropsync.core.common.AppResult
import com.dropsync.core.model.PlaylistLabel
import com.dropsync.core.model.Song
import com.dropsync.domain.library.Album
import com.dropsync.domain.library.Artist
import com.dropsync.domain.library.Genre
import com.dropsync.domain.library.LibraryBrowseRepository
import com.dropsync.domain.library.LibraryFolder
import com.dropsync.domain.library.Playlist
import com.dropsync.domain.library.PlaylistImportResult
import com.dropsync.domain.library.ShuffleCandidate
import com.dropsync.domain.library.SongPlayStat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf

/**
 * Steuerbarer Browse-Fake (C3/C7): nur die Playlist-Labels und die
 * Playlist-Liste sind einstellbar, alles andere leer.
 */
class FakeLibraryBrowseRepository : LibraryBrowseRepository {
    val playlistsByLabelMap = mutableMapOf<PlaylistLabel, MutableStateFlow<List<Playlist>>>()

    /** C7: steuerbare Playlist-Liste (DropSync-Sektion). */
    val playlistsFlow = MutableStateFlow<List<Playlist>>(emptyList())

    /** C7: Aufrufe von [setPlaylistLabel] (Zuordnung in Tests). */
    val setLabelCalls = mutableListOf<Pair<Long, PlaylistLabel?>>()

    /** C4: steuerbare Titel je Playlist (Abdeckung/Detail/Verwenden). */
    val songsByPlaylist = mutableMapOf<Long, MutableStateFlow<List<Song>>>()

    /** C4: setzt die Titel einer Playlist (legt den Eintrag bei Bedarf an). */
    fun setSongsOfPlaylist(
        playlistId: Long,
        songs: List<Song>,
    ) {
        songsByPlaylist.getOrPut(playlistId) { MutableStateFlow(emptyList()) }.value = songs
    }

    override fun playlistsByLabel(label: PlaylistLabel): Flow<List<Playlist>> =
        playlistsByLabelMap.getOrPut(label) { MutableStateFlow(emptyList()) }

    override val playlists: Flow<List<Playlist>> = playlistsFlow

    override fun songsOfPlaylist(playlistId: Long): Flow<List<Song>> =
        songsByPlaylist.getOrPut(playlistId) { MutableStateFlow(emptyList()) }

    // D5/A7: die kombinierte Abfrage leitet sich aus denselben steuerbaren
    // Maps ab (Playlist-Reihenfolge, dann Titel), Duplikate je Song einmal.
    override suspend fun songsForLabelOnce(label: PlaylistLabel): AppResult<List<Song>> {
        val songs =
            playlistsByLabelMap[label]
                ?.value
                .orEmpty()
                .flatMap { playlist -> songsByPlaylist[playlist.id]?.value.orEmpty() }
                .distinctBy { it.mediaStoreId }
        return AppResult.Success(songs)
    }

    override val albums: Flow<List<Album>> = flowOf(emptyList())
    override val artists: Flow<List<Artist>> = flowOf(emptyList())
    override val genres: Flow<List<Genre>> = flowOf(emptyList())
    override val folders: Flow<List<LibraryFolder>> = flowOf(emptyList())
    override val playStats: Flow<List<SongPlayStat>> = flowOf(emptyList())
    override val favorites: Flow<List<Song>> = flowOf(emptyList())

    override fun songsByAlbum(album: String): Flow<List<Song>> = flowOf(emptyList())

    override fun songsByArtist(artist: String): Flow<List<Song>> = flowOf(emptyList())

    override fun songsByGenre(genre: String): Flow<List<Song>> = flowOf(emptyList())

    override fun songsByFolder(relativePath: String): Flow<List<Song>> = flowOf(emptyList())

    override fun recentlyAdded(limit: Int): Flow<List<Song>> = flowOf(emptyList())

    override fun recentlyPlayed(limit: Int): Flow<List<Song>> = flowOf(emptyList())

    override fun mostPlayed(limit: Int): Flow<List<Song>> = flowOf(emptyList())

    override fun isFavorite(songId: Long): Flow<Boolean> = flowOf(false)

    override suspend fun recordPlayback(songId: Long): AppResult<Unit> = AppResult.Success(Unit)

    override suspend fun shuffleCandidates(songIds: List<Long>): AppResult<List<ShuffleCandidate>> =
        AppResult.Success(emptyList())

    override suspend fun setFavorite(
        songId: Long,
        favorite: Boolean,
    ): AppResult<Unit> = AppResult.Success(Unit)

    override suspend fun search(query: String): AppResult<List<Song>> = AppResult.Success(emptyList())

    override suspend fun setPlaylistLabel(
        playlistId: Long,
        label: PlaylistLabel?,
    ): AppResult<Unit> {
        setLabelCalls += playlistId to label
        return AppResult.Success(Unit)
    }

    override suspend fun createPlaylist(name: String): AppResult<Long> = AppResult.Success(0L)

    override suspend fun renamePlaylist(
        playlistId: Long,
        name: String,
    ): AppResult<Unit> = AppResult.Success(Unit)

    override suspend fun deletePlaylist(playlistId: Long): AppResult<Unit> = AppResult.Success(Unit)

    override suspend fun addToPlaylist(
        playlistId: Long,
        songIds: List<Long>,
    ): AppResult<Int> = AppResult.Success(0)

    override suspend fun removeFromPlaylist(
        playlistId: Long,
        position: Int,
    ): AppResult<Unit> = AppResult.Success(Unit)

    override suspend fun moveInPlaylist(
        playlistId: Long,
        fromPosition: Int,
        toPosition: Int,
    ): AppResult<Unit> = AppResult.Success(Unit)

    override suspend fun importM3uPlaylist(
        name: String,
        m3uText: String,
    ): AppResult<PlaylistImportResult> = AppResult.Success(PlaylistImportResult(0L, 0, 0, 0))
}
