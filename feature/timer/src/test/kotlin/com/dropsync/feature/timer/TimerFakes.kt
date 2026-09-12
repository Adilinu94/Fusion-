package com.dropsync.feature.timer

import com.dropsync.core.common.Clock
import com.dropsync.domain.timer.CueOutput
import com.dropsync.domain.timer.RestTimerPreferencesRepository
import kotlinx.coroutines.flow.MutableStateFlow

/** Steuerbare monotone Uhr fuer den TimerEngine-Kern. */
internal class FakeTimerClock(
    var nowMs: Long = 0L,
) : Clock {
    override fun elapsedRealtimeMs(): Long = nowMs

    override fun epochMillis(): Long = nowMs

    fun advanceBy(ms: Long) {
        nowMs += ms
    }
}

/** Zeichnet Cue-Abbruche auf; beweist, dass cancel() den Engine-Pfad nimmt. */
internal class RecordingCueOutput : CueOutput {
    val stoppedIds = mutableListOf<String>()

    override fun speak(
        cueSessionId: String,
        secondsRemaining: Int,
    ) = Unit

    override fun haptic(cueSessionId: String) = Unit

    override fun countdownBeep(cueSessionId: String) = Unit

    override fun tone(cueSessionId: String) = Unit

    override fun stopAll(cueSessionId: String) {
        stoppedIds += cueSessionId
    }
}

/** Resttimer-Prefs mit direkt setzbaren Stroemen (Get-Ready B9). */
internal class FakeRestTimerPreferences : RestTimerPreferencesRepository {
    override val restPresetsSeconds = MutableStateFlow(RestTimerPreferencesRepository.DEFAULT_PRESETS_SECONDS)
    override val getReadyEnabled = MutableStateFlow(false)
    override val getReadySeconds = MutableStateFlow(RestTimerPreferencesRepository.DEFAULT_GET_READY_SECONDS)

    override suspend fun setRestPresetsSeconds(seconds: List<Int>) {
        restPresetsSeconds.value = seconds
    }

    override suspend fun setGetReady(
        enabled: Boolean,
        seconds: Int,
    ) {
        getReadyEnabled.value = enabled
        getReadySeconds.value = seconds
    }
}
