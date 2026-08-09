package com.dropsync.data.library

import com.dropsync.core.database.entity.CueTrackEntity
import com.dropsync.core.database.entity.SafFileEntity
import com.dropsync.domain.library.AudioFileFormat
import com.dropsync.domain.library.CueTrack
import com.dropsync.domain.library.CueVirtualTrack
import com.dropsync.domain.library.ScannedFile
import com.dropsync.domain.library.ScannedFileKind

// Abbildungen zwischen Scan-Persistenz und Domain (Plan Phase 3).

internal fun CueTrackEntity.toDomain(): CueVirtualTrack =
    CueVirtualTrack(
        id = id,
        songId = songId,
        trackNumber = trackNumber,
        title = title,
        performer = performer,
        startMs = startMs,
        endMs = endMs,
    )

/** Geparster CUE-Track -> Persistenz fuer [songId]. */
internal fun CueTrack.toEntity(songId: Long): CueTrackEntity =
    CueTrackEntity(
        songId = songId,
        trackNumber = number,
        title = title,
        performer = performer,
        startMs = startMs,
        endMs = endMs,
    )

internal fun SafFileEntity.toDomain(): ScannedFile =
    ScannedFile(
        id = id,
        treeUri = treeUri,
        documentUri = documentUri,
        displayName = displayName,
        relativePath = relativePath,
        sizeBytes = sizeBytes,
        lastModifiedMs = lastModifiedMs,
        kind =
            runCatching { ScannedFileKind.valueOf(kind) }
                .getOrDefault(ScannedFileKind.AUDIO),
        format = format?.let { runCatching { AudioFileFormat.valueOf(it) }.getOrNull() },
    )

internal fun SafDocument.toEntity(
    treeUri: String,
    kind: ScannedFileKind,
    format: AudioFileFormat?,
): SafFileEntity =
    SafFileEntity(
        treeUri = treeUri,
        documentUri = documentUri,
        displayName = displayName,
        relativePath = relativePath,
        sizeBytes = sizeBytes,
        lastModifiedMs = lastModifiedMs,
        kind = kind.name,
        format = format?.name,
    )
