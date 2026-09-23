package com.dropsync.feature.player

import com.dropsync.core.common.Clock
import com.dropsync.core.common.DispatcherProvider
import com.dropsync.core.common.getOrNull
import com.dropsync.domain.library.LibraryRepository
import com.dropsync.domain.playback.PlaybackRepository
import com.dropsync.domain.timer.DropRestMonitor
import com.dropsync.domain.timer.DropSyncFailureReason
import com.dropsync.domain.timer.DropSyncMode
import com.dropsync.domain.timer.DropSyncState
import com.dropsync.domain.timer.PlaybackSample
import com.dropsync.domain.timer.TimerEngine
import com.dropsync.domain.timer.TimerSession
import com.dropsync.domain.timer.TimerState
import com.dropsync.domain.timer.TimerStatus
import com.dropsync.domain.timer.TimingConfidence
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Monitor des manuellen DropRest (Bauplan 11.4, P1-8): projiziert die
 * Restzeit aus der Playerposition, liefert Cues und beendet nur den Timer
 * bei Songwechsel, Seek, Pause oder Playerfehler.
 *
 * Der Monitor lebt app-weit (nicht im Screen): Der Nutzer kann den Player
 * verlassen, waehrend die Sitzung im Foreground-Service weiterlaeuft.
 * Zustaende werden ueber den `publish`-Rueckruf an den
 * [DropSyncCoordinator] gemeldet (Design 4.5, Modus [DropSyncMode.UNTIL_MARKER]).
 */
@Singleton
class DropRestSessionMonitor
    @Inject
    constructor(
        private val timerEngine: TimerEngine,
        private val playbackRepository: PlaybackRepository,
        private val libraryRepository: LibraryRepository,
        private val clock: Clock,
        dispatchers: DispatcherProvider,
    ) {
        private val scope = CoroutineScope(SupervisorJob() + dispatchers.default)

        private var monitoredSessionId: String? = null
        private var monitorJob: Job? = null

        /** Reagiert auf den Timerzustand einer DROPSYNC-Sitzung. */
        fun onTimerState(
            state: TimerState,
            session: TimerSession,
            publish: (DropSyncState) -> Unit,
        ) {
            when (state.status) {
                TimerStatus.RUNNING -> {
                    if (monitoredSessionId != session.id) start(session, publish)
                }

                TimerStatus.COMPLETED -> {
                    stop()
                    publish(DropSyncState.Landed(clock.elapsedRealtimeMs(), deltaMs = 0L))
                }

                TimerStatus.CANCELLED -> {
                    stop()
                    publish(DropSyncState.Cancelled)
                }

                TimerStatus.FAILED -> {
                    stop()
                    publish(DropSyncState.Failed(DropSyncFailureReason.PLAYBACK_ERROR))
                }

                TimerStatus.PAUSED -> {
                    // P-1: Der Loop deutet eine Pause als Wiedergabe-Stopp und
                    // beendet sich (er prueft nur auf RUNNING); der Merker
                    // monitoredSessionId blieb aber stehen, sodass der Resume
                    // keinen neuen Monitor startete. Deshalb hier sauber
                    // beenden — der naechste RUNNING-Zustand startet neu.
                    stop()
                }

                TimerStatus.IDLE -> {
                    // Sitzung zurueckgesetzt: kein Monitor mehr noetig.
                    stop()
                }

                else -> {
                    Unit
                }
            }
        }

        /** Beendet die Ueberwachung (Sitzungsende, Abbruch, Screen-Wechsel). */
        fun stop() {
            monitorJob?.cancel()
            monitorJob = null
            monitoredSessionId = null
        }

        private fun start(
            session: TimerSession,
            publish: (DropSyncState) -> Unit,
        ) {
            stop()
            val markerPositionMs = session.markerPositionMs ?: return
            monitoredSessionId = session.id
            monitorJob =
                scope.launch {
                    val first =
                        playbackRepository.snapshotNow().getOrNull()
                            ?: run {
                                timerEngine.cancel(com.dropsync.domain.timer.CancelReason.PLAYBACK_INTERRUPTED)
                                return@launch
                            }
                    val startedSongId = first.currentSongId
                    if (startedSongId == null) {
                        timerEngine.cancel(com.dropsync.domain.timer.CancelReason.PLAYBACK_INTERRUPTED)
                        return@launch
                    }
                    val songTitle =
                        (libraryRepository.getSong(startedSongId) as? com.dropsync.core.common.AppResult.Success)
                            ?.value
                            ?.title
                            .orEmpty()
                    publish(
                        DropSyncState.Planned(
                            songTitle = songTitle,
                            markerLabel = "",
                            targetElapsedRealtimeMs =
                                clock.elapsedRealtimeMs() + markerPositionMs - first.positionMs,
                            remainingMs = (markerPositionMs - first.positionMs).coerceAtLeast(0),
                            confidence = TimingConfidence.EXACT,
                            mode = DropSyncMode.UNTIL_MARKER,
                        ),
                    )
                    var previous =
                        PlaybackSample(startedSongId, first.positionMs, first.isPlaying)
                    val invalidated = mutableSetOf<Long>()
                    while (true) {
                        delay(MONITOR_SAMPLE_MS)
                        val engineState = timerEngine.state.value
                        if (engineState.session?.id != session.id ||
                            engineState.status != TimerStatus.RUNNING
                        ) {
                            return@launch
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
                            DropRestMonitor.detect(
                                startedSongId,
                                previous,
                                current,
                                MONITOR_SAMPLE_MS,
                            )
                        if (interruption != null) {
                            timerEngine.cancel(com.dropsync.domain.timer.CancelReason.PLAYBACK_INTERRUPTED)
                            return@launch
                        }
                        val remaining = markerPositionMs - current.positionMs
                        timerEngine.projectRemaining(session.id, remaining)
                        publish(
                            DropSyncState.Planned(
                                songTitle = songTitle,
                                markerLabel = "",
                                targetElapsedRealtimeMs =
                                    clock.elapsedRealtimeMs() + remaining.coerceAtLeast(0),
                                remainingMs = remaining.coerceAtLeast(0),
                                confidence = TimingConfidence.EXACT,
                                mode = DropSyncMode.UNTIL_MARKER,
                            ),
                        )
                        val due =
                            session.plannedCues
                                .map { it.thresholdMs }
                                .filter { remaining <= it && it !in invalidated }
                        if (due.isNotEmpty()) {
                            timerEngine.onThresholdReached(session.id, due.min())
                            invalidated += due
                        }
                        if (remaining <= 0) return@launch
                        previous = current
                    }
                }
        }

        private companion object {
            /** Abtastabstand des DropRest-Monitors (11.4). */
            const val MONITOR_SAMPLE_MS = 500L
        }
    }
