package com.dropsync.data.library

import com.dropsync.core.database.dao.AlbumRow
import com.dropsync.core.database.dao.ArtistRow
import com.dropsync.core.database.dao.FolderRow
import com.dropsync.core.database.dao.GenreRow
import com.dropsync.core.database.dao.PlaylistRow
import com.dropsync.core.model.PlaylistLabel
import com.dropsync.domain.library.Album
import com.dropsync.domain.library.Artist
import com.dropsync.domain.library.Genre
import com.dropsync.domain.library.LibraryFolder
import com.dropsync.domain.library.Playlist

// Abbildung der Room-Projektionszeilen auf Domainmodelle (Plan Phase 6).

internal fun AlbumRow.toDomain(): Album =
    Album(title = album, artist = artist, trackCount = trackCount, totalDurationMs = totalDurationMs)

internal fun ArtistRow.toDomain(): Artist =
    Artist(
        name = artist,
        trackCount = trackCount,
        albumCount = albumCount,
        totalDurationMs = totalDurationMs,
    )

internal fun GenreRow.toDomain(): Genre =
    Genre(name = genre, trackCount = trackCount, totalDurationMs = totalDurationMs)

internal fun FolderRow.toDomain(): LibraryFolder =
    LibraryFolder(
        relativePath = relativePath,
        trackCount = trackCount,
        totalDurationMs = totalDurationMs,
    )

internal fun PlaylistRow.toDomain(): Playlist =
    Playlist(
        id = id,
        name = name,
        trackCount = trackCount,
        label = label?.let { runCatching { PlaylistLabel.valueOf(it) }.getOrNull() },
    )
