package com.dropsync.data.library

import com.dropsync.core.database.entity.SongEntity
import com.dropsync.core.database.entity.SongMarkerEntity
import com.dropsync.core.model.MarkerSource
import com.dropsync.core.model.Song
import com.dropsync.core.model.SongMarker

// Abbildung zwischen Room-Entities und Domainmodellen (Bauplan 3.2/3):
// Features sehen nur Domainmodelle, nie Datenbankzeilen.

internal fun SongEntity.toDomain(): Song =
    Song(
        mediaStoreId = mediaStoreId,
        contentUri = contentUri,
        displayName = displayName,
        relativePath = relativePath,
        durationMs = durationMs,
        sizeBytes = sizeBytes,
        dateModifiedSeconds = dateModifiedSeconds,
        title = title,
        artist = artist,
        album = album,
        genre = genre,
        isAvailable = isAvailable,
    )

internal fun Song.toEntity(knownSha256: String?): SongEntity =
    SongEntity(
        mediaStoreId = mediaStoreId,
        contentUri = contentUri,
        displayName = displayName,
        relativePath = relativePath,
        durationMs = durationMs,
        sizeBytes = sizeBytes,
        dateModifiedSeconds = dateModifiedSeconds,
        title = title,
        artist = artist,
        album = album,
        genre = genre,
        isAvailable = isAvailable,
        knownSha256 = knownSha256,
    )

internal fun SongMarkerEntity.toDomain(linkedSongId: Long?): SongMarker =
    SongMarker(
        id = id,
        label = label,
        positionMs = positionMs,
        source = MarkerSource.valueOf(source),
        isEnabled = isEnabled,
        linkedSongId = linkedSongId,
    )
