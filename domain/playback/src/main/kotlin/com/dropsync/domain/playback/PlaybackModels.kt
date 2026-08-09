package com.dropsync.domain.playback

/** Wiederholmodus ohne Media3-Typen (ADR-0004); stabile Namen. */
enum class RepeatMode { OFF, ONE, ALL }

/**
 * Ein Eintrag der Wiedergabewarteschlange fuer den Queue-Editor (Plan
 * Phase 6, Punkt 3). [mediaId] identifiziert die Timeline-Position
 * stabil (auch virtuelle CUE-Tracks); [songId] ist die MediaStore-ID,
 * falls es sich um einen regulaeren Song handelt.
 */
data class QueueItem(
    val mediaId: String,
    val songId: Long?,
    val title: String,
    val artist: String?,
)

/**
 * Beobachtbarer Wiedergabezustand fuer Features (Bauplan 3.3).
 * Song-Identitaet ist immer die MediaStore-ID (5.1).
 */
data class PlaybackState(
    val isPlaying: Boolean = false,
    val currentSongId: Long? = null,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val shuffleEnabled: Boolean = false,
    val repeatMode: RepeatMode = RepeatMode.OFF,
    val queueSongIds: List<Long> = emptyList(),
    /** Position des laufenden Titels in [queue]; -1 wenn leer (Queue-Editor). */
    val currentIndex: Int = -1,
    /** Vollstaendige Warteschlange mit Anzeigemetadaten (Queue-Editor). */
    val queue: List<QueueItem> = emptyList(),
)

/**
 * Persistierter Wiederherstellungszustand (Schritt 5.5): Queue, Shuffle,
 * Repeat, letzter Song und Position werden nach jeder relevanten
 * Aenderung gespeichert. Automatisches Playback-Resume ueber den
 * Media3-Callback bleibt in Version 1 deaktiviert, bis es separat
 * implementiert und getestet ist.
 */
data class PersistedPlayerState(
    val queueSongIds: List<Long>,
    val currentSongId: Long?,
    val positionMs: Long,
    val shuffleEnabled: Boolean,
    val repeatMode: RepeatMode,
)
