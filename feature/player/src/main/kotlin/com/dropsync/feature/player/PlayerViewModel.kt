package com.dropsync.feature.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dropsync.core.common.getOrNull
import com.dropsync.core.model.SongMarker
import com.dropsync.domain.audio.TrackAnalysisRepository
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
                                        analysis.waveformBuckets.map { bucket ->
                                            bucket.min / 127f to bucket.max / 127f
                                        },
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
    }
