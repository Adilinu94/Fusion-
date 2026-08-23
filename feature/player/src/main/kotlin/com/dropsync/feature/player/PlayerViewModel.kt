package com.dropsync.feature.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dropsync.core.common.getOrNull
import com.dropsync.core.model.SongMarker
import com.dropsync.domain.audio.AudioEngineRepository
import com.dropsync.domain.audio.DspConfig
import com.dropsync.domain.audio.TrackAnalysisRepository
import com.dropsync.domain.audio.WaveformDisplayGain
import com.dropsync.domain.library.LibraryRepository
import com.dropsync.domain.library.MarkerRepository
import com.dropsync.domain.playback.PlaybackRepository
import com.dropsync.domain.playback.QueueItem
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
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
        private val trackAnalysisRepository: TrackAnalysisRepository,
        private val markerRepository: MarkerRepository,
        private val audioEngineRepository: AudioEngineRepository,
    ) : ViewModel() {
        init {
            // Waveform-Analyse fruehzeitig anstossen (Plan Phase 2/3): sobald ein
            // neuer Titel laeuft, nicht erst beim Oeffnen des Now-Playing-Screens.
            // So ist die Wellenform beim Tap auf einen Titel meist schon bereit;
            // die Analyse ist idempotent und cachebar (kein doppelter Aufwand).
            viewModelScope.launch {
                playbackRepository.state
                    .map { it.currentSongId }
                    .distinctUntilChanged()
                    .collect { songId -> requestAnalysis(songId) }
            }
            // BPM-Lock: Ziel-Kadenz x aktuellem Track-BPM -> Tempo-Faktor.
            // Laeuft reaktiv: Titelwechsel oder neue BPM-Analyse ziehen die
            // Geschwindigkeit automatisch nach (nur solange der Lock an ist).
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
            playbackRepository.state
                .map { it.currentSongId }
                .distinctUntilChanged()
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

        fun setBpmLock(enabled: Boolean) {
            bpmLock.value = enabled
        }

        fun setLockTargetBpm(bpm: Int) {
            targetBpm.value = bpm.coerceIn(MIN_TARGET_BPM, MAX_TARGET_BPM)
        }

        @OptIn(ExperimentalCoroutinesApi::class)
        val miniPlayer: StateFlow<MiniPlayerState> =
            playbackRepository.state
                .mapLatest { state ->
                    val songId = state.currentSongId
                    if (songId == null) {
                        MiniPlayerState()
                    } else {
                        val song = libraryRepository.getSong(songId).getOrNull()
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

        /** Now-Playing-Projektion (Marker/Waveform-Plan Phase 1). */
        @OptIn(ExperimentalCoroutinesApi::class)
        val nowPlaying: StateFlow<NowPlayingUiState> =
            playbackRepository.state
                .mapLatest { state ->
                    val songId = state.currentSongId
                    if (songId == null) {
                        NowPlayingUiState()
                    } else {
                        val song = libraryRepository.getSong(songId).getOrNull()
                        NowPlayingUiState(
                            isVisible = true,
                            isPlaying = state.isPlaying,
                            title = song?.title ?: song?.displayName ?: "",
                            artist = song?.artist,
                            positionMs = state.positionMs,
                            durationMs = state.durationMs,
                            songId = songId,
                            contentUri = song?.contentUri,
                        )
                    }
                }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), NowPlayingUiState())

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
            playbackRepository.state
                .map { it.currentSongId }
                .distinctUntilChanged()
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
         * Stoesst die aufschiebbare Analyse fuer den Song an (Cache-Miss
         * beim Oeffnen des Now-Playing-Screens, Plan Phase 2/3).
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
            combine(
                playbackRepository.state.map { it.currentSongId }.distinctUntilChanged(),
                markersVersion,
            ) { songId, _ -> songId }
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
