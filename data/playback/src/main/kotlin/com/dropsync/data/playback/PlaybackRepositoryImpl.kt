package com.dropsync.data.playback

import android.os.Bundle
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import com.dropsync.core.common.AppError
import com.dropsync.core.common.AppResult
import com.dropsync.core.common.DispatcherProvider
import com.dropsync.core.model.Song
import com.dropsync.domain.playback.PersistedPlayerState
import com.dropsync.domain.playback.PlaybackRepository
import com.dropsync.domain.playback.PlaybackState
import com.dropsync.domain.playback.QueueItem
import com.dropsync.domain.playback.RepeatMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Einziger App-Zugang zur Wiedergabe (Bauplan 3.3, Schritt 5).
 *
 * Alle Kommandos laufen auf dem Main-Dispatcher gegen denselben
 * MediaController; der Zustand wird ueber einen Player.Listener
 * beobachtet, damit auch Sperrbildschirm- und Bluetooth-Steuerung
 * (Schritt 5, Abnahme 1) im App-Zustand ankommen.
 */
class PlaybackRepositoryImpl(
    private val connection: PlayerConnection,
    private val stateStore: PlayerStateStore,
    private val dispatchers: DispatcherProvider,
) : PlaybackRepository {
    private val scope = CoroutineScope(SupervisorJob() + dispatchers.io)
    private val mutableState = MutableStateFlow(PlaybackState())
    private var listenerAttached = false

    override val state: Flow<PlaybackState> = mutableState.asStateFlow()

    override suspend fun setQueue(
        songs: List<Song>,
        startIndex: Int,
        playWhenReady: Boolean,
    ): AppResult<Unit> {
        if (songs.isEmpty() || startIndex !in songs.indices) {
            return AppResult.failure(AppError.MediaUnavailable(null))
        }
        return command { player ->
            player.setMediaItems(songs.map(MediaItemFactory::fromSong), startIndex, 0)
            player.prepare()
            player.playWhenReady = playWhenReady
        }
    }

    override suspend fun play(): AppResult<Unit> = command { it.play() }

    override suspend fun pause(): AppResult<Unit> = command { it.pause() }

    override suspend fun seekTo(positionMs: Long): AppResult<Unit> = command { it.seekTo(positionMs) }

    override suspend fun skipToNext(): AppResult<Unit> = command { it.seekToNextMediaItem() }

    override suspend fun skipToPrevious(): AppResult<Unit> = command { it.seekToPreviousMediaItem() }

    override suspend fun skipToQueueIndex(index: Int): AppResult<Unit> =
        command { player ->
            if (index in 0 until player.mediaItemCount) player.seekTo(index, 0L)
        }

    override suspend fun moveInQueue(
        fromIndex: Int,
        toIndex: Int,
    ): AppResult<Unit> =
        command { player ->
            val count = player.mediaItemCount
            if (fromIndex in 0 until count && toIndex in 0 until count && fromIndex != toIndex) {
                player.moveMediaItem(fromIndex, toIndex)
            }
        }

    override suspend fun removeFromQueue(index: Int): AppResult<Unit> =
        command { player ->
            if (index in 0 until player.mediaItemCount) player.removeMediaItem(index)
        }

    override suspend fun playNext(song: Song): AppResult<Unit> =
        command { player ->
            val insertIndex =
                if (player.mediaItemCount == 0) 0 else player.currentMediaItemIndex + 1
            player.addMediaItem(insertIndex, MediaItemFactory.fromSong(song))
            if (player.playbackState == Player.STATE_IDLE) player.prepare()
        }

    override suspend fun addToQueueEnd(song: Song): AppResult<Unit> =
        command { player ->
            player.addMediaItem(MediaItemFactory.fromSong(song))
            if (player.playbackState == Player.STATE_IDLE) player.prepare()
        }

    override suspend fun setShuffle(enabled: Boolean): AppResult<Unit> = command { it.shuffleModeEnabled = enabled }

    override suspend fun setPlaybackSpeed(speed: Float): AppResult<Unit> =
        command { it.setPlaybackSpeed(speed.coerceIn(MIN_PLAYBACK_SPEED, MAX_PLAYBACK_SPEED)) }

    override suspend fun setRepeatMode(mode: RepeatMode): AppResult<Unit> =
        command {
            it.repeatMode =
                when (mode) {
                    RepeatMode.OFF -> Player.REPEAT_MODE_OFF
                    RepeatMode.ONE -> Player.REPEAT_MODE_ONE
                    RepeatMode.ALL -> Player.REPEAT_MODE_ALL
                }
        }

    override suspend fun lastPersistedState(): PersistedPlayerState? = stateStore.read()

    override suspend fun snapshotNow(): AppResult<PlaybackState> =
        try {
            withContext(dispatchers.main) {
                AppResult.success(connection.requirePlayer().toPlaybackState(lastQueue))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppResult.failure(AppError.Unknown(e.message))
        }

    override suspend fun playSongAt(
        song: Song,
        startPositionMs: Long,
    ): AppResult<Unit> =
        try {
            withContext(dispatchers.main) {
                val player = connection.requirePlayer()
                attachListener(player)
                val controller = player as? MediaController
                if (controller != null) {
                    // Nur der Service haelt den Player: den Wechsel per
                    // Custom-Kommando dort ausloesen.
                    val args =
                        Bundle().apply {
                            putLong(PlaybackCommands.ARG_SONG_ID, song.mediaStoreId)
                            putLong(
                                PlaybackCommands.ARG_START_POSITION_MS,
                                startPositionMs.coerceAtLeast(0),
                            )
                        }
                    controller
                        .sendCustomCommand(
                            SessionCommand(PlaybackCommands.ACTION_PLAY_SONG_AT, Bundle.EMPTY),
                            args,
                        ).awaitResult()
                        .throwOnFailure()
                } else {
                    // Fallback ohne MediaController: vorgespulter harter Wechsel.
                    player.setMediaItem(
                        MediaItemFactory.fromSong(song),
                        startPositionMs.coerceAtLeast(0),
                    )
                    player.prepare()
                    player.play()
                }
                publishAndPersist(player)
            }
            AppResult.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppResult.failure(AppError.Unknown(e.message))
        }

    /**
     * Umbauplan Phase 10.1: wartet auf den Session-Result des Custom
     * Commands, statt sofort Erfolg zu melden. Nicht erfolgreiche Codes
     * (Permission, Bad Value, Unknown) werfen, damit Prepare/Arm sich
     * nicht ueberholen und Fehler sichtbar werden.
     */
    private suspend fun com.google.common.util.concurrent.ListenableFuture<
        androidx.media3.session.SessionResult,
    >.awaitResult(): androidx.media3.session.SessionResult =
        kotlinx.coroutines.suspendCancellableCoroutine { cont ->
            addListener(
                {
                    cont.resumeWith(
                        runCatching { get() },
                    )
                },
                { it.run() },
            )
            cont.invokeOnCancellation { cancel(false) }
        }

    private fun androidx.media3.session.SessionResult.throwOnFailure() {
        if (resultCode != androidx.media3.session.SessionResult.RESULT_SUCCESS) {
            throw IllegalStateException("MediaSession-Kommando fehlgeschlagen (Code $resultCode)")
        }
    }

    /**
     * Scrubbing-Modus (Media3 1.8+, P1-Fix): nur der Service haelt den
     * ExoPlayer, deshalb laeuft der Schalter als Custom-Kommando. Ein
     * fehlgeschlagenes Kommando ist unkritisch — Seeks funktionieren auch
     * ohne den Modus, nur weniger effizient.
     */
    override suspend fun setScrubbingMode(enabled: Boolean): AppResult<Unit> =
        try {
            withContext(dispatchers.main) {
                val controller = connection.requirePlayer() as? MediaController
                if (controller != null) {
                    val args = Bundle().apply { putBoolean(PlaybackCommands.ARG_SCRUBBING_ENABLED, enabled) }
                    controller.sendCustomCommand(
                        SessionCommand(PlaybackCommands.ACTION_SET_SCRUBBING_MODE, Bundle.EMPTY),
                        args,
                    )
                }
            }
            AppResult.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppResult.failure(AppError.Unknown(e.message))
        }

    private suspend fun command(block: (Player) -> Unit): AppResult<Unit> =
        try {
            withContext(dispatchers.main) {
                val player = connection.requirePlayer()
                attachListener(player)
                block(player)
                publishAndPersist(player)
            }
            AppResult.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppResult.failure(AppError.Unknown(e.message))
        }

    /** Muss auf dem Main-Dispatcher laufen (MediaController-Vertrag). */
    private fun attachListener(player: Player) {
        if (listenerAttached) return
        listenerAttached = true
        player.addListener(
            object : Player.Listener {
                override fun onEvents(
                    eventsPlayer: Player,
                    events: Player.Events,
                ) {
                    if (events.containsAny(
                            Player.EVENT_IS_PLAYING_CHANGED,
                            Player.EVENT_MEDIA_ITEM_TRANSITION,
                            Player.EVENT_TIMELINE_CHANGED,
                            Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED,
                            Player.EVENT_REPEAT_MODE_CHANGED,
                            Player.EVENT_POSITION_DISCONTINUITY,
                            Player.EVENT_PLAYBACK_STATE_CHANGED,
                            Player.EVENT_PLAYBACK_PARAMETERS_CHANGED,
                        )
                    ) {
                        // Auch externe Steuerung (Notification, Bluetooth)
                        // landet so im Zustand und im Restore-Speicher (5.5).
                        publishAndPersist(eventsPlayer)
                    }
                }
            },
        )
    }

    private fun publishAndPersist(player: Player) {
        val snapshot = player.toPlaybackState(lastQueue)
        // Queue-Liste nur bei echter Aenderung neu aufbauen (P1-Fix):
        // `toPlaybackState` erzeugte fuer JEDES Player-Ereignis so viele
        // QueueItem-Objekte, wie Titel in der Warteschlange stehen — bei
        // 500 Titeln also 500 Allokationen pro Positionssprung.
        lastQueue = snapshot.queue
        mutableState.value = snapshot
        // Persistenz entprellt: bei Scrubbing feuert
        // EVENT_POSITION_DISCONTINUITY in dichter Folge, und jeder Schreibvorgang
        // serialisiert die vollstaendige Queue nach DataStore. Der letzte
        // Zustand gewinnt; conflate + Delay fasst die Serie zusammen.
        pendingPersist.value = snapshot
    }

    /**
     * Letzter bekannter Queue-Zustand. Dient als Vergleichsbasis, damit
     * [toPlaybackState] die Liste nur bei tatsaechlicher Timeline-Aenderung
     * neu aufbaut.
     */
    private var lastQueue: List<QueueItem> = emptyList()

    /** Entprellte Persistenz-Warteschlange (nur der letzte Zustand zaehlt). */
    private val pendingPersist = MutableStateFlow<PlaybackState?>(null)

    init {
        scope.launch {
            pendingPersist
                .filterNotNull()
                .conflate()
                .collect { snapshot ->
                    stateStore.write(
                        PersistedPlayerState(
                            queueSongIds = snapshot.queueSongIds,
                            currentSongId = snapshot.currentSongId,
                            positionMs = snapshot.positionMs,
                            shuffleEnabled = snapshot.shuffleEnabled,
                            repeatMode = snapshot.repeatMode,
                        ),
                    )
                    delay(PERSIST_DEBOUNCE_MS)
                }
        }
    }

    companion object {
        /** Sicherer Tempo-Bereich (media3-Issue #1101: Extremwerte werfen auf Geraeten). */
        const val MIN_PLAYBACK_SPEED = 0.5f
        const val MAX_PLAYBACK_SPEED = 2.0f

        /**
         * Mindestabstand zweier Persistenz-Schreibvorgaenge. Der Restore-Zustand
         * darf ein paar hundert Millisekunden hinterherlaufen; entscheidend ist,
         * dass eine Scrub-Serie nicht in Dutzende DataStore-Writes muendet.
         */
        const val PERSIST_DEBOUNCE_MS = 400L

        /**
         * Reine Abbildung Player -> Domainzustand; ohne Seiteneffekte.
         *
         * [knownQueue] ist der zuletzt gelesene Queue-Zustand. Stimmen Laenge
         * und Reihenfolge der mediaIds noch, wird die Liste WIEDERVERWENDET
         * statt neu aufgebaut — bei langen Warteschlangen der Unterschied
         * zwischen null und mehreren hundert Allokationen pro Player-Ereignis.
         */
        fun Player.toPlaybackState(knownQueue: List<QueueItem> = emptyList()): PlaybackState {
            val items =
                if (queueMatches(knownQueue)) {
                    knownQueue
                } else {
                    (0 until mediaItemCount).map { index ->
                        val item = getMediaItemAt(index)
                        QueueItem(
                            mediaId = item.mediaId,
                            songId = item.mediaId.toLongOrNull(),
                            title = item.mediaMetadata.title?.toString() ?: item.mediaId,
                            artist = item.mediaMetadata.artist?.toString(),
                        )
                    }
                }
            return PlaybackState(
                isPlaying = isPlaying,
                currentSongId = currentMediaItem?.mediaId?.toLongOrNull(),
                positionMs = currentPosition.coerceAtLeast(0),
                durationMs = duration.coerceAtLeast(0),
                playbackSpeed = playbackParameters.speed,
                shuffleEnabled = shuffleModeEnabled,
                repeatMode =
                    when (repeatMode) {
                        Player.REPEAT_MODE_ONE -> RepeatMode.ONE
                        Player.REPEAT_MODE_ALL -> RepeatMode.ALL
                        else -> RepeatMode.OFF
                    },
                queueSongIds = items.mapNotNull { it.songId },
                currentIndex = if (mediaItemCount == 0) -1 else currentMediaItemIndex,
                queue = items,
            )
        }

        /** True, wenn [knownQueue] die aktuelle Timeline noch exakt abbildet. */
        private fun Player.queueMatches(knownQueue: List<QueueItem>): Boolean {
            if (knownQueue.size != mediaItemCount) return false
            for (index in 0 until mediaItemCount) {
                if (knownQueue[index].mediaId != getMediaItemAt(index).mediaId) return false
            }
            return true
        }
    }
}
