package com.dropsync.data.playback

import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import com.dropsync.domain.playback.AudioRouteProfile
import com.dropsync.domain.playback.PersistedPlayerState
import com.dropsync.domain.playback.RouteProfileRepository
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Minimaler Player auf SimpleBasePlayer-Basis (Media3-Testmuster): haelt
 * Playlist, Position und Flags selbst und zeichnet Seeks auf. Ersetzt den
 * ExoPlayer in Repository-Tests; MediaController-spezifische Pfade
 * (Custom-Commands) bleiben bewusst aussen vor — der Fake ist kein
 * MediaController, das Repository faellt auf die dokumentierten
 * Nicht-Controller-Pfade zurueck.
 */
internal class FakePlayer : SimpleBasePlayer(Looper.getMainLooper()) {
    private var nextUid = 0
    private var playlist: List<MediaItemData> = emptyList()
    private var index = 0
    private var positionMs = 0L
    private var playWhenReady = false
    private var playbackState = Player.STATE_IDLE
    private var shuffleEnabled = false
    private var repeatMode = Player.REPEAT_MODE_OFF
    private var playbackParameters = PlaybackParameters.DEFAULT
    private var loading = false

    /** Aufgezeichnete Seeks als (MediaItemIndex, PositionMs). */
    val seekCalls = mutableListOf<Pair<Int, Long>>()

    val playlistMediaIds: List<String>
        get() = playlist.map { it.mediaItem.mediaId }

    /** Position ohne Event-Logik setzen (fuer snapshotNow-Pruefungen). */
    fun setPositionSilently(ms: Long) {
        positionMs = ms
        invalidateState()
    }

    /** Ladezustand ohne Event-Logik setzen (fuer Clock-Snapshots). */
    fun setLoadingSilently(value: Boolean) {
        loading = value
        invalidateState()
    }

    override fun getState(): State =
        State
            .Builder()
            .setAvailableCommands(COMMANDS)
            .setPlaylist(playlist)
            .setCurrentMediaItemIndex(index)
            .setContentPositionMs(positionMs)
            .setPlayWhenReady(playWhenReady, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setPlaybackState(playbackState)
            .setIsLoading(loading)
            .setShuffleModeEnabled(shuffleEnabled)
            .setRepeatMode(repeatMode)
            .setPlaybackParameters(playbackParameters)
            .build()

    override fun handleSetMediaItems(
        mediaItems: List<MediaItem>,
        startIndex: Int,
        startPositionMs: Long,
    ): ListenableFuture<*> {
        playlist = mediaItems.map { data(it) }
        index = startIndex.coerceIn(0, (playlist.size - 1).coerceAtLeast(0))
        positionMs = startPositionMs
        invalidateState()
        return Futures.immediateVoidFuture()
    }

    override fun handleAddMediaItems(
        index: Int,
        mediaItems: List<MediaItem>,
    ): ListenableFuture<*> {
        val insertAt = index.coerceIn(0, playlist.size)
        val added = mediaItems.map { data(it) }
        playlist = playlist.toMutableList().apply { addAll(insertAt, added) }
        invalidateState()
        return Futures.immediateVoidFuture()
    }

    override fun handleMoveMediaItems(
        fromIndex: Int,
        toIndex: Int,
        newIndex: Int,
    ): ListenableFuture<*> {
        val moved = playlist.subList(fromIndex, toIndex).toList()
        val rest = playlist.toMutableList().apply { removeAll(moved) }
        rest.addAll(newIndex.coerceIn(0, rest.size), moved)
        playlist = rest
        invalidateState()
        return Futures.immediateVoidFuture()
    }

    override fun handleRemoveMediaItems(
        fromIndex: Int,
        toIndex: Int,
    ): ListenableFuture<*> {
        playlist = playlist.filterIndexed { i, _ -> i < fromIndex || i >= toIndex }
        if (index >= playlist.size) index = (playlist.size - 1).coerceAtLeast(0)
        invalidateState()
        return Futures.immediateVoidFuture()
    }

    override fun handleSeek(
        mediaItemIndex: Int,
        positionMs: Long,
        seekCommand: Int,
    ): ListenableFuture<*> {
        seekCalls += mediaItemIndex to positionMs
        index = mediaItemIndex
        this.positionMs = positionMs
        invalidateState()
        return Futures.immediateVoidFuture()
    }

    override fun handlePrepare(): ListenableFuture<*> {
        playbackState = Player.STATE_READY
        invalidateState()
        return Futures.immediateVoidFuture()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        this.playWhenReady = playWhenReady
        invalidateState()
        return Futures.immediateVoidFuture()
    }

    override fun handleSetShuffleModeEnabled(shuffleModeEnabled: Boolean): ListenableFuture<*> {
        this.shuffleEnabled = shuffleModeEnabled
        invalidateState()
        return Futures.immediateVoidFuture()
    }

    override fun handleSetRepeatMode(repeatMode: Int): ListenableFuture<*> {
        this.repeatMode = repeatMode
        invalidateState()
        return Futures.immediateVoidFuture()
    }

    override fun handleSetPlaybackParameters(playbackParameters: PlaybackParameters): ListenableFuture<*> {
        this.playbackParameters = playbackParameters
        invalidateState()
        return Futures.immediateVoidFuture()
    }

    private fun data(item: MediaItem): MediaItemData =
        MediaItemData
            .Builder(nextUid++)
            .setMediaItem(item)
            .setDurationUs(DEFAULT_DURATION_US)
            .build()

    private companion object {
        const val DEFAULT_DURATION_US = 180_000_000L

        val COMMANDS: Player.Commands =
            Player.Commands
                .Builder()
                .addAll(
                    Player.COMMAND_PLAY_PAUSE,
                    Player.COMMAND_PREPARE,
                    Player.COMMAND_STOP,
                    Player.COMMAND_SEEK_TO_MEDIA_ITEM,
                    Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
                    Player.COMMAND_SEEK_TO_DEFAULT_POSITION,
                    Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                    Player.COMMAND_SEEK_TO_NEXT,
                    Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
                    Player.COMMAND_SEEK_TO_PREVIOUS,
                    Player.COMMAND_SEEK_BACK,
                    Player.COMMAND_SEEK_FORWARD,
                    Player.COMMAND_GET_TIMELINE,
                    Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
                    Player.COMMAND_CHANGE_MEDIA_ITEMS,
                    Player.COMMAND_SET_MEDIA_ITEM,
                    Player.COMMAND_SET_SPEED_AND_PITCH,
                    Player.COMMAND_SET_SHUFFLE_MODE,
                    Player.COMMAND_SET_REPEAT_MODE,
                ).build()
    }
}

/** Liefert genau den eingestellten Player; optional mit erzwungenem Fehler. */
internal class FakePlayerConnection(
    var player: Player? = FakePlayer(),
) : PlayerConnection {
    var failure: Exception? = null

    override suspend fun requirePlayer(): Player {
        failure?.let { throw it }
        return player ?: throw IllegalStateException("kein Player verbunden")
    }
}

/** Zeichnet Persistenz-Schreibvorgaenge auf (Debounce-Pruefungen). */
internal class FakePlayerStateStore : PlayerStateStore {
    val writes = mutableListOf<PersistedPlayerState>()
    var stored: PersistedPlayerState? = null

    override suspend fun read(): PersistedPlayerState? = stored

    override suspend fun write(state: PersistedPlayerState) {
        writes += state
        stored = state
    }
}

/** Latenzprofil-Port mit setzbarem Profil (Snapshot-Tests). */
internal class FakeRouteProfiles(
    profile: AudioRouteProfile? = null,
) : RouteProfileRepository {
    private val flows = MutableStateFlow(profile)

    var profile: AudioRouteProfile?
        get() = flows.value
        set(value) {
            flows.value = value
        }

    override val currentProfile: Flow<AudioRouteProfile?> = flows

    override suspend fun currentLatencyMs(): Long? = flows.value?.estimatedLatencyMs

    override suspend fun markStale() {
        flows.value = flows.value?.copy(confidence = AudioRouteProfile.Confidence.STALE)
    }

    override suspend fun upsert(profile: AudioRouteProfile) {
        flows.value = profile
    }
}
