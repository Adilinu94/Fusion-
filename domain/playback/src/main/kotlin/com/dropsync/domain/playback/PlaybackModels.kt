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
    /**
     * Aktiver Tempo-Faktor der Wiedergabe (BPM-Lock/Tempo-Regler,
     * 0.5..2.0; 1.0 = Originaltempo). Player-global fuer die laufende
     * Session, gilt also auch nach Titelwechseln.
     */
    val playbackSpeed: Float = 1f,
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
 *
 * **Queue-Eintraege statt reiner Song-IDs (2026-09-27):** die Queue wird
 * als [PersistedQueueEntry] gespeichert, nicht als `List<Long>`. Grund:
 * virtuelle CUE-Tracks haben die mediaId `cue:<songId>:<trackNr>`, und
 * `mediaId.toLongOrNull()` liefert dafuer `null` — ueber `mapNotNull`
 * fielen sie aus der Persistenz heraus und waren nach Prozess-Tod
 * verloren. Der Eintrag fuehrt beide Identitaeten: [mediaId] zur
 * Wiederherstellung der Timeline-Position, [songId] fuer den
 * bibliotheksseitigen Zugriff (Play-Stats, Marker).
 */
data class PersistedPlayerState(
    val queueEntries: List<PersistedQueueEntry>,
    val currentSongId: Long?,
    val positionMs: Long,
    val shuffleEnabled: Boolean,
    val repeatMode: RepeatMode,
)

/**
 * Ein Queue-Eintrag in der Persistenz. [songId] ist `null` fuer
 * Eintraege, die sich nicht auf eine MediaStore-ID zurueckfuehren
 * lassen; bei CUE-Tracks ist es die ID der zugrunde liegenden Datei.
 */
data class PersistedQueueEntry(
    val mediaId: String,
    val songId: Long?,
)
