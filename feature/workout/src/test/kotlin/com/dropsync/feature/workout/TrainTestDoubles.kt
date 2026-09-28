package com.dropsync.feature.workout

import android.content.Context
import android.content.Intent
import androidx.activity.result.contract.ActivityResultContract
import com.dropsync.domain.timer.CueOutput
import com.dropsync.feature.workout.shadow.SampleWindow
import com.dropsync.feature.workout.shadow.ShadowDiffEvent
import com.dropsync.feature.workout.shadow.ShadowSessionRecorder

/**
 * Gemeinsame Testhelfer fuer die Train-ViewModel-Tests (2026-09-27,
 * Produktentscheidung "Sensor optional").
 *
 * **Warum sie hier und nicht in `TrainViewModelTest`:** dort waren sie
 * `private`, wodurch jede zweite Testdatei die ViewModel nicht
 * konstruieren konnte. `SensorOptionalBetriebTest` braucht dieselben drei
 * Helfer, und eine Kopie waere genau die Sorte Duplikation, die spaeter
 * auseinanderlaeuft.
 */
internal class FakeShadowSessionRecorder : ShadowSessionRecorder {
    val recorded = mutableListOf<ShadowDiffEvent>()

    /**
     * Umbauplan 2026-09-04 Phase 0: Reihenfolge der Aufrufe ist Teil des
     * Vertrags (recordSamples nach recordSet), deshalb wird sie hier
     * mitprotokolliert und nicht nur die Nutzlast.
     */
    val callOrder = mutableListOf<String>()
    val sampleWindows = mutableListOf<SampleWindow>()
    val started: MutableList<String> = mutableListOf()
    var ended: Int = 0

    override suspend fun startSession(sessionId: String) {
        started += sessionId
    }

    override suspend fun recordSet(event: ShadowDiffEvent) {
        recorded += event
        callOrder += "set"
    }

    override suspend fun recordSamples(window: SampleWindow) {
        sampleWindows += window
        callOrder += "samples"
    }

    override suspend fun endSession() {
        ended++
    }
}

/** Cues tun nichts — die Timer-Ausgabe ist in diesen Tests nicht das Thema. */
internal class NoOpCueOutput : CueOutput {
    override fun speak(
        cueSessionId: String,
        secondsRemaining: Int,
    ) = Unit

    override fun haptic(cueSessionId: String) = Unit

    override fun countdownBeep(cueSessionId: String) = Unit

    override fun tone(cueSessionId: String) = Unit

    override fun stopAll(cueSessionId: String) = Unit
}

/** Noop-Contract fuer den Health-Connect-Permission-Launcher (Tests). */
internal class TestHealthPermissionContract : ActivityResultContract<Set<String>, Set<String>>() {
    override fun createIntent(
        context: Context,
        input: Set<String>,
    ): Intent = Intent()

    override fun parseResult(
        resultCode: Int,
        intent: Intent?,
    ): Set<String> = emptySet()
}
