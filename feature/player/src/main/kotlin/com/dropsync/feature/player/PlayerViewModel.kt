package com.dropsync.feature.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dropsync.core.common.getOrNull
import com.dropsync.core.model.Song
import com.dropsync.core.model.SongMarker
import com.dropsync.domain.audio.AudioEngineRepository
import com.dropsync.domain.audio.DspConfig
import com.dropsync.domain.audio.TrackAnalysisRepository
import com.dropsync.domain.audio.WaveformDisplayGain
import com.dropsync.domain.library.LibraryBrowseRepository
import com.dropsync.domain.library.LibraryRepository
import com.dropsync.domain.library.MarkerRepository
import com.dropsync.domain.playback.PlaybackRepository
import com.dropsync.domain.playback.PlaybackState
import com.dropsync.domain.playback.QueueItem
import com.dropsync.domain.playback.RepeatMode
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.math.roundToInt

/** Zustand des Mini-Players (Schritt 12.2). */
data class MiniPlayerState(
    val isVisible: Boolean = false,
    val isPlaying: Boolean = false,
    val title: String = "",
    val artist: String? = null,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    /** Content-URI fuer die Cover-Kachel (gemeinsamer CoverArtLoader). */
    val contentUri: String? = null,
)

/**
 * Zustand des Now-Playing-Screens (Marker/Waveform-Plan Phase 1):
 * breitere Projektion derselben `playbackRepository.state`-Quelle, die
 * auch den Mini-Player speist — keine zweite Wahrheit.
 */
data class NowPlayingUiState(
    val isVisible: Boolean = false,
    val isPlaying: Boolean = false,
    val title: String = "",
    val artist: String? = null,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    /** MediaStore-ID des laufenden Songs (5.1); null bei leerer Queue. */
    val songId: Long? = null,
    /** Content-URI fuer den Cover-Art-Lader (MediaMetadataRetriever). */
    val contentUri: String? = null,
    val shuffleEnabled: Boolean = false,
    val repeatMode: RepeatMode = RepeatMode.OFF,
)

/** Zustand des Queue-Editors (Plan Phase 6, Punkt 3). */
data class QueueUiState(
    val items: List<QueueItem> = emptyList(),
    val currentIndex: Int = -1,
)

/**
 * Zustand der Waveform-Anzeige (Marker/Waveform-Plan Phase 3). Die
 * Grundfunktion (Abspielen, Springen per Zeit) haengt nie an der Analyse:
 * [Unavailable] faellt auf die klassische Zeitleiste zurueck.
 */
sealed interface WaveformUiState {
    /** Kein laufender Song. */
    data object Hidden : WaveformUiState

    /** Analyse angestossen, Ergebnis noch nicht im Cache. */
    data object Loading : WaveformUiState

    /** Analyse fehlgeschlagen (z. B. Format ohne Plattformdecoder). */
    data object Unavailable : WaveformUiState

    /** Min/Max-Paare normalisiert auf [-1..1] in Trackreihenfolge. */
    data class Ready(
        val buckets: List<Pair<Float, Float>>,
    ) : WaveformUiState
}

@HiltViewModel
class PlayerViewModel
    @Inject
    constructor(
        private val playbackRepository: PlaybackRepository,
        private val libraryRepository: LibraryRepository,
        private val browseRepository: LibraryBrowseRepository,
        private val trackAnalysisRepository: TrackAnalysisRepository,
        private val markerRepository: MarkerRepository,
        private val audioEngineRepository: AudioEngineRepository,
    ) : ViewModel() {
        /**
         * MediaStore-ID des laufenden Titels. Einzige Quelle fuer alle
         * songabhaengigen Ketten; `distinctUntilChanged` verhindert, dass
         * jedes Player-Ereignis (Position, Play/Pause) die nachgelagerten
         * Abfragen erneut anstoesst.
         */
        private val currentSongId: StateFlow<Long?> =
            playbackRepository.state
                .map { it.currentSongId }
                .distinctUntilChanged()
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

        /**
         * Geladene Metadaten MIT der ID, zu der sie gehoeren.
         *
         * Die ID ist Teil des Werts, damit die Projektionen unterscheiden
         * koennen zwischen "Titel hat keine Metadaten" und "Metadaten sind
         * noch nicht geladen". Ohne diese Unterscheidung veroeffentlichte
         * `nowPlaying` bei jedem Songwechsel einen Zwischenzustand mit
         * `isVisible = true` und leerem Titel — auf dem Geraet ein sichtbares
         * Aufblitzen einer leeren Titelzeile.
         */
        private data class LoadedSong(
            val songId: Long,
            val song: Song?,
        )

        /**
         * Metadaten des laufenden Titels, EINMAL pro Titelwechsel aus der
         * Bibliothek geladen (P1-Fix). Vorher rief sowohl `miniPlayer` als
         * auch `nowPlaying` in `mapLatest` ein `getSong()` — also ein
         * Room-Query pro Player-Ereignis, doppelt.
         */
        @OptIn(ExperimentalCoroutinesApi::class)
        private val currentSong: StateFlow<LoadedSong?> =
            currentSongId
                .flatMapLatest { songId ->
                    if (songId == null) {
                        flowOf<LoadedSong?>(null)
                    } else {
                        flow<LoadedSong?> {
                            emit(LoadedSong(songId, libraryRepository.getSong(songId).getOrNull()))
                        }
                    }
                }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

        /**
         * Paart den Wiedergabezustand mit den Metadaten und laesst nur
         * zusammengehoerende Paare durch.
         *
         * Der Filter ist der Grund, warum es diese Hilfsfunktion gibt: er
         * unterdrueckt genau die Emissionen, in denen die Metadaten noch zum
         * VORHERIGEN Titel gehoeren (oder noch fehlen). Die UI zeigt in dem
         * kurzen Moment weiter den alten Titel statt einer leeren Zeile.
         */
        private fun statesWithSong(): kotlinx.coroutines.flow.Flow<Pair<PlaybackState, Song?>> =
            combine(playbackRepository.state, currentSong) { state, loaded -> state to loaded }
                .filter { (state, loaded) ->
                    state.currentSongId == null || loaded?.songId == state.currentSongId
                }.map { (state, loaded) -> state to loaded?.song }

        init {
            // Waveform-Analyse fruehzeitig anstossen (Plan Phase 2/3): sobald ein
            // neuer Titel laeuft, nicht erst beim Oeffnen des Now-Playing-Screens.
            // So ist die Wellenform beim Tap auf einen Titel meist schon bereit;
            // die Analyse ist idempotent und cachebar (kein doppelter Aufwand).
            viewModelScope.launch {
                currentSongId.collect { songId -> requestAnalysis(songId) }
            }
        }

        /** Aktive DSP-Konfiguration fuer den EQ-Schnellzugriff im Player. */
        val dspConfig: StateFlow<DspConfig> =
            audioEngineRepository.dspConfig
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DspConfig())

        /** Aktiviert/deaktiviert den EQ ohne Umweg ueber die Audio-Einstellungen. */
        fun setEqEnabled(enabled: Boolean) {
            viewModelScope.launch {
                audioEngineRepository.updateDspConfig(
                    dspConfig.value.copy(eq = dspConfig.value.eq.copy(enabled = enabled)),
                )
            }
        }

        /** Setzt den Gain eines EQ-Bandes (Quick-EQ-Sheet, vertikale Slider). */
        fun setEqBandGain(
            bandIndex: Int,
            gainDb: Double,
        ) {
            val config = dspConfig.value
            val bands =
                config.eq.bands.mapIndexed { index, band ->
                    if (index == bandIndex) band.copy(gainDb = gainDb) else band
                }
            viewModelScope.launch {
                audioEngineRepository.updateDspConfig(config.copy(eq = config.eq.copy(bands = bands)))
            }
        }

        /** Crossfade an/aus im Player-Aktions-Carousel (Mix-Chip). */
        fun setCrossfadeEnabled(enabled: Boolean) {
            val config = dspConfig.value
            viewModelScope.launch {
                audioEngineRepository.updateDspConfig(
                    config.copy(crossfadeSeconds = if (enabled) DEFAULT_CROSSFADE_SECONDS else 0),
                )
            }
        }

        /** Aktueller Tempo-Faktor der Wiedergabe (0.5..2.0; 1.0 = Original). */
        val playbackSpeed: StateFlow<Float> =
            playbackRepository.state
                .map { it.playbackSpeed }
                .distinctUntilChanged()
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 1f)

        /** Setzt den Tempo-Faktor; ausserhalb 0.5..2.0 begrenzt die Implementation. */
        fun setPlaybackSpeed(speed: Float) {
            viewModelScope.launch { playbackRepository.setPlaybackSpeed(speed) }
        }

        /** BPM des laufenden Titels aus der Analyse (null bis Analyse fertig). */
        @OptIn(ExperimentalCoroutinesApi::class)
        val trackBpm: StateFlow<Float?> =
            currentSongId
                .flatMapLatest { songId ->
                    if (songId == null) {
                        flowOf(null)
                    } else {
                        trackAnalysisRepository.observeAnalysis(songId).map { it?.bpm }
                    }
                }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

        /** BPM-Lock-Zustand (Ziel-Kadenz haelt das Wiedergabetempo nach). */
        private val bpmLock = MutableStateFlow(false)
        val isBpmLockEnabled: StateFlow<Boolean> = bpmLock.asStateFlow()

        /** Ziel-Kadenz des BPM-Locks in BPM (60..200, Default 160). */
        private val targetBpm = MutableStateFlow(DEFAULT_TARGET_BPM)
        val lockTargetBpm: StateFlow<Int> = targetBpm.asStateFlow()

        // BPM-Lock: Ziel-Kadenz x aktuellem Track-BPM -> Tempo-Faktor.
        // Laeuft reaktiv: Titelwechsel oder neue BPM-Analyse ziehen die
        // Geschwindigkeit automatisch nach (nur solange der Lock an ist).
        // Eigener init-Block NACH den Deklarationen von bpmLock/targetBpm/
        // trackBpm: viewModelScope laeuft auf Dispatchers.Main.immediate,
        // d. h. die Coroutine startet synchron im Konstruktor — im ersten
        // init war trackBpm da noch null (Start-Crash, NPE in combine).
        init {
            viewModelScope.launch {
                combine(
                    bpmLock,
                    targetBpm,
                    trackBpm,
                ) { lock, target, bpm -> Triple(lock, target, bpm) }
                    .collect { (lock, target, bpm) ->
                        if (lock && bpm != null) {
                            playbackRepository.setPlaybackSpeed(speedForBpmLock(target, bpm))
                        }
                    }
            }
        }

        fun setBpmLock(enabled: Boolean) {
            bpmLock.value = enabled
        }

        fun setLockTargetBpm(bpm: Int) {
            targetBpm.value = bpm.coerceIn(MIN_TARGET_BPM, MAX_TARGET_BPM)
        }

        @OptIn(ExperimentalCoroutinesApi::class)
        val miniPlayer: StateFlow<MiniPlayerState> =
            statesWithSong()
                .map { (state, song) ->
                    if (state.currentSongId == null) {
                        MiniPlayerState()
                    } else {
                        MiniPlayerState(
                            isVisible = true,
                            isPlaying = state.isPlaying,
                            title = song?.title ?: song?.displayName ?: "",
                            artist = song?.artist,
                            positionMs = state.positionMs,
                            durationMs = state.durationMs,
                            contentUri = song?.contentUri,
                        )
                    }
                }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MiniPlayerState())

        fun togglePlayPause() {
            viewModelScope.launch {
                // Wahrheit ist die eine Player-Instanz, nicht `miniPlayer.value`:
                // Der Now-Playing-Screen abonniert `miniPlayer` nicht, daher
                // bliebe dessen Wert dort auf dem Startwert (isPlaying=false)
                // stehen und Pause wuerde nie greifen. `snapshotNow()` liefert
                // den echten Live-Zustand derselben Instanz.
                val playing =
                    playbackRepository.snapshotNow().getOrNull()?.isPlaying
                        ?: miniPlayer.value.isPlaying
                if (playing) {
                    playbackRepository.pause()
                } else {
                    playbackRepository.play()
                }
            }
        }

        fun skipToNext() {
            viewModelScope.launch { playbackRepository.skipToNext() }
        }

        fun skipToPrevious() {
            viewModelScope.launch { playbackRepository.skipToPrevious() }
        }

        fun seekTo(positionMs: Long) {
            viewModelScope.launch { playbackRepository.seekTo(positionMs) }
        }

        /**
         * Scrubbing-Modus der Wiedergabe (Media3 1.8+). Die Waveform meldet
         * Drag-Beginn und -Ende; der Player optimiert dazwischen auf viele
         * schnelle Seeks. Best-effort — Seeks funktionieren auch ohne.
         */
        fun setScrubbing(active: Boolean) {
            viewModelScope.launch { playbackRepository.setScrubbingMode(active) }
        }

        /** Now-Playing-Projektion (Marker/Waveform-Plan Phase 1). */
        @OptIn(ExperimentalCoroutinesApi::class)
        val nowPlaying: StateFlow<NowPlayingUiState> =
            statesWithSong()
                .map { (state, song) ->
                    val songId = state.currentSongId
                    if (songId == null) {
                        NowPlayingUiState()
                    } else {
                        NowPlayingUiState(
                            isVisible = true,
                            isPlaying = state.isPlaying,
                            title = song?.title ?: song?.displayName ?: "",
                            artist = song?.artist,
                            positionMs = state.positionMs,
                            durationMs = state.durationMs,
                            songId = songId,
                            contentUri = song?.contentUri,
                            shuffleEnabled = state.shuffleEnabled,
                            repeatMode = state.repeatMode,
                        )
                    }
                }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), NowPlayingUiState())

        /** Favoritenstatus des laufenden Titels aus derselben Quelle wie die Library. */
        @OptIn(ExperimentalCoroutinesApi::class)
        val isFavorite: StateFlow<Boolean> =
            currentSongId
                .flatMapLatest { songId ->
                    if (songId == null) flowOf(false) else browseRepository.isFavorite(songId)
                }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

        fun toggleFavorite() {
            val songId = nowPlaying.value.songId ?: return
            viewModelScope.launch { browseRepository.setFavorite(songId, !isFavorite.value) }
        }

        fun toggleShuffle() {
            viewModelScope.launch {
                playbackRepository.setShuffle(
                    !playbackRepository
                        .snapshotNow()
                        .getOrNull()
                        ?.shuffleEnabled
                        .orDefault(false),
                )
            }
        }

        fun cycleRepeat() {
            viewModelScope.launch {
                val current = playbackRepository.snapshotNow().getOrNull()?.repeatMode ?: RepeatMode.OFF
                val next =
                    when (current) {
                        RepeatMode.OFF -> RepeatMode.ALL
                        RepeatMode.ALL -> RepeatMode.ONE
                        RepeatMode.ONE -> RepeatMode.OFF
                    }
                playbackRepository.setRepeatMode(next)
            }
        }

        private fun Boolean?.orDefault(default: Boolean): Boolean = this ?: default

        private val tickedPositionMs = MutableStateFlow<Long?>(null)

        /**
         * Live-Position aus dem Ticker des Now-Playing-Screens; null,
         * solange kein Tick vorliegt (dann gilt [NowPlayingUiState.positionMs]).
         */
        val livePositionMs: StateFlow<Long?> = tickedPositionMs.asStateFlow()

        /**
         * Ein Ticker-Schritt: fragt `snapshotNow()` ab, weil `state` die
         * Position nur bei Player-Ereignissen aktualisiert. Wird nur vom
         * sichtbaren Now-Playing-Screen aufgerufen (kein Hintergrund-Polling).
         */
        fun refreshPosition() {
            viewModelScope.launch {
                playbackRepository.snapshotNow().getOrNull()?.let {
                    tickedPositionMs.value = it.positionMs
                }
            }
        }

        /**
         * Waveform des laufenden Songs aus dem Analyse-Cache (Phase 3).
         * Bytes werden auf [-1..1] normalisiert; leere Buckets sind der
         * persistierte Fehlerfall.
         */
        @OptIn(ExperimentalCoroutinesApi::class)
        val waveform: StateFlow<WaveformUiState> =
            currentSongId
                .flatMapLatest { songId ->
                    if (songId == null) {
                        flowOf<WaveformUiState>(WaveformUiState.Hidden)
                    } else {
                        trackAnalysisRepository.observeAnalysis(songId).map { analysis ->
                            when {
                                analysis == null -> {
                                    WaveformUiState.Loading
                                }

                                analysis.waveformBuckets.isEmpty() -> {
                                    WaveformUiState.Unavailable
                                }

                                else -> {
                                    WaveformUiState.Ready(
                                        // Phase 8: visuelle Lautheits-Normalisierung.
                                        // Leise Tracks werden mit Boden hochskaliert,
                                        // damit sie nicht als flache Linie erscheinen.
                                        WaveformDisplayGain.displayBuckets(
                                            analysis.waveformBuckets,
                                            analysis.peakLinear,
                                        ),
                                    )
                                }
                            }
                        }
                    }
                }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), WaveformUiState.Hidden)

        /**
         * Stoesst die Analyse fuer den sichtbaren Song an (Cache-Miss beim
         * Titelwechsel oder beim Oeffnen des Now-Playing-Screens).
         */
        fun requestAnalysis(songId: Long?) {
            if (songId == null) return
            viewModelScope.launch {
                libraryRepository.getSong(songId).getOrNull()?.let {
                    trackAnalysisRepository.requestAnalysis(it)
                }
            }
        }

        /**
         * Aktive Marker des laufenden Songs fuer die Waveform-Ticks
         * (Phase 4); [markersVersion] erzwingt einen Reload nach
         * createMarker/deleteMarker.
         */
        private val markersVersion = MutableStateFlow(0)

        @OptIn(ExperimentalCoroutinesApi::class)
        val nowPlayingMarkers: StateFlow<List<SongMarker>> =
            combine(currentSongId, markersVersion) { songId, _ -> songId }
                .mapLatest { songId ->
                    if (songId == null) {
                        emptyList()
                    } else {
                        markerRepository.getEnabledMarkersForSong(songId).getOrNull().orEmpty()
                    }
                }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

        /** Manuellen Marker anlegen (Phase 4); leeres Label ergibt "Drop". */
        fun createMarker(
            label: String,
            positionMs: Long,
        ) {
            val songId = nowPlaying.value.songId ?: return
            viewModelScope.launch {
                markerRepository.createManualMarker(songId, label, positionMs)
                markersVersion.value++
            }
        }

        /** Marker nach Bestaetigung loeschen (Phase 4, Long-Press). */
        fun deleteMarker(markerId: Long) {
            viewModelScope.launch {
                markerRepository.deleteMarker(markerId)
                markersVersion.value++
            }
        }

        /** Marker per Drag verschieben (Phase 5); wirkt sofort auf die Landung. */
        fun moveMarker(
            markerId: Long,
            newPositionMs: Long,
        ) {
            viewModelScope.launch {
                markerRepository.moveMarker(markerId, newPositionMs)
                markersVersion.value++
            }
        }

        /** Beobachtbare Warteschlange fuer den Queue-Editor (Plan Phase 6). */
        val queue: StateFlow<QueueUiState> =
            playbackRepository.state
                .map { QueueUiState(items = it.queue, currentIndex = it.currentIndex) }
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), QueueUiState())

        fun playQueueItem(index: Int) {
            viewModelScope.launch { playbackRepository.skipToQueueIndex(index) }
        }

        /**
         * Cover-URI eines Queue-Titels fuer den Swipe-Wechsel im
         * Now-Playing-Screen. Wird pro sichtbarer Pager-Seite einzeln
         * aufgeloest (nicht die ganze Queue auf einmal), damit lange
         * Warteschlangen die UI nicht belasten.
         */
        suspend fun coverUriFor(songId: Long): String? = libraryRepository.getSong(songId).getOrNull()?.contentUri

        fun moveQueueItem(
            fromIndex: Int,
            toIndex: Int,
        ) {
            viewModelScope.launch { playbackRepository.moveInQueue(fromIndex, toIndex) }
        }

        fun removeQueueItem(index: Int) {
            viewModelScope.launch { playbackRepository.removeFromQueue(index) }
        }

        // Queue-Prewarming (Umbauplan Phase 4): sobald die Waveform des
        // LAUFENDEN Titels im Cache liegt, die naechsten Titel vorbereiten.
        //
        // Die Bedingung ist der Kern der Sache: frueher angestossen
        // konkurrieren die Prewarm-Decodes mit dem einen Lauf, auf den der
        // Nutzer gerade wartet, und machen die sichtbare Waveform langsamer
        // statt schneller.
        //
        // Der init-Block steht bewusst NACH den Deklarationen von `waveform`
        // und `queue`: viewModelScope laeuft auf Dispatchers.Main.immediate,
        // die Coroutine startet also synchron im Konstruktor. Weiter oben
        // waeren beide Felder noch null (dieselbe Falle wie beim BPM-Lock).
        init {
            viewModelScope.launch {
                combine(
                    waveform.map { it is WaveformUiState.Ready },
                    queue,
                ) { ready, queueState -> ready to queueState }
                    .filter { (ready, queueState) -> ready && queueState.currentIndex >= 0 }
                    .map { (_, queueState) ->
                        // Nur regulaere Songs: virtuelle CUE-Tracks haben keine
                        // MediaStore-ID und damit keinen Analyse-Cache.
                        queueState.items
                            .drop(queueState.currentIndex + 1)
                            .mapNotNull { it.songId }
                            .take(TrackAnalysisRepository.DEFAULT_PREWARM_LIMIT)
                    }.distinctUntilChanged()
                    .collect(::prewarmUpcoming)
            }
        }

        private suspend fun prewarmUpcoming(songIds: List<Long>) {
            if (songIds.isEmpty()) return
            val songs = songIds.mapNotNull { libraryRepository.getSong(it).getOrNull() }
            if (songs.isNotEmpty()) trackAnalysisRepository.requestAnalysisPrewarm(songs)
        }

        companion object {
            /** Sicherer Tempo-Bereich, identisch zur Playback-Implementation. */
            const val MIN_PLAYBACK_SPEED = 0.5f
            const val MAX_PLAYBACK_SPEED = 2.0f

            /** Crossfade-Dauer des Mix-Chips im Aktions-Carousel (Sekunden). */
            const val DEFAULT_CROSSFADE_SECONDS = 4

            const val MIN_TARGET_BPM = 60
            const val MAX_TARGET_BPM = 200
            const val DEFAULT_TARGET_BPM = 160

            /**
             * Tempo-Faktor fuer den BPM-Lock: Ziel-Kadenz / Track-BPM, in den
             * sicheren Bereich 0.5..2.0 gefaltet (Oktavfehler wie bei der
             * Tempo-Schaetzung: Halb-/Doppeltempo ist ein Treffer).
             */
            fun speedForBpmLock(
                targetBpm: Int,
                trackBpm: Float,
            ): Float {
                var target = targetBpm.toFloat()
                if (trackBpm <= 0f) return 1f
                var speed = target / trackBpm
                while (speed < MIN_PLAYBACK_SPEED) {
                    target *= 2f
                    speed = target / trackBpm
                }
                while (speed > MAX_PLAYBACK_SPEED) {
                    target /= 2f
                    speed = target / trackBpm
                }
                return (speed * 100).roundToInt() / 100f
            }
        }
    }
