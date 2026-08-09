package com.dropsync.domain.playback

import com.dropsync.core.common.AppResult
import com.dropsync.core.model.Song
import kotlinx.coroutines.flow.Flow

/**
 * Einziger App-Zugang zur Wiedergabe (Bauplan 3.3, ADR-0004).
 *
 * Implementierung in :data:playback ueber MediaController; kein Feature
 * ruft je ExoPlayer.Builder auf (Schritt 5.3). Alle Kommandos wirken auf
 * genau eine Player-Instanz im PlaybackService.
 */
interface PlaybackRepository {
    /** Beobachtbarer Zustand derselben Wiedergabeinstanz fuer alle Screens. */
    val state: Flow<PlaybackState>

    /**
     * Ersetzt die Queue durch [songs], startet bei [startIndex].
     * MediaUnavailable, wenn die Liste leer ist oder der Index nicht passt.
     */
    suspend fun setQueue(
        songs: List<Song>,
        startIndex: Int,
        playWhenReady: Boolean,
    ): AppResult<Unit>

    suspend fun play(): AppResult<Unit>

    suspend fun pause(): AppResult<Unit>

    suspend fun seekTo(positionMs: Long): AppResult<Unit>

    suspend fun skipToNext(): AppResult<Unit>

    suspend fun skipToPrevious(): AppResult<Unit>

    /**
     * Springt zum Warteschlangeneintrag [index] und startet dort
     * (Plan Phase 6, Queue-Editor). Ein ungueltiger Index wird ignoriert.
     */
    suspend fun skipToQueueIndex(index: Int): AppResult<Unit>

    /**
     * Verschiebt einen Warteschlangeneintrag von [fromIndex] nach
     * [toIndex] (Queue-Editor). Der laufende Titel bleibt derselbe.
     */
    suspend fun moveInQueue(
        fromIndex: Int,
        toIndex: Int,
    ): AppResult<Unit>

    /** Entfernt den Warteschlangeneintrag an [index] (Queue-Editor). */
    suspend fun removeFromQueue(index: Int): AppResult<Unit>

    /**
     * Reiht [song] direkt hinter dem laufenden Titel ein ("als
     * Naechstes"). Bei leerer Queue wird [song] zum ersten Titel.
     */
    suspend fun playNext(song: Song): AppResult<Unit>

    /** Haengt [song] ans Ende der Warteschlange an. */
    suspend fun addToQueueEnd(song: Song): AppResult<Unit>

    suspend fun setShuffle(enabled: Boolean): AppResult<Unit>

    suspend fun setRepeatMode(mode: RepeatMode): AppResult<Unit>

    /** Zuletzt gespeicherter Wiederherstellungszustand (Schritt 5.5). */
    suspend fun lastPersistedState(): PersistedPlayerState?

    /**
     * Live-Momentaufnahme derselben Player-Instanz inklusive aktueller
     * Position; Grundlage fuer Drop-Rest-Gate und -Ueberwachung (11.2),
     * weil [state] die Position nur bei Player-Ereignissen aktualisiert.
     */
    suspend fun snapshotNow(): AppResult<PlaybackState>

    /**
     * Wechselt aktiv auf [song], vorgespult auf [startPositionMs], per
     * Equal-Power-Crossfade (Musik-Workout-Plan Phase 4, "Drop-Landung").
     * Der Wechsel laeuft dienstseitig ueber den vorhandenen
     * Dual-Player-Crossfade (ADR-0007/0012); ist der Crossfade aus oder
     * die Wiedergabe pausiert, erfolgt ein harter Uebergang auf dem einen
     * sessionfuehrenden Player. Die App uebernimmt damit die Queue.
     */
    suspend fun crossfadeTo(
        song: Song,
        startPositionMs: Long,
    ): AppResult<Unit>
}
