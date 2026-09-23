package com.dropsync.data.playback

import android.content.Intent
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.audiofx.AudioEffect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.OptIn
import androidx.core.content.getSystemService
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.dropsync.core.common.AppResult
import com.dropsync.core.common.DispatcherProvider
import com.dropsync.core.model.Song
import com.dropsync.data.audio.AudioPipeline
import com.dropsync.data.audio.DspRenderersFactory
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.guava.future
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
@OptIn(UnstableApi::class)
class PlaybackService : MediaLibraryService() {
    @Inject
    lateinit var audioPipeline: AudioPipeline

    @Inject
    lateinit var bitPerfectGateway: com.dropsync.data.audio.BitPerfectGateway

    @Inject
    lateinit var trackAnalysisRepository: com.dropsync.domain.audio.TrackAnalysisRepository

    @Inject
    lateinit var playerStateStore: PlayerStateStore

    @Inject
    lateinit var libraryRepository: LibraryRepository

    @Inject
    lateinit var browseRepository: LibraryBrowseRepository

    @Inject
    lateinit var playbackSettingsStore: PlaybackSettingsStore

    @Inject
    lateinit var audioClock: Media3AudioClock

    @Inject
    lateinit var dropLandingArmer: DropLandingArmer

    @Inject
    lateinit var dispatchers: DispatcherProvider

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Player-Zugriffe (Drop-Landung, BT-Resume) gehoeren auf den Main-Thread.
    private val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var player: ExoPlayer? = null
    private var session: MediaLibrarySession? = null
    private var audioSessionId: Int = C.AUDIO_SESSION_ID_UNSET
    private var audioDeviceCallback: AudioDeviceCallback? = null
    private var resumeOnBluetoothConnect = false

    /**
     * Befund 4.4: genau EIN ReplayGain-Lauf ist aktuell. Jeder Titelwechsel
     * bricht den Vorgaenger ab, loescht sofort und erhoeht die Generation —
     * ein langsamer (ueberholter) Lauf darf den Gain des Vortitels nicht
     * spaeter ueberschreiben. Laeufe ohne Main-Zugriff brauchen kein
     * Main-Confinement; der Listener laeuft auf dem Player-Thread.
     */
    private var replayGainJob: Job? = null
    private var replayGainGeneration = 0L

    private companion object {
        const val TAG = "PlaybackService"
    }

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()
        val exoPlayer =
            ExoPlayer
                .Builder(
                    this,
                    DspRenderersFactory(
                        this,
                        audioPipeline.audioProcessors(),
                        // Befund 2.8: Float-Output folgt der Konfiguration —
                        // Bit-Perfect (ADR-0009) erzwingt Int16- Durchreichung,
                        // sonst bleibt der Hi-Res-Float-Pfad aktiv. Der
                        // Wert gilt fuer den gesamten Player-Lebenszyklus;
                        // ein Wechsel erfordert den Service-Neustart
                        // (unten verdrahtet).
                        floatOutput = !audioPipeline.currentConfig.value.bitPerfectEnabled,
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
                // Workout-Normalfall: Display aus, Geraet in der Tasche.
                // Ohne WakeLock kann Doze/OEM-Management die Wiedergabe
                // unterbrechen (Audit Fokus 1, Befund 3).
                .setWakeMode(C.WAKE_MODE_LOCAL)
                .build()
        exoPlayer.addAnalyticsListener(AudioInfoListener(audioPipeline))
        player = exoPlayer
        // AudioClock (Design Phase 6): Player binden, damit der Rest
        // der App eine interpolierte hoerbare Position lesen kann.
        audioClock.attach(exoPlayer)
        // Armierte Drop-Landung (MP-3): PlayerMessage-Landung laeuft auf
        // dem Main-Thread des Service; der Armer wird hier ueber den
        // schmalen LandingPlayer-Port gebunden (Audit 2026-09-22).
        dropLandingArmer.attach(ExoLandingPlayer(exoPlayer), mainScope)
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
                        ownPackageName = packageName,
                        onPlaySongAt = ::handlePlaySongAt,
                        onSetScrubbingMode = ::handleSetScrubbingMode,
                        onArmLanding = ::handleArmLanding,
                        onCancelLanding = ::handleCancelLanding,
                    ),
                ).build()
        // MusicFX (Plan Phase 4): Systemequalizer erhaelt die Session-ID;
        // ob er statt der internen Kette wirkt, steuert useSystemEffects.
        // Reconnect-Fix (Befund 3.2): Die Session-ID kann sich bei einem
        // Sink-Neuaufbau (BT an/aus, USB-DAC) aendern — einmaliges Lesen
        // bricht die MusicFX-Bindung. Der Listener aktualisiert und
        // rebroadcastet bei jeder Aenderung.
        audioSessionId = exoPlayer.audioSessionId
        exoPlayer.addListener(
            object : Player.Listener {
                override fun onAudioSessionIdChanged(audioSessionId: Int) {
                    this@PlaybackService.audioSessionId = audioSessionId
                    if (audioSessionId != C.AUDIO_SESSION_ID_UNSET) {
                        broadcastEffectSession(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION)
                    }
                }

                override fun onMediaItemTransition(
                    mediaItem: MediaItem?,
                    reason: Int,
                ) {
                    // ReplayGain (Befund 2.10): Loudness des neuen Titels
                    // nachziehen; Referenz ist -18 LUFS (ReplayGain 2.0 mit
                    // Surround-Toleranz, gaengige Player-Praxis).
                    val songId = mediaItem?.mediaId?.toLongOrNull()
                    if (songId == null) {
                        replayGainJob?.cancel()
                        replayGainJob = null
                        audioPipeline.setReplayGainDb(null)
                        return
                    }
                    // Befund 4.4: Vorgaenger abbrechen, sofort loeschen,
                    // Generation erhoehen — ein ueberholter Lauf schreibt
                    // danach nichts mehr (Pruefung unten).
                    replayGainJob?.cancel()
                    audioPipeline.setReplayGainDb(null)
                    val generation = ++replayGainGeneration
                    replayGainJob =
                        serviceScope.launch {
                            val lufs =
                                trackAnalysisRepository
                                    .observeAnalysis(songId)
                                    .first()
                                    ?.integratedLufs
                            if (generation != replayGainGeneration) return@launch
                            val gainDb = lufs?.let { ReplayGain.REFERENCE_LUFS - it.toDouble() }
                            audioPipeline.setReplayGainDb(gainDb)
                        }
                }
            },
        )
        broadcastEffectSession(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION)
        // Option "Bei BT-Verbindung automatisch fortsetzen" (Plan Phase 4).
        mainScope.launch {
            playbackSettingsStore.resumeOnBluetoothConnect.collect { enabled ->
                resumeOnBluetoothConnect = enabled
            }
        }
        registerBluetoothResumeCallback()
        // Befund 2.8: Bit-Perfect-Schalter beobachten. Beim Aktivieren werden
        // die bevorzugten Mixer-Attribute am USB-Gerät gesetzt; der Float-
        // Output-Wechsel erfordert einen neuen Player, daher startet der
        // Service neu (Settings-UI warnt bereits mit "wirkt beim naechsten
        // Start"). Beim Deaktivieren werden die Attribute wieder freigegeben.
        serviceScope.launch {
            audioPipeline.currentConfig
                .map { it.bitPerfectEnabled }
                .distinctUntilChanged()
                .drop(1) // Startwert nicht als "Wechsel" werten.
                .collect { enabled ->
                    if (enabled) {
                        // Befund 4.5: das Ergebnis ist kein Selbstgaenger —
                        // ohne USB-DAC, unter API 34 oder bei Ablehnung durch
                        // das Geraet bleibt der Mixer unangetastet. Der Nutzer
                        // sieht den echten Stand in den Audio-Einstellungen
                        // (BitPerfectSupport.mixerApplied), hier landet der
                        // Grund im Log statt im Nirwana.
                        val applied = bitPerfectGateway.applyPreferredMixerAttributes()
                        if (!applied) {
                            Log.w(TAG, "Bit-Perfect angefordert, Mixer-Attribute nicht gesetzt (kein USB-DAC, API < 34 oder Geraet lehnt ab)")
                        }
                    } else {
                        bitPerfectGateway.clearPreferredMixerAttributes()
                    }
                    // Service-Neustart, damit der Player mit dem richtigen
                    // floatOutput neu gebaut wird.
                    withContext(Dispatchers.Main) {
                        val restart = Intent(this@PlaybackService, PlaybackService::class.java)
                        stopService(restart)
                        startService(restart)
                    }
                }
        }
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
        dropLandingArmer.detach()
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
     * Empfaengt das Drop-Landungs-Kommando: loest den Song auf und wechselt
     * auf dem einen sessionfuehrenden Player vorgespult. Der Session-Result
     * wird erst nach der echten Ausfuehrung zurueckgegeben: `false`, wenn der
     * Song fehlt oder kein Player bereitsteht, damit der Rufer einen
     * Fehlschlag von Erfolg unterscheiden kann.
     */
    private suspend fun handlePlaySongAt(
        songId: Long,
        startPositionMs: Long,
    ): Boolean {
        val song = (libraryRepository.getSong(songId) as? AppResult.Success)?.value ?: return false
        val item = MediaItemFactory.fromSong(song)
        return withContext(dispatchers.main) {
            val current = player ?: return@withContext false
            current.setMediaItem(item, startPositionMs.coerceAtLeast(0))
            current.prepare()
            current.play()
            true
        }
    }

    /**
     * Armierte Drop-Landung (MP-3): Der Service terminiert den Wechsel
     * per `PlayerMessage` an der Wiedergabeposition (Audio-Uhr) und
     * blendet Stufe 1 (ADR-0022) aus/ein. Laeuft auf dem Main-Thread.
     */
    private suspend fun handleArmLanding(
        songId: Long,
        startPositionMs: Long,
        delayMs: Long,
        fadeMs: Long,
    ): Boolean {
        val song = (libraryRepository.getSong(songId) as? AppResult.Success)?.value ?: return false
        return withContext(dispatchers.main) {
            dropLandingArmer.arm(song, startPositionMs, delayMs, fadeMs)
        }
    }

    /** Bricht eine armierte Landung ab (Override, Neuplanung, Sitzungsende). */
    private suspend fun handleCancelLanding() {
        withContext(dispatchers.main) { dropLandingArmer.cancel() }
    }

    /**
     * Scrubbing-Modus (Media3 1.8+): waehrend eines Waveform-Drags optimiert
     * der Player auf viele schnelle Seeks statt jeden Sprung als vollen
     * Positionswechsel mit Audio-Ausgabe-Reset zu behandeln. Muss auf dem
     * Main-Thread laufen (Player-Vertrag).
     */
    private fun handleSetScrubbingMode(enabled: Boolean) {
        mainScope.launch {
            runCatching { player?.setScrubbingModeEnabled(enabled) }
        }
    }

    /** Kategorienamen des Browse-Baums (Plan Phase 6.5). */
    internal data class BrowseLabels(
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
    @OptIn(UnstableApi::class)
    /**
     * Testbar gehaltener Session-Callback (Befund 8.2): die
     * Custom-Command-Logik ist der Vertragskern zwischen App und Service
     * und wird deshalb in [PlaybackServiceCommandsTest] ohne echten
     * ExoPlayer ausgefuehrt. `internal` nur fuer `:data:playback`-Tests.
     */
    internal class LibrarySessionCallback(
        private val scope: CoroutineScope,
        private val stateStore: PlayerStateStore,
        private val libraryRepository: LibraryRepository,
        private val browseRepository: LibraryBrowseRepository,
        private val labels: BrowseLabels,
        private val ownPackageName: String,
        private val onPlaySongAt: suspend (Long, Long) -> Boolean,
        private val onSetScrubbingMode: (Boolean) -> Unit,
        private val onArmLanding: suspend (Long, Long, Long, Long) -> Boolean,
        private val onCancelLanding: suspend () -> Unit,
    ) : MediaLibrarySession.Callback {
        /**
         * Das interne Drop-Landungs-Kommando wird nur dem eigenen Package
         * freigegeben; fremde Controller (Android Auto, BT) bekommen
         * ausschliesslich die Standard-Browse- und Transportkommandos.
         * Staffelung siehe [SessionConnectionPolicy] (Ausbauplan A2):
         * System-UIDs voll (ohne Custom), Dritte ohne Queue-/Tempo-Eingriffe.
         */
        private fun isOwnPackage(controller: MediaSession.ControllerInfo): Boolean =
            SessionConnectionPolicy.isOwnPackage(controller.packageName, ownPackageName)

        /** Meldet das eigene Drop-Landungs-Kommando als verfuegbar an. */
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): MediaSession.ConnectionResult {
            val own = isOwnPackage(controller)
            val system = SessionConnectionPolicy.isSystemUid(controller.uid)
            return MediaSession.ConnectionResult
                .AcceptedResultBuilder(session)
                .setAvailableSessionCommands(SessionConnectionPolicy.sessionCommands(own))
                .setAvailablePlayerCommands(SessionConnectionPolicy.playerCommands(own, system))
                .build()
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            when (customCommand.customAction) {
                PlaybackCommands.ACTION_PLAY_SONG_AT -> {
                    if (!isOwnPackage(controller)) {
                        return Futures.immediateFuture(SessionResult(SessionError.ERROR_PERMISSION_DENIED))
                    }
                    val songId = args.getLong(PlaybackCommands.ARG_SONG_ID, -1L)
                    val startPositionMs = args.getLong(PlaybackCommands.ARG_START_POSITION_MS, 0L)
                    if (songId < 0) {
                        return Futures.immediateFuture(SessionResult(SessionError.ERROR_BAD_VALUE))
                    }
                    // Erst zurueckmelden, wenn die Operation wirklich
                    // ausgefuehrt wurde: Fehlschlag (Song fehlt, kein
                    // Player) wird als ERROR_UNKNOWN gemeldet, damit der
                    // Rufer ihn von Erfolg unterscheiden kann.
                    return scope.future {
                        val played = onPlaySongAt(songId, startPositionMs)
                        SessionResult(
                            if (played) SessionResult.RESULT_SUCCESS else SessionResult.RESULT_ERROR_UNKNOWN,
                        )
                    }
                }

                PlaybackCommands.ACTION_SET_SCRUBBING_MODE -> {
                    if (!isOwnPackage(controller)) {
                        return Futures.immediateFuture(SessionResult(SessionError.ERROR_PERMISSION_DENIED))
                    }
                    onSetScrubbingMode(args.getBoolean(PlaybackCommands.ARG_SCRUBBING_ENABLED, false))
                    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }

                PlaybackCommands.ACTION_ARM_LANDING -> {
                    if (!isOwnPackage(controller)) {
                        return Futures.immediateFuture(SessionResult(SessionError.ERROR_PERMISSION_DENIED))
                    }
                    val songId = args.getLong(PlaybackCommands.ARG_SONG_ID, -1L)
                    if (songId < 0) {
                        return Futures.immediateFuture(SessionResult(SessionError.ERROR_BAD_VALUE))
                    }
                    val startPositionMs = args.getLong(PlaybackCommands.ARG_START_POSITION_MS, 0L)
                    val delayMs = args.getLong(PlaybackCommands.ARG_DELAY_MS, 0L)
                    val fadeMs = args.getLong(PlaybackCommands.ARG_FADE_MS, 0L)
                    return scope.future {
                        val armed = onArmLanding(songId, startPositionMs, delayMs, fadeMs)
                        SessionResult(
                            if (armed) SessionResult.RESULT_SUCCESS else SessionResult.RESULT_ERROR_UNKNOWN,
                        )
                    }
                }

                PlaybackCommands.ACTION_CANCEL_LANDING -> {
                    if (!isOwnPackage(controller)) {
                        return Futures.immediateFuture(SessionResult(SessionError.ERROR_PERMISSION_DENIED))
                    }
                    return scope.future {
                        onCancelLanding()
                        SessionResult(SessionResult.RESULT_SUCCESS)
                    }
                }
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
