package com.dropsync.feature.timer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dropsync.domain.timer.CancelReason
import com.dropsync.domain.timer.RestTimerPreferencesRepository
import com.dropsync.domain.timer.TimerEngine
import com.dropsync.domain.timer.TimerMode
import com.dropsync.domain.timer.TimerState
import com.dropsync.domain.timer.TimerStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Timer-UI-Zustand (Schritt 12.3: Timer -> Modus -> Start ->
 * Pause/Abbruch). `evaluate()` ist idempotent; der UI-Tick ist nie die
 * Abschlussquelle (7.1).
 */
@HiltViewModel
class TimerViewModel
    @Inject
    constructor(
        private val timerEngine: TimerEngine,
        restTimerPreferences: RestTimerPreferencesRepository,
    ) : ViewModel() {
        val state: StateFlow<TimerState> = timerEngine.state

        /** Get-Ready-Vorlauf (B9): startRest zieht ihn als prepMs heran. */
        private val getReady: StateFlow<Pair<Boolean, Int>> =
            combine(
                restTimerPreferences.getReadyEnabled,
                restTimerPreferences.getReadySeconds,
            ) { enabled, seconds -> enabled to seconds }.stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5_000),
                false to RestTimerPreferencesRepository.DEFAULT_GET_READY_SECONDS,
            )

        init {
            viewModelScope.launch {
                while (isActive) {
                    timerEngine.evaluate()
                    delay(TICK_MS)
                }
            }
        }

        /** Startet einen Resttimer mit fester Dauer (TimerPreset-Domaene). */
        fun startRest(durationMs: Long) {
            if (state.value.status != TimerStatus.IDLE) return
            val (enabled, seconds) = getReady.value
            val prepMs = if (enabled) seconds * 1_000L else 0L
            timerEngine.start(TimerMode.REST, durationMs, prepMs)
        }

        fun pause() {
            timerEngine.pause()
        }

        fun resume() {
            timerEngine.resume()
        }

        fun cancel() {
            timerEngine.cancel(CancelReason.USER)
            timerEngine.reset()
        }

        /** Endzustand bestaetigen: zurueck zu IDLE (7.2). */
        fun acknowledgeFinished() {
            timerEngine.reset()
        }

        private companion object {
            const val TICK_MS = 250L
        }
    }
