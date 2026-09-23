package com.dropsync.feature.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dropsync.core.common.AppResult
import com.dropsync.core.common.getOrNull
import com.dropsync.core.model.SongMarker
import com.dropsync.domain.library.MarkerRepository
import com.dropsync.domain.playback.PlaybackRepository
import com.dropsync.domain.timer.CancelReason
import com.dropsync.domain.timer.DropRestBlockReason
import com.dropsync.domain.timer.DropRestEligibility
import com.dropsync.domain.timer.DropRestGate
import com.dropsync.domain.timer.MarkerPoint
import com.dropsync.domain.timer.PlaybackSample
import com.dropsync.domain.timer.RestTimerServiceStarter
import com.dropsync.domain.timer.TimerEngine
import com.dropsync.domain.timer.TimerMode
import com.dropsync.domain.timer.TimerState
import com.dropsync.domain.timer.TimerStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * "Rest bis zum naechsten Drop" (Bauplan Schritt 11.2-11.4): Gate,
 * Start und Abbruch. Die effektive Dauer ist immer
 * `markerPosition - aktuelle Playerposition` und nie editierbar (11.3).
 *
 * Seit P1-8 ist das eine duenne UI-Fassade (A.3): Die **Ueberwachung**
 * (Restzeit-Projektion, Cues, Abbruch bei Songwechsel/Seek/Pause) und der
 * Foreground-Service liegen app-weit im
 * [com.dropsync.feature.player.DropSyncCoordinator] bzw. im
 * `TimerService`. Der Screen kann verlassen werden, ohne die Sitzung zu
 * toeten; die Notification bietet "Plan abbrechen".
 */
@HiltViewModel
class DropRestViewModel
    @Inject
    constructor(
        private val playbackRepository: PlaybackRepository,
        private val markerRepository: MarkerRepository,
        private val timerEngine: TimerEngine,
        private val restTimerServiceStarter: RestTimerServiceStarter,
    ) : ViewModel() {
        val timerState: StateFlow<TimerState> = timerEngine.state

        /**
         * Befund 6.2: Start-Fehlschlag als Einmal-Ereignis — vorher kehrte
         * `startDropRest` bei `AppResult.Failure` still zurueck und der
         * Nutzer stand vor einem toten Knopf. Die Shell zeigt eine Snackbar.
         */
        private val _startFailed = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        val startFailed: SharedFlow<Unit> = _startFailed.asSharedFlow()

        /**
         * C11 (P-9): true, wenn eine ANDERE Timer-Sitzung laeuft (z. B. die
         * normale Pause). Ein Start waere dann ein stiller Fehlgriff
         * (TimerConflict) — der Knopf wird stattdessen mit Grund gesperrt.
         */
        val startBlockedByOtherTimer: StateFlow<Boolean> =
            timerEngine.state
                .map { state ->
                    state.status in
                        setOf(TimerStatus.PREPARING, TimerStatus.RUNNING, TimerStatus.PAUSED) &&
                        state.session?.mode != TimerMode.DROPSYNC
                }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

        /**
         * Gate-Neubewertung im UI-Takt, nur solange ein Screen zuschaut.
         * D5/A8: Die Marker des laufenden Titels kommen aus einem Flow —
         * EINE Abfrage je Songwechsel (Room invalidiert bei
         * Marker-Aenderungen); der 500-ms-Takt liest nur noch die
         * Wiedergabezeit ([PlaybackRepository.snapshotNow]).
         */
        @OptIn(ExperimentalCoroutinesApi::class)
        val eligibility: StateFlow<DropRestEligibility> =
            combine(
                playbackRepository.state
                    .map { it.currentSongId }
                    .distinctUntilChanged()
                    .flatMapLatest { songId ->
                        if (songId == null) {
                            flowOf(emptyList())
                        } else {
                            markerRepository.observeEnabledMarkersForSong(songId)
                        }
                    },
                gateTicker(),
            ) { markers, _ -> evaluateGate(markers) }
                .stateIn(
                    viewModelScope,
                    SharingStarted.WhileSubscribed(5_000),
                    DropRestEligibility.Ineligible(DropRestBlockReason.NO_CURRENT_SONG),
                )

        /** Der UI-Takt: stoesst die Neubewertung an, ohne selbst zu laden. */
        private fun gateTicker(): Flow<Unit> =
            flow {
                while (true) {
                    emit(Unit)
                    delay(GATE_SAMPLE_MS)
                }
            }

        /** Startet Drop-Rest, wenn das Gate im Moment des Klicks offen ist. */
        fun startDropRest() {
            viewModelScope.launch {
                val state = timerEngine.state.value
                val active =
                    state.session?.mode == TimerMode.DROPSYNC &&
                        state.status == TimerStatus.RUNNING
                if (active) return@launch
                val snapshot = playbackRepository.snapshotNow().getOrNull() ?: return@launch
                val songId = snapshot.currentSongId ?: return@launch
                val sample = PlaybackSample(songId, snapshot.positionMs, snapshot.isPlaying)
                val markers = markerPointsOf(songId)
                val gate =
                    DropRestGate.evaluate(sample, markers) as? DropRestEligibility.Eligible
                        ?: return@launch
                val session =
                    when (
                        val result =
                            timerEngine.startDropSync(
                                requestedDurationMs = gate.effectiveDurationMs,
                                markerPositionMs = gate.markerPositionMs,
                            )
                    ) {
                        is AppResult.Success -> result.value
                        is AppResult.Failure -> {
                            _startFailed.tryEmit(Unit)
                            return@launch
                        }
                    }
                // Wiedergabe laeuft bereits (Gate-Bedingung): PREPARING -> RUNNING.
                timerEngine.markRunning(session.id)
                // P1-8: Der Foreground-Service traegt den Rest (Notification,
                // Prozessschutz); die Ueberwachung uebernimmt der app-weite
                // DropSyncCoordinator.
                restTimerServiceStarter.startForegroundTimerService()
            }
        }

        /** Nutzerabbruch (11.4): nur der Timer endet, nie die Session. */
        fun cancelDropRest() {
            timerEngine.cancel(CancelReason.USER)
        }

        fun acknowledgeEnd() {
            timerEngine.reset()
        }

        private suspend fun evaluateGate(markers: List<SongMarker>): DropRestEligibility {
            val snapshot =
                playbackRepository.snapshotNow().getOrNull()
                    ?: return DropRestEligibility.Ineligible(DropRestBlockReason.NO_CURRENT_SONG)
            val songId =
                snapshot.currentSongId
                    ?: return DropRestEligibility.Ineligible(DropRestBlockReason.NO_CURRENT_SONG)
            return DropRestGate.evaluate(
                PlaybackSample(songId, snapshot.positionMs, snapshot.isPlaying),
                markers.filter { it.linkedSongId == songId }.map { MarkerPoint(it.id, it.positionMs) },
            )
        }

        private suspend fun markerPointsOf(songId: Long): List<MarkerPoint> =
            markerRepository
                .getEnabledMarkersForSong(songId)
                .getOrNull()
                .orEmpty()
                .map { MarkerPoint(it.id, it.positionMs) }

        private companion object {
            const val GATE_SAMPLE_MS = 500L
        }
    }
