package com.dropsync.feature.timer

import androidx.lifecycle.viewModelScope
import com.dropsync.domain.timer.TimerEngine
import com.dropsync.domain.timer.TimerMode
import com.dropsync.domain.timer.TimerStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * B-UI-1, letzte offene Zeile (`feature/timer` hatte null Tests): die
 * Timer-UI-Logik gegen die echte [TimerEngine] (reine JVM-Domaene).
 *
 * Nebenbefund mit Fix: `getReady` lief mit `WhileSubscribed` ohne jeden
 * Abonnenten — `startRest` las damit immer den Startwert (Vorlauf aus),
 * der Get-Ready-Vorlauf (B9) war in Produktion tot. Derselbe
 * WhileSubscribed-ohne-Sammler-Typ wie in P2-15 (`dspConfig.value`).
 * `startRest mit Get-Ready` ist der Gegenbeweis (rot vor dem Fix).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TimerViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    private lateinit var clock: FakeTimerClock
    private lateinit var cueOutput: RecordingCueOutput
    private lateinit var prefs: FakeRestTimerPreferences
    private lateinit var engine: TimerEngine

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        clock = FakeTimerClock()
        cueOutput = RecordingCueOutput()
        prefs = FakeRestTimerPreferences()
        engine = TimerEngine(clock, cueOutput)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel(): TimerViewModel = TimerViewModel(engine, prefs)

    /**
     * Erzeugt ein ViewModel und cancelt dessen Scope am Testende. Ohne das
     * Cancel haengt runTest im Cleanup: der 250-ms-Ticker in
     * TimerViewModel.init laeuft im viewModelScope (nicht im
     * TestScope.backgroundScope) und produziert endlos neue Tasks
     * (Muster aus TrainViewModelTest).
     */
    private suspend fun kotlinx.coroutines.test.TestScope.withViewModel(block: suspend (TimerViewModel) -> Unit) {
        val vm = viewModel()
        try {
            block(vm)
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    @Test
    fun `startRest startet REST-Sitzung mit Dauer`() =
        runTest(dispatcher) {
            withViewModel { vm ->
                vm.startRest(90_000)
                runCurrent()
                val state = vm.state.value
                assertEquals(TimerStatus.RUNNING, state.status)
                assertEquals(TimerMode.REST, state.session?.mode)
                assertEquals(90_000, state.remainingMs)
            }
        }

    @Test
    fun `startRest mit Get-Ready startet PREPARING mit Vorlauf`() =
        runTest(dispatcher) {
            prefs.getReadyEnabled.value = true
            prefs.getReadySeconds.value = 5
            withViewModel { vm ->
                runCurrent()
                vm.startRest(60_000)
                runCurrent()
                val state = vm.state.value
                assertEquals(TimerStatus.PREPARING, state.status)
                assertEquals(5_000, state.remainingMs)
            }
        }

    @Test
    fun `zweiter Start waehrend Lauf wird ignoriert`() =
        runTest(dispatcher) {
            withViewModel { vm ->
                vm.startRest(90_000)
                runCurrent()
                val firstId =
                    vm.state.value.session
                        ?.id
                vm.startRest(30_000)
                runCurrent()
                assertEquals(
                    firstId,
                    vm.state.value.session
                        ?.id,
                )
                assertEquals(90_000, vm.state.value.remainingMs)
            }
        }

    @Test
    fun `pause und resume steuern die Engine`() =
        runTest(dispatcher) {
            withViewModel { vm ->
                vm.startRest(60_000)
                runCurrent()
                vm.pause()
                runCurrent()
                assertEquals(TimerStatus.PAUSED, vm.state.value.status)
                vm.resume()
                runCurrent()
                assertEquals(TimerStatus.RUNNING, vm.state.value.status)
            }
        }

    @Test
    fun `cancel stoppt Cues und kehrt zu IDLE zurueck`() =
        runTest(dispatcher) {
            withViewModel { vm ->
                vm.startRest(60_000)
                runCurrent()
                val sessionId =
                    vm.state.value.session
                        ?.id
                vm.cancel()
                runCurrent()
                assertEquals(TimerStatus.IDLE, vm.state.value.status)
                assertEquals(listOf(sessionId), cueOutput.stoppedIds)
                // Nach Abbruch ist ein Neustart moeglich (reset lief mit).
                vm.startRest(30_000)
                runCurrent()
                assertEquals(TimerStatus.RUNNING, vm.state.value.status)
            }
        }

    @Test
    fun `Ticker schliesst ab, acknowledge kehrt zu IDLE zurueck`() =
        runTest(dispatcher) {
            withViewModel { vm ->
                vm.startRest(1_000)
                runCurrent()
                clock.advanceBy(2_000)
                advanceTimeBy(1_000)
                runCurrent()
                assertEquals(TimerStatus.COMPLETED, vm.state.value.status)
                vm.acknowledgeFinished()
                runCurrent()
                assertEquals(TimerStatus.IDLE, vm.state.value.status)
            }
        }
}
