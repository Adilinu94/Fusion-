package com.dropsync.data.library

import com.dropsync.core.common.AppError
import com.dropsync.core.common.AppResult
import com.dropsync.core.common.DispatcherProvider
import com.dropsync.core.database.TransactionRunner
import com.dropsync.core.database.dao.FavoriteDao
import com.dropsync.core.database.dao.LibraryBrowseDao
import com.dropsync.core.database.dao.PlayStatDao
import com.dropsync.core.database.dao.PlaylistDao
import com.dropsync.core.database.dao.SongDao
import com.dropsync.core.database.entity.FavoriteEntity
import com.dropsync.core.database.entity.PlayStatEntity
import com.dropsync.core.database.entity.PlaylistEntity
import com.dropsync.core.database.entity.PlaylistItemEntity
import com.dropsync.core.model.PlaylistLabel
import com.dropsync.core.model.Song
import com.dropsync.domain.library.Album
import com.dropsync.domain.library.Artist
import com.dropsync.domain.library.Genre
import com.dropsync.domain.library.LibraryBrowseRepository
import com.dropsync.domain.library.LibraryFolder
import com.dropsync.domain.library.M3uPlaylistParser
import com.dropsync.domain.library.Playlist
import com.dropsync.domain.library.PlaylistImportResult
import com.dropsync.domain.library.ShuffleCandidate
import com.dropsync.domain.library.SongPlayStat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * Bibliotheksansichten, Statistik, Favoriten, Playlisten und Suche
 * (Plan Phase 6). Alle Lesepfade kommen aus Room; Songs erreichen die
 * Features nur als Domainmodelle (Modulregel 3.2).
 */
class LibraryBrowseRepositoryImpl(
    private val browseDao: LibraryBrowseDao,
    private val playStatDao: PlayStatDao,
    private val favoriteDao: FavoriteDao,
    private val playlistDao: PlaylistDao,
    private val songDao: SongDao,
    private val transactionRunner: TransactionRunner,
    private val dispatchers: DispatcherProvider,
    private val now: () -> Long = { System.currentTimeMillis() },
) : LibraryBrowseRepository {
    override val albums: Flow<List<Album>> =
        browseDao.observeAlbums().map { rows -> rows.map { it.toDomain() } }

    override val artists: Flow<List<Artist>> =
        browseDao.observeArtists().map { rows -> rows.map { it.toDomain() } }

    override val genres: Flow<List<Genre>> =
        browseDao.observeGenres().map { rows -> rows.map { it.toDomain() } }

    override val folders: Flow<List<LibraryFolder>> =
        browseDao.observeFolders().map { rows -> rows.map { it.toDomain() } }

    override val playStats: Flow<List<SongPlayStat>> =
        playStatDao.observeAll().map { rows ->
            rows.map {
                SongPlayStat(
                    songId = it.songId,
                    playCount = it.playCount,
                    lastPlayedAtEpochMs = it.lastPlayedAtEpochMs,
                )
            }
        }

    override fun songsByAlbum(album: String): Flow<List<Song>> =
        browseDao.observeSongsByAlbum(album).map { it.map { row -> row.toDomain() } }

    override fun songsByArtist(artist: String): Flow<List<Song>> =
        browseDao.observeSongsByArtist(artist).map { it.map { row -> row.toDomain() } }

    override fun songsByGenre(genre: String): Flow<List<Song>> =
        browseDao.observeSongsByGenre(genre).map { it.map { row -> row.toDomain() } }

    override fun songsByFolder(relativePath: String): Flow<List<Song>> =
        browseDao.observeSongsByFolder(relativePath).map { it.map { row -> row.toDomain() } }

    override fun recentlyAdded(limit: Int): Flow<List<Song>> =
        browseDao.observeRecentlyAdded(limit).map { it.map { row -> row.toDomain() } }

    override fun recentlyPlayed(limit: Int): Flow<List<Song>> =
        browseDao.observeRecentlyPlayed(limit).map { it.map { row -> row.toDomain() } }

    override fun mostPlayed(limit: Int): Flow<List<Song>> =
        browseDao.observeMostPlayed(limit).map { it.map { row -> row.toDomain() } }

    override suspend fun recordPlayback(songId: Long): AppResult<Unit> =
        withContext(dispatchers.io) {
            try {
                transactionRunner {
                    val updated = playStatDao.incrementIfExists(songId, now())
                    if (updated == 0) {
                        playStatDao.insertIfMissing(
                            PlayStatEntity(songId = songId, playCount = 1, lastPlayedAtEpochMs = now()),
                        )
                    }
                }
                AppResult.success(Unit)
            } catch (e: Exception) {
                AppResult.failure(AppError.DatabaseFailure("recordPlayback"))
            }
        }

    override suspend fun shuffleCandidates(songIds: List<Long>): AppResult<List<ShuffleCandidate>> =
        withContext(dispatchers.io) {
            if (songIds.isEmpty()) return@withContext AppResult.success(emptyList())
            try {
                // Favoriten einmal als Momentaufnahme; play_stats pro Titel.
                val favoriteIds =
                    favoriteDao
                        .observeFavorites()
                        .first()
                        .map { it.mediaStoreId }
                        .toSet()
                val candidates =
                    songIds.map { songId ->
                        val stat = playStatDao.getStat(songId)
                        ShuffleCandidate(
                            songId = songId,
                            playCount = stat?.playCount ?: 0,
                            lastPlayedAtEpochMs = stat?.lastPlayedAtEpochMs,
                            isFavorite = songId in favoriteIds,
                        )
                    }
                AppResult.success(candidates)
            } catch (e: Exception) {
                AppResult.failure(AppError.DatabaseFailure("shuffleCandidates"))
            }
        }

    override val favorites: Flow<List<Song>> =
        favoriteDao.observeFavorites().map { it.map { row -> row.toDomain() } }

    override fun isFavorite(songId: Long): Flow<Boolean> = favoriteDao.observeIsFavorite(songId)

    override suspend fun setFavorite(
        songId: Long,
        favorite: Boolean,
    ): AppResult<Unit> =
        withContext(dispatchers.io) {
            try {
                if (favorite) {
                    favoriteDao.add(FavoriteEntity(songId = songId, createdAtEpochMs = now()))
                } else {
                    favoriteDao.remove(songId)
                }
                AppResult.success(Unit)
            } catch (e: Exception) {
                AppResult.failure(AppError.DatabaseFailure("setFavorite"))
            }
        }

    override suspend fun search(query: String): AppResult<List<Song>> =
        withContext(dispatchers.io) {
            val trimmed = query.trim()
            if (trimmed.isEmpty()) return@withContext AppResult.success(emptyList())
            try {
                // Der Volltextindex ist eine content-Tabelle ueber songs und
                // wird vor der Suche neu aufgebaut, damit er den letzten
                // Scan widerspiegelt (Room synchronisiert ihn nicht selbst).
                browseDao.rebuildSearchIndex()
                val match = toFtsPrefixQuery(trimmed)
                val hits = browseDao.search(match)
                AppResult.success(hits.map { it.toDomain() })
            } catch (e: Exception) {
                AppResult.failure(AppError.DatabaseFailure("search"))
            }
        }

    override val playlists: Flow<List<Playlist>> =
        playlistDao.observePlaylists().map { rows -> rows.map { it.toDomain() } }

    override fun playlistsByLabel(label: PlaylistLabel): Flow<List<Playlist>> =
        playlistDao.observePlaylistsByLabel(label.name).map { rows -> rows.map { it.toDomain() } }

    override fun songsOfPlaylist(playlistId: Long): Flow<List<Song>> =
        playlistDao.observeSongsOfPlaylist(playlistId).map { it.map { row -> row.toDomain() } }

    override suspend fun setPlaylistLabel(
        playlistId: Long,
        label: PlaylistLabel?,
    ): AppResult<Unit> =
        withContext(dispatchers.io) {
            try {
                playlistDao.setLabel(playlistId, label?.name)
                AppResult.success(Unit)
            } catch (e: Exception) {
                AppResult.failure(AppError.DatabaseFailure("setPlaylistLabel"))
            }
        }

    override suspend fun createPlaylist(name: String): AppResult<Long> =
        withContext(dispatchers.io) {
            val trimmed = name.trim()
            if (trimmed.isEmpty()) {
                return@withContext AppResult.failure(AppError.Unknown("Playlistname ist leer"))
            }
            try {
                if (playlistDao.getPlaylistIdByName(trimmed) != null) {
                    return@withContext AppResult.failure(
                        AppError.Unknown("Playlist '$trimmed' existiert bereits"),
                    )
                }
                val id =
                    playlistDao.insertPlaylist(
                        PlaylistEntity(name = trimmed, createdAtEpochMs = now()),
                    )
                AppResult.success(id)
            } catch (e: Exception) {
                AppResult.failure(AppError.DatabaseFailure("createPlaylist"))
            }
        }

    override suspend fun renamePlaylist(
        playlistId: Long,
        name: String,
    ): AppResult<Unit> =
        withContext(dispatchers.io) {
            val trimmed = name.trim()
            if (trimmed.isEmpty()) {
                return@withContext AppResult.failure(AppError.Unknown("Playlistname ist leer"))
            }
            try {
                val clash = playlistDao.getPlaylistIdByName(trimmed)
                if (clash != null && clash != playlistId) {
                    return@withContext AppResult.failure(
                        AppError.Unknown("Playlist '$trimmed' existiert bereits"),
                    )
                }
                playlistDao.renamePlaylist(playlistId, trimmed)
                AppResult.success(Unit)
            } catch (e: Exception) {
                AppResult.failure(AppError.DatabaseFailure("renamePlaylist"))
            }
        }

    override suspend fun deletePlaylist(playlistId: Long): AppResult<Unit> =
        withContext(dispatchers.io) {
            try {
                playlistDao.deletePlaylist(playlistId)
                AppResult.success(Unit)
            } catch (e: Exception) {
                AppResult.failure(AppError.DatabaseFailure("deletePlaylist"))
            }
        }

    override suspend fun addToPlaylist(
        playlistId: Long,
        songIds: List<Long>,
    ): AppResult<Unit> =
        withContext(dispatchers.io) {
            if (songIds.isEmpty()) return@withContext AppResult.success(Unit)
            try {
                transactionRunner {
                    var position = playlistDao.maxPosition(playlistId) + 1
                    val items =
                        songIds.map { songId ->
                            PlaylistItemEntity(
                                playlistId = playlistId,
                                songId = songId,
                                position = position++,
                            )
                        }
                    playlistDao.insertItems(items)
                }
                AppResult.success(Unit)
            } catch (e: Exception) {
                AppResult.failure(AppError.DatabaseFailure("addToPlaylist"))
            }
        }

    override suspend fun removeFromPlaylist(
        playlistId: Long,
        position: Int,
    ): AppResult<Unit> =
        withContext(dispatchers.io) {
            try {
                transactionRunner {
                    val items = playlistDao.getItemsOnce(playlistId)
                    val target = items.getOrNull(position) ?: return@transactionRunner
                    playlistDao.deleteItem(target.id)
                    // Verbleibende Eintraege lueckenlos neu nummerieren.
                    items
                        .filterNot { it.id == target.id }
                        .forEachIndexed { index, item ->
                            if (item.position != index) {
                                playlistDao.updateItemPosition(item.id, index)
                            }
                        }
                }
                AppResult.success(Unit)
            } catch (e: Exception) {
                AppResult.failure(AppError.DatabaseFailure("removeFromPlaylist"))
            }
        }

    override suspend fun moveInPlaylist(
        playlistId: Long,
        fromPosition: Int,
        toPosition: Int,
    ): AppResult<Unit> =
        withContext(dispatchers.io) {
            try {
                transactionRunner {
                    val items = playlistDao.getItemsOnce(playlistId).toMutableList()
                    if (fromPosition !in items.indices || toPosition !in items.indices) {
                        return@transactionRunner
                    }
                    val moved = items.removeAt(fromPosition)
                    items.add(toPosition, moved)
                    items.forEachIndexed { index, item ->
                        if (item.position != index) {
                            playlistDao.updateItemPosition(item.id, index)
                        }
                    }
                }
                AppResult.success(Unit)
            } catch (e: Exception) {
                AppResult.failure(AppError.DatabaseFailure("moveInPlaylist"))
            }
        }

    override suspend fun importM3uPlaylist(
        name: String,
        m3uText: String,
    ): AppResult<PlaylistImportResult> =
        withContext(dispatchers.io) {
            val trimmed = name.trim()
            if (trimmed.isEmpty()) {
                return@withContext AppResult.failure(AppError.Unknown("Playlistname ist leer"))
            }
            try {
                val parsed = M3uPlaylistParser.parse(m3uText)
                // Lokale Eintraege ueber den Dateinamen eindeutig aufloesen.
                val songsByName =
                    songDao
                        .getAllOnce()
                        .groupBy { it.displayName.lowercase() }
                var skippedRemote = 0
                var unresolved = 0
                val matchedIds = ArrayList<Long>()
                for (entry in parsed.entries) {
                    if (entry.isRemote) {
                        skippedRemote++
                        continue
                    }
                    val fileName =
                        entry.location
                            .trim()
                            .replace('\\', '/')
                            .substringAfterLast('/')
                            .lowercase()
                    val matches = songsByName[fileName]
                    if (matches == null || matches.size != 1) {
                        unresolved++
                    } else {
                        matchedIds += matches.first().mediaStoreId
                    }
                }
                val playlistId =
                    transactionRunner {
                        val existing = playlistDao.getPlaylistIdByName(trimmed)
                        val id =
                            existing
                                ?: playlistDao.insertPlaylist(
                                    PlaylistEntity(name = trimmed, createdAtEpochMs = now()),
                                )
                        var position = playlistDao.maxPosition(id) + 1
                        if (matchedIds.isNotEmpty()) {
                            playlistDao.insertItems(
                                matchedIds.map { songId ->
                                    PlaylistItemEntity(
                                        playlistId = id,
                                        songId = songId,
                                        position = position++,
                                    )
                                },
                            )
                        }
                        id
                    }
                AppResult.success(
                    PlaylistImportResult(
                        playlistId = playlistId,
                        importedCount = matchedIds.size,
                        skippedRemote = skippedRemote,
                        unresolved = unresolved,
                    ),
                )
            } catch (e: Exception) {
                AppResult.failure(AppError.DatabaseFailure("importM3uPlaylist"))
            }
        }

    /**
     * Baut aus Nutzertext eine sichere FTS4-Praefixabfrage: Sonderzeichen
     * werden entfernt, jedes Token als Praefix (`token*`) gesucht.
     */
    private fun toFtsPrefixQuery(raw: String): String =
        raw
            .split(Regex("\\s+"))
            .mapNotNull { token ->
                val cleaned = token.replace(Regex("[^\\p{L}\\p{N}]"), "")
                if (cleaned.isEmpty()) null else "$cleaned*"
            }.joinToString(" ")
            .ifEmpty { "\"\"" }
}
