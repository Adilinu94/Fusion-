package com.dropsync.core.model

// Domainmodelle der lokalen Musikbibliothek (Bauplan 5.1).
// Identitaet ist immer die MediaStore-ID; Dateipfade sind nie Schluessel.

data class Song(
    val mediaStoreId: Long,
    val contentUri: String,
    val displayName: String,
    val relativePath: String,
    val durationMs: Long,
    val sizeBytes: Long,
    val dateModifiedSeconds: Long,
    val title: String?,
    val artist: String?,
    val album: String?,
    /** Genre aus MediaStore (Plan Phase 6), falls indexiert. */
    val genre: String? = null,
    val isAvailable: Boolean,
)

data class SongMarker(
    val id: Long,
    val label: String,
    val positionMs: Long,
    val source: MarkerSource,
    val isEnabled: Boolean,
    /** Zugeordneter Song ueber MarkerSongLink; null = nicht zugeordnet. */
    val linkedSongId: Long?,
)
