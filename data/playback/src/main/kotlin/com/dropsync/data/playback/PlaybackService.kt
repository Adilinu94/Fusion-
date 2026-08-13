package com.dropsync.data.playback

import android.content.Intent
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.audiofx.AudioEffect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.core.content.getSystemService
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.dropsync.core.common.AppResult
import com.dropsync.core.model.Song
import com.dropsync.data.audio.AudioPipeline
import com.dropsync.data.audio.DspRenderersFactory
import com.dropsync.data.audio.DspSettingsStore
import com.dropsync.data.audio.OutputFormatInfo
import com.dropsync.data.audio.SourceFormatInfo
import com.dropsync.domain.library.LibraryBrowseRepository
import com.dropsync.domain.library.LibraryRepository
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.guava.future
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import javax.inject.Inject

/**
 * Einziger Wiedergabedienst der App (Bauplan 3.3, Schritt 5).
 *
 * - Genau ein sessionfuehrender ExoPlayer und genau eine MediaSession
 *   leben in diesem Service; beide werden in onDestroy genau einmal
 *   freigegeben (Praezisierung durch ADR-0007).
 * - Media3 verwaltet Audio Focus fuer Musik selbst (Schritt 5.6);
 *   eigene doppelte Fokusverwaltung ist verboten.
 * - Der Player laeuft ueber die eigene Audio-Pipeline (ADR-0005):
 *   Float-Output plus DSP-Kette aus :data:audio.
 * - Der Service ist ausschliesslich fuer Musikwiedergabe da und darf nie
 *   als allgemeiner Timer- oder Workoutdienst missbraucht werden
 *   (Bauplan Abschnitt 4).
 */
@AndroidEntryPoint
class PlaybackService : MediaLibraryService() {
    @Inject
    lateinit var audioPipeline: AudioPipeline

    @Inject
    lateinit var playerStateStore: PlayerStateStore

    @Inject
    lateinit var libraryRepository: LibraryRepository

    @Inject
    lateinit var browseRepository: LibraryBrowseRepository

    @Inject
    lateinit var playbackSettingsStore: PlaybackSettingsStore

    @Inject
    lateinit var dspSettingsStore: DspSettingsStore

    @Inject
    lateinit var audioClock: Media3AudioClock

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Player-Zugriffe (Crossfade, BT-Resume) gehoeren auf den Main-Thread.
    private val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var player: ExoPlayer? = null
    private var session: MediaLibrarySession? = null
    private var audioSessionId: Int = C.AUDIO_SESSION_ID_UNSET
    private var crossfadeController: CrossfadeController? = null
    private var audioDeviceCallback: AudioDeviceCallback? = null
    private var resumeOnBluetoothConnect = false

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()
        // Bit-Perfect (ADR-0009) entscheidet ueber den Sink-Aufbau und
        // gilt deshalb ab Service-Start (kurzer, einmaliger Store-Read).
        val bitPerfect = runBlocking { dspSettingsStore.config.first() }.bitPerfectEnabled
        val exoPlayer =
            ExoPlayer
                .Builder(
                    this,
                    DspRenderersFactory(
                        this,
                        audioPipeline.audioProcessors(),
                        floatOutput = !bitPerfect,
                    ),
                ).setAudioAttributes(
                    AudioAttributes
                        .Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                        .build(),
                    // Media3 uebernimmt den Audio Focus (Schritt 5.6).
                    true,
                ).setHandleAudioBecomingNoisy(true)
                .build()
        exoPlayer.addAnalyticsListener(AudioInfoListener(audioPipeline))
        player = exoPlayer
        // AudioClock (Design Phase 6): Player binden, damit der Rest
        // der App eine interpolierte hoerbare Position lesen kann.
        audioClock.attach(exoPlayer)
        session =
            MediaLibrarySession
                .Builder(
                    this,
                    exoPlayer,
                    LibrarySessionCallback(
                        scope = serviceScope,
                        stateStore = playerStateStore,
                        libraryRepository = libraryRepository,
                        browseRepository = browseRepository,
                        labels = browseLabels(),
                        onCrossfade = ::handleCrossfadeTo,
                    ),
                ).build()
        // MusicFX (Plan Phase 4): Systemequalizer erhaelt die Session-ID;
        // ob er statt der internen Kette wirkt, steuert useSystemEffects.
        audioSessionId = exoPlayer.audioSessionId
        broadcastEffectSession(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION)
        // Crossfade (ADR-0007): Dual-Player mit Equal-Power-Rampen; der
        // Zweitspieler nimmt nie Audio Focus (der Hauptspieler haelt ihn).
        val controller =
            CrossfadeController(
                mainPlayer = exoPlayer,
                secondaryPlayerFactory = {
                    ExoPlayer
                        .Builder(this)
                        .setAudioAttributes(
                            AudioAttributes
                                .Builder()
                                .setUsage(C.USAGE_MEDIA)
                                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                                .build(),
                            false,
                        ).build()
                },
                scope = mainScope,
            )
        crossfadeController = controller
        controller.start()
        mainScope.launch {
            audioPipeline.currentConfig.collect { config ->
                // Nie bei Bit-Perfect ueberblenden (ADR-0009).
                val seconds = if (config.bitPerfectEnabled) 0 else config.crossfadeSeconds
                controller.setCrossfadeSeconds(seconds)
                // Uebergangs-Preset (Mix-Uebergaenge-Plan Phase 2).
                controller.setPreset(config.mixPreset)
            }
        }
        // Option "Bei BT-Verbindung automatisch fortsetzen" (Plan Phase 4).
        mainScope.launch {
            playbackSettingsStore.resumeOnBluetoothConnect.collect { enabled ->
                resumeOnBluetoothConnect = enabled
            }
        }
        registerBluetoothResumeCallback()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = session

    /** Lokalisierte Kategorienamen fuer den Browse-Baum (Plan Phase 6.5). */
    private fun browseLabels(): BrowseLabels =
        BrowseLabels(
            root = getString(R.string.browse_root_title),
            songs = getString(R.string.browse_songs),
            albums = getString(R.string.browse_albums),
            artists = getString(R.string.browse_artists),
            folders = getString(R.string.browse_folders),
        )

    override fun onTaskRemoved(rootIntent: android.content.Intent?) {
        val current = player
        // Ohne aktive Wiedergabe gibt es keinen Grund weiterzulaufen.
        if (current == null || !current.playWhenReady || current.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        broadcastEffectSession(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION)
        audioDeviceCallback?.let { callback ->
            getSystemService<AudioManager>()?.unregisterAudioDeviceCallback(callback)
        }
        audioDeviceCallback = null
        crossfadeController?.release()
        crossfadeController = null
        audioClock.detach()
        // Genau einmal freigeben (Abnahme Schritt 5).
        session?.release()
        session = null
        player?.release()
        player = null
        audioPipeline.onPlaybackReleased()
        serviceScope.cancel()
        mainScope.cancel()
        super.onDestroy()
    }

    /**
     * BT-Reconnect (Plan Phase 4): sobald ein A2DP-Geraet erscheint und
     * die Option aktiv ist, setzt eine pausierte Queue automatisch fort.
     */
    private fun registerBluetoothResumeCallback() {
        val audioManager = getSystemService<AudioManager>() ?: return
        val callback =
            object : AudioDeviceCallback() {
                override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
                    if (!resumeOnBluetoothConnect) return
                    if (addedDevices.none { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP }) return
                    val current = player ?: return
                    if (!current.playWhenReady && current.mediaItemCount > 0) {
                        current.play()
                    }
                }
            }
        audioDeviceCallback = callback
        audioManager.registerAudioDeviceCallback(callback, Handler(Looper.getMainLooper()))
    }

    private fun broadcastEffectSession(action: String) {
        if (audioSessionId == C.AUDIO_SESSION_ID_UNSET) return
        sendBroadcast(
            Intent(action)
                .putExtra(AudioEffect.EXTRA_AUDIO_SESSION, audioSessionId)
                .putExtra(AudioEffect.EXTRA_PACKAGE_NAME, packageName)
                .putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_MUSIC),
        )
    }

    /**
     * Empfaengt das Drop-Landungs-Kommando (Musik-Workout-Plan Phase 4):
     * loest den Song auf und uebergibt ihn dem [CrossfadeController]. Der
     * eigentliche Crossfade laeuft auf dem Main-Thread, weil er Player
     * beruehrt.
     */
    private fun handleCrossfadeTo(
        songId: Long,
        startPositionMs: Long,
    ) {
        serviceScope.launch {
            val song = (libraryRepository.getSong(songId) as? AppResult.Success)?.value ?: return@launch
            val item = MediaItemFactory.fromSong(song)
            mainScope.launch { crossfadeController?.crossfadeTo(item, startPositionMs) }
        }
    }

    /** Kategorienamen des Browse-Baums (Plan Phase 6.5). */
    private data class BrowseLabels(
        val root: String,
        val songs: String,
        val albums: String,
        val artists: String,
        val folders: String,
    )

    /**
     * Browse-Baum fuer externe Controller (Plan Phase 6.5): Root ->
     * Kategorien (Titel/Alben/Interpreten/Ordner) -> abspielbare Songs.
     * Android Auto und BT-Browsing nutzen denselben Baum. Auto-Resume
     * (Plan Phase 4): BT-Reconnect und Notification-Resume stellen Queue
     * und Position aus dem PlayerStateStore wieder her.
     */
    private class LibrarySessionCallback(
        private val scope: CoroutineScope,
        private val stateStore: PlayerStateStore,
        private val libraryRepository: LibraryRepository,
        private val browseRepository: LibraryBrowseRepository,
        private val labels: BrowseLabels,
        private val onCrossfade: (Long, Long) -> Unit,
    ) : MediaLibrarySession.Callback {
        /** Meldet das eigene Drop-Landungs-Kommando als verfuegbar an. */
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): MediaSession.ConnectionResult {
            val sessionCommands =
                MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS
                    .buildUpon()
                    .add(SessionCommand(PlaybackCommands.ACTION_CROSSFADE_TO, Bundle.EMPTY))
                    .build()
            return MediaSession.ConnectionResult
                .AcceptedResultBuilder(session)
                .setAvailableSessionCommands(sessionCommands)
                .build()
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            if (customCommand.customAction == PlaybackCommands.ACTION_CROSSFADE_TO) {
                val songId = args.getLong(PlaybackCommands.ARG_SONG_ID, -1L)
                val startPositionMs = args.getLong(PlaybackCommands.ARG_START_POSITION_MS, 0L)
                if (songId >= 0) onCrossfade(songId, startPositionMs)
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }
            return super.onCustomCommand(session, controller, customCommand, args)
        }

        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: List<MediaItem>,
        ): ListenableFuture<List<MediaItem>> =
            Futures.immediateFuture(mediaItems.map(MediaItemFactory::resolveForPlayback))

        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> =
            scope.future {
                val state =
                    stateStore.read()
                        ?: throw UnsupportedOperationException("Kein gespeicherter Wiedergabezustand")
                val songs =
                    state.queueSongIds.mapNotNull { id ->
                        (libraryRepository.getSong(id) as? AppResult.Success)?.value
                    }
                if (songs.isEmpty()) {
                    throw UnsupportedOperationException("Queue nicht wiederherstellbar")
                }
                val startIndex =
                    songs
                        .indexOfFirst { it.mediaStoreId == state.currentSongId }
                        .coerceAtLeast(0)
                MediaSession.MediaItemsWithStartPosition(
                    songs.map(MediaItemFactory::fromSong),
                    startIndex,
                    state.positionMs,
                )
            }

        override fun onGetLibraryRoot(
            mediaSession: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> =
            Futures.immediateFuture(
                LibraryResult.ofItem(
                    browsableItem(ROOT_ID, labels.root, MediaMetadata.MEDIA_TYPE_FOLDER_MIXED),
                    params,
                ),
            )

        override fun onGetChildren(
            mediaSession: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> =
            scope.future {
                LibraryResult.ofItemList(childrenOf(parentId), params)
            }

        private suspend fun childrenOf(parentId: String): List<MediaItem> =
            when {
                parentId == ROOT_ID -> {
                    listOf(
                        browsableItem(CAT_SONGS, labels.songs, MediaMetadata.MEDIA_TYPE_PLAYLIST),
                        browsableItem(CAT_ALBUMS, labels.albums, MediaMetadata.MEDIA_TYPE_FOLDER_ALBUMS),
                        browsableItem(CAT_ARTISTS, labels.artists, MediaMetadata.MEDIA_TYPE_FOLDER_ARTISTS),
                        browsableItem(CAT_FOLDERS, labels.folders, MediaMetadata.MEDIA_TYPE_FOLDER_MIXED),
                    )
                }

                parentId == CAT_SONGS -> {
                    libraryRepository.availableSongs.first().map(::playableItem)
                }

                parentId == CAT_ALBUMS -> {
                    browseRepository.albums.first().map {
                        browsableItem(PREFIX_ALBUM + it.title, it.title, MediaMetadata.MEDIA_TYPE_ALBUM)
                    }
                }

                parentId == CAT_ARTISTS -> {
                    browseRepository.artists.first().map {
                        browsableItem(PREFIX_ARTIST + it.name, it.name, MediaMetadata.MEDIA_TYPE_ARTIST)
                    }
                }

                parentId == CAT_FOLDERS -> {
                    browseRepository.folders.first().map {
                        browsableItem(
                            PREFIX_FOLDER + it.relativePath,
                            it.relativePath,
                            MediaMetadata.MEDIA_TYPE_FOLDER_MIXED,
                        )
                    }
                }

                parentId.startsWith(PREFIX_ALBUM) -> {
                    browseRepository
                        .songsByAlbum(parentId.removePrefix(PREFIX_ALBUM))
                        .first()
                        .map(::playableItem)
                }

                parentId.startsWith(PREFIX_ARTIST) -> {
                    browseRepository
                        .songsByArtist(parentId.removePrefix(PREFIX_ARTIST))
                        .first()
                        .map(::playableItem)
                }

                parentId.startsWith(PREFIX_FOLDER) -> {
                    browseRepository
                        .songsByFolder(parentId.removePrefix(PREFIX_FOLDER))
                        .first()
                        .map(::playableItem)
                }

                else -> {
                    emptyList()
                }
            }

        private fun browsableItem(
            mediaId: String,
            title: String,
            mediaType: Int,
        ): MediaItem =
            MediaItem
                .Builder()
                .setMediaId(mediaId)
                .setMediaMetadata(
                    MediaMetadata
                        .Builder()
                        .setTitle(title)
                        .setIsBrowsable(true)
                        .setIsPlayable(false)
                        .setMediaType(mediaType)
                        .build(),
                ).build()

        private fun playableItem(song: Song): MediaItem {
            val base = MediaItemFactory.fromSong(song)
            return base
                .buildUpon()
                .setMediaMetadata(
                    base.mediaMetadata
                        .buildUpon()
                        .setIsBrowsable(false)
                        .setIsPlayable(true)
                        .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                        .build(),
                ).build()
        }

        private companion object {
            const val ROOT_ID = "root"
            const val CAT_SONGS = "cat_songs"
            const val CAT_ALBUMS = "cat_albums"
            const val CAT_ARTISTS = "cat_artists"
            const val CAT_FOLDERS = "cat_folders"
            const val PREFIX_ALBUM = "album:"
            const val PREFIX_ARTIST = "artist:"
            const val PREFIX_FOLDER = "folder:"
        }
    }

    /**
     * Meldet Quellformat und Audiotrack-Konfiguration an die Pipeline;
     * Grundlage der Audioinformationen (Plan Phase 1).
     */
    @OptIn(UnstableApi::class)
    private class AudioInfoListener(
        private val pipeline: AudioPipeline,
    ) : AnalyticsListener {
        override fun onAudioInputFormatChanged(
            eventTime: AnalyticsListener.EventTime,
            format: Format,
            decoderReuseEvaluation: androidx.media3.exoplayer.DecoderReuseEvaluation?,
        ) {
            pipeline.onSourceFormatChanged(
                SourceFormatInfo(
                    codecMimeType = format.sampleMimeType,
                    bitrateBps = format.bitrate.takeIf { it != Format.NO_VALUE },
                    sampleRateHz = format.sampleRate.takeIf { it != Format.NO_VALUE },
                    channelCount = format.channelCount.takeIf { it != Format.NO_VALUE },
                    bitDepth = bitDepthOf(format.pcmEncoding),
                ),
            )
        }

        override fun onAudioTrackInitialized(
            eventTime: AnalyticsListener.EventTime,
            audioTrackConfig: AudioSink.AudioTrackConfig,
        ) {
            pipeline.onAudioTrackInitialized(
                OutputFormatInfo(
                    sampleRateHz = audioTrackConfig.sampleRate,
                    encodingName = encodingName(audioTrackConfig.encoding),
                    isFloat = audioTrackConfig.encoding == C.ENCODING_PCM_FLOAT,
                ),
            )
        }

        private fun bitDepthOf(pcmEncoding: Int): Int? =
            when (pcmEncoding) {
                C.ENCODING_PCM_8BIT -> 8
                C.ENCODING_PCM_16BIT, C.ENCODING_PCM_16BIT_BIG_ENDIAN -> 16
                C.ENCODING_PCM_24BIT, C.ENCODING_PCM_24BIT_BIG_ENDIAN -> 24
                C.ENCODING_PCM_32BIT, C.ENCODING_PCM_32BIT_BIG_ENDIAN -> 32
                C.ENCODING_PCM_FLOAT -> 32
                else -> null
            }

        private fun encodingName(encoding: Int): String =
            when (encoding) {
                C.ENCODING_PCM_FLOAT -> "32-Bit Float"
                C.ENCODING_PCM_32BIT -> "32-Bit PCM"
                C.ENCODING_PCM_24BIT -> "24-Bit PCM"
                C.ENCODING_PCM_16BIT -> "16-Bit PCM"
                C.ENCODING_PCM_8BIT -> "8-Bit PCM"
                else -> "Encoding $encoding"
            }
    }
}
