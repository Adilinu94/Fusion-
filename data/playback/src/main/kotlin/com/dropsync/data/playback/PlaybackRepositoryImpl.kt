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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
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
                AppResult.success(connection.requirePlayer().toPlaybackState())
            }
        } catch (e: Exception) {
            AppResult.failure(AppError.Unknown(e.message))
        }

    override suspend fun crossfadeTo(
        song: Song,
        startPositionMs: Long,
    ): AppResult<Unit> =
        try {
            withContext(dispatchers.main) {
                val player = connection.requirePlayer()
                attachListener(player)
                val controller = player as? MediaController
                if (controller != null) {
                    // Nur der Service haelt den Zweitspieler: den echten
                    // Crossfade per Custom-Kommando dort ausloesen (ADR-0012).
                    val args =
                        Bundle().apply {
                            putLong(PlaybackCommands.ARG_SONG_ID, song.mediaStoreId)
                            putLong(
                                PlaybackCommands.ARG_START_POSITION_MS,
                                startPositionMs.coerceAtLeast(0),
                            )
                        }
                    controller.sendCustomCommand(
                        SessionCommand(PlaybackCommands.ACTION_CROSSFADE_TO, Bundle.EMPTY),
                        args,
                    )
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
        val snapshot = player.toPlaybackState()
        mutableState.value = snapshot
        scope.launch {
            stateStore.write(
                PersistedPlayerState(
                    queueSongIds = snapshot.queueSongIds,
                    currentSongId = snapshot.currentSongId,
                    positionMs = snapshot.positionMs,
                    shuffleEnabled = snapshot.shuffleEnabled,
                    repeatMode = snapshot.repeatMode,
                ),
            )
        }
    }

    companion object {
        /** Reine Abbildung Player -> Domainzustand; ohne Seiteneffekte. */
        fun Player.toPlaybackState(): PlaybackState {
            val items =
                (0 until mediaItemCount).map { index ->
                    val item = getMediaItemAt(index)
                    QueueItem(
                        mediaId = item.mediaId,
                        songId = item.mediaId.toLongOrNull(),
                        title = item.mediaMetadata.title?.toString() ?: item.mediaId,
                        artist = item.mediaMetadata.artist?.toString(),
                    )
                }
            return PlaybackState(
                isPlaying = isPlaying,
                currentSongId = currentMediaItem?.mediaId?.toLongOrNull(),
                positionMs = currentPosition.coerceAtLeast(0),
                durationMs = duration.coerceAtLeast(0),
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
    }
}
