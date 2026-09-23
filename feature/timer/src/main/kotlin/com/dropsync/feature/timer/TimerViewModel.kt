package com.dropsync.feature.timer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dropsync.core.common.AppResult
import com.dropsync.domain.playback.RestMusicSettingsRepository
import com.dropsync.domain.timer.CancelReason
import com.dropsync.domain.timer.DropLandingPlanner
import com.dropsync.domain.timer.DropRestRequestBus
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
        // C15 (PR-3): DropSync-Schalter auch im Standalone-Timer.
        private val restMusicSettings: RestMusicSettingsRepository,
        private val dropRestRequestBus: DropRestRequestBus,
    ) : ViewModel() {
        val state: StateFlow<TimerState> = timerEngine.state

        /**
         * C15: der globale Drop-Auto-Schalter (eine Wahrheit mit dem
         * Train-Tab und den Einstellungen). Eagerly, weil [startRest]
         * `.value` ohne Collector liest.
         */
        val dropAutoEnabled: StateFlow<Boolean> =
            restMusicSettings.dropAutoEnabled.stateIn(
                viewModelScope,
                SharingStarted.Eagerly,
                RestMusicSettingsRepository.DEFAULT_DROP_AUTO_ENABLED,
            )

        /**
         * Bearbeitbare Schnellwahl-Presets (B8, Befund 3.4): die
         * Einstellungen aus dem RestTimerPreferences-Store wirken jetzt
         * auch im Standalone-Timer statt der hartkodierten Liste.
         */
        val restPresetsSeconds: StateFlow<List<Int>> =
            restTimerPreferences.restPresetsSeconds.stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5_000),
                RestTimerPreferencesRepository.DEFAULT_PRESETS_SECONDS,
            )

        /**
         * Get-Ready-Vorlauf (B9): startRest zieht ihn als prepMs heran.
         *
         * Eagerly statt WhileSubscribed: Diesen Strom abonniert niemand,
         * startRest liest nur `.value`. Mit WhileSubscribed staende dort
         * ewig der Startwert (Vorlauf aus) — der B9-Vorlauf waere tot.
         */
        private val getReady: StateFlow<Pair<Boolean, Int>> =
            combine(
                restTimerPreferences.getReadyEnabled,
                restTimerPreferences.getReadySeconds,
            ) { enabled, seconds -> enabled to seconds }.stateIn(
                viewModelScope,
                SharingStarted.Eagerly,
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
            val result = timerEngine.start(TimerMode.REST, durationMs, prepMs)
            // C15 (PR-3/PR-4): Drop-Auto wirkt auch hier — aber erst ab
            // einer Minute Pause, sonst waere die Landung nur ein Wechsel.
            if (result is AppResult.Success &&
                dropAutoEnabled.value &&
                durationMs >= DropLandingPlanner.MIN_DROP_AUTO_REST_MS
            ) {
                dropRestRequestBus.request()
            }
        }

        /**
         * C15 (PR-3): Schalter im Countdown — bei laufender Pause sofort
         * planen; unter einer Minute wirkungslos (PR-4, UI sperrt vorher).
         */
        fun setDropAutoEnabled(enabled: Boolean) {
            viewModelScope.launch {
                restMusicSettings.setDropAutoEnabled(enabled)
                if (enabled &&
                    state.value.status == TimerStatus.RUNNING &&
                    state.value.remainingMs >= DropLandingPlanner.MIN_DROP_AUTO_REST_MS
                ) {
                    dropRestRequestBus.request()
                }
            }
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
