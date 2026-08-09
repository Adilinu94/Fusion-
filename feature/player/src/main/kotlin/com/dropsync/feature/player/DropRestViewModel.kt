package com.dropsync.feature.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dropsync.core.common.AppResult
import com.dropsync.core.common.getOrNull
import com.dropsync.domain.library.MarkerRepository
import com.dropsync.domain.playback.PlaybackRepository
import com.dropsync.domain.timer.CancelReason
import com.dropsync.domain.timer.DropRestBlockReason
import com.dropsync.domain.timer.DropRestEligibility
import com.dropsync.domain.timer.DropRestGate
import com.dropsync.domain.timer.DropRestMonitor
import com.dropsync.domain.timer.DropRestRequestBus
import com.dropsync.domain.timer.MarkerPoint
import com.dropsync.domain.timer.PlaybackSample
import com.dropsync.domain.timer.TimerEngine
import com.dropsync.domain.timer.TimerSession
import com.dropsync.domain.timer.TimerState
import com.dropsync.domain.timer.TimerStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * "Rest bis zum naechsten Drop" (Bauplan Schritt 11.2-11.4): Gate,
 * Start und Ueberwachung. Die effektive Dauer ist immer
 * `markerPosition - aktuelle Playerposition` und nie editierbar (11.3).
 */
@HiltViewModel
class DropRestViewModel
    @Inject
    constructor(
        private val playbackRepository: PlaybackRepository,
        private val markerRepository: MarkerRepository,
        private val timerEngine: TimerEngine,
        private val dropRestRequestBus: DropRestRequestBus,
    ) : ViewModel() {
        val timerState: StateFlow<TimerState> = timerEngine.state

        init {
            // Drop-Rest-Wunsch aus dem Trainingslog (Schritt 11): startet den
            // Modus, sofern das Gate im Moment des Klicks offen ist. startDropRest
            // ist idempotent, wenn bereits ein Timer laeuft.
            viewModelScope.launch {
                dropRestRequestBus.requests.collect { startDropRest() }
            }
        }

        /**
         * Gate-Neubewertung im UI-Takt, nur solange ein Screen zuschaut;
         * [PlaybackRepository.snapshotNow] liefert die Live-Position.
         */
        val eligibility: StateFlow<DropRestEligibility> =
            flow {
                while (true) {
                    emit(evaluateGate())
                    delay(GATE_SAMPLE_MS)
                }
            }.stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5_000),
                DropRestEligibility.Ineligible(DropRestBlockReason.NO_CURRENT_SONG),
            )

        private var monitorJob: Job? = null

        /** Startet Drop-Rest, wenn das Gate im Moment des Klicks offen ist. */
        fun startDropRest() {
            if (monitorJob?.isActive == true) return
            viewModelScope.launch {
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
                        is AppResult.Failure -> return@launch
                    }
                // Wiedergabe laeuft bereits (Gate-Bedingung): PREPARING -> RUNNING.
                timerEngine.markRunning(session.id)
                monitorJob = launch { monitor(session, songId, gate.markerPositionMs, sample) }
            }
        }

        /** Nutzerabbruch (11.4): nur der Timer endet, nie die Session. */
        fun cancelDropRest() {
            timerEngine.cancel(CancelReason.USER)
        }

        fun acknowledgeEnd() {
            timerEngine.reset()
        }

        /**
         * Ueberwachung nach 11.4: Songwechsel, Seek, Pause oder
         * Playerfehler beenden nur den Timer. Cue-Grenzwerte kommen aus
         * der Playertimeline; bei Rueckstau wird nur der juengste
         * Grenzwert geliefert (7.5).
         */
        private suspend fun monitor(
            session: TimerSession,
            startedSongId: Long,
            markerPositionMs: Long,
            firstSample: PlaybackSample,
        ) {
            var previous = firstSample
            val invalidated = mutableSetOf<Long>()
            while (true) {
                delay(MONITOR_SAMPLE_MS)
                val engineState = timerEngine.state.value
                if (engineState.session?.id != session.id ||
                    engineState.status != TimerStatus.RUNNING
                ) {
                    return
                }
                val current =
                    playbackRepository.snapshotNow().getOrNull()?.let {
                        PlaybackSample(it.currentSongId, it.positionMs, it.isPlaying)
                    } ?: PlaybackSample(
                        songId = null,
                        positionMs = previous.positionMs,
                        isPlaying = false,
                        hasError = true,
                    )
                val interruption =
                    DropRestMonitor.detect(startedSongId, previous, current, MONITOR_SAMPLE_MS)
                if (interruption != null) {
                    timerEngine.cancel(CancelReason.PLAYBACK_INTERRUPTED)
                    return
                }
                val remaining = markerPositionMs - current.positionMs
                timerEngine.projectRemaining(session.id, remaining)
                val due =
                    session.plannedCues
                        .map { it.thresholdMs }
                        .filter { remaining <= it && it !in invalidated }
                if (due.isNotEmpty()) {
                    timerEngine.onThresholdReached(session.id, due.min())
                    invalidated += due
                }
                if (remaining <= 0) return
                previous = current
            }
        }

        private suspend fun evaluateGate(): DropRestEligibility {
            val snapshot =
                playbackRepository.snapshotNow().getOrNull()
                    ?: return DropRestEligibility.Ineligible(DropRestBlockReason.NO_CURRENT_SONG)
            val songId =
                snapshot.currentSongId
                    ?: return DropRestEligibility.Ineligible(DropRestBlockReason.NO_CURRENT_SONG)
            return DropRestGate.evaluate(
                PlaybackSample(songId, snapshot.positionMs, snapshot.isPlaying),
                markerPointsOf(songId),
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
            const val MONITOR_SAMPLE_MS = 500L
        }
    }
