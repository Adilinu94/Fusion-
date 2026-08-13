package com.dropsync.feature.workout

import app.cash.turbine.test
import androidx.lifecycle.viewModelScope
import com.dropsync.core.testing.FakeCalibrationProfileRepository
import com.dropsync.core.testing.FakeClock
import com.dropsync.core.testing.FakeFlatSetRepository
import com.dropsync.core.testing.FakeRestTimerPreferencesRepository
import com.dropsync.core.testing.FakeSensorProvider
import com.dropsync.core.testing.FakeWorkoutRepository
import com.dropsync.domain.sensor.CalibrationProfile
import com.dropsync.domain.sensor.SensorConnectionState
import com.dropsync.domain.timer.CueOutput
import com.dropsync.domain.timer.RestTimerServiceStarter
import com.dropsync.domain.timer.TimerEngine
import com.dropsync.domain.workout.ExerciseInfo
import com.dropsync.feature.workout.shadow.ShadowDiffEvent
import com.dropsync.feature.workout.shadow.ShadowSessionRecorder
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
 * Fund 1 / Abschnitt 11b: die Shadow-Pipeline darf die Nutzereingabe und den
 * Live-Zaehler NIEMALS beeinflussen. Der Shadow zaehlt nur fuer den Diff
 * mit; `_repsInput` und `_liveCountedReps` gehoeren ausschliesslich dem
 * Live-Pfad bzw. dem Nutzer.
 *
 * Der kritische Fall: `stopCountedSet()` fuellt `_repsInput` mit der vom
 * LIVE-Engine gezaehlten Zahl vor (TrainViewModel.kt:474-476). Wuerde hier
 * versehentlich der Shadow-Stand einfliessen, waere die "Ground-Truth" des
 * Nutzers still durch die zu validierende Pipeline selbst ersetzt — genau
 * die Unabhaengigkeits-Falle aus dem Review.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TrainViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    private lateinit var workoutRepository: FakeWorkoutRepository
    private lateinit var flatSetRepository: FakeFlatSetRepository
    private lateinit var sensorProvider: FakeSensorProvider
    private lateinit var calibrationProfileRepository: FakeCalibrationProfileRepository
    private lateinit var timerEngine: TimerEngine
    private lateinit var shadowSessionRecorder: FakeShadowSessionRecorder

    private fun profile(
        exerciseId: Long = 1L,
        deviceId: String = "AA:BB:CC:DD:EE:FF",
        axis: List<Double> = listOf(1.0, 0.0, 0.0),
        bias: List<Double> = listOf(0.0, 0.0, 0.0),
        spk: Double = 150.0,
        npk: Double = 20.0,
    ) = CalibrationProfile(
        exerciseId = exerciseId,
        deviceId = deviceId,
        rotationAxis = axis,
        gyroBias = bias,
        repTemplate = List(64) { 0.0 },
        signalPeakLevel = spk,
        noisePeakLevel = npk,
        expectedProminence = 50.0,
        expectedDurationSamples = 50.0,
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        workoutRepository = FakeWorkoutRepository()
        flatSetRepository = FakeFlatSetRepository()
        sensorProvider = FakeSensorProvider()
        calibrationProfileRepository = FakeCalibrationProfileRepository()
        timerEngine = TimerEngine(clock = FakeClock(), cueOutput = NoOpCueOutput())
        shadowSessionRecorder = FakeShadowSessionRecorder()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel(): TrainViewModel = TrainViewModel(
        workoutRepository = workoutRepository,
        flatSetRepository = flatSetRepository,
        timerEngine = timerEngine,
        restTimerServiceStarter = RestTimerServiceStarter { },
        sensorProvider = sensorProvider,
        calibrationProfileRepository = calibrationProfileRepository,
        restTimerPreferences = FakeRestTimerPreferencesRepository(),
        shadowSessionRecorder = shadowSessionRecorder,
    )

    /**
     * Erzeugt ein ViewModel und cancelt dessen Scope am Testende. Ohne das
     * Cancel haengt runTest im Cleanup (advanceUntilIdle): der 250-ms-Ticker
     * in TrainViewModel.init laeuft im viewModelScope (nicht im
     * TestScope.backgroundScope) und produziert endlos neue Tasks.
     */
    private suspend fun kotlinx.coroutines.test.TestScope.withViewModel(
        block: suspend (TrainViewModel) -> Unit,
    ) {
        val vm = viewModel()
        try {
            block(vm)
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    /**
     * Emittet Samples in Chunks mit Scheduler-Durchlauf dazwischen. Der
     * FakeSensorProvider puffert nur 64 Samples (extraBufferCapacity); ohne
     * das Chunking wuerden laengere Streams ueberlaufen und Samples verlieren.
     */
    private fun kotlinx.coroutines.test.TestScope.emitStream(
        samples: List<com.dropsync.domain.sensor.SensorSample>,
    ) {
        samples.chunked(32).forEach { chunk ->
            chunk.forEach { sensorProvider.emit(it) }
            testScheduler.runCurrent()
        }
    }

    @Test
    fun `repsInput bleibt leer wenn kein Live-Set lief (Shadow zaehlt nicht vor)`() =
        runTest(dispatcher) {
            withViewModel { vm ->
                // Kein startCountedSet, kein stopCountedSet: nur Shadow wuerde
                // (bei Profil + Samples) zaehlen. Ohne Live-Set darf nichts in
                // _repsInput landen.
                vm.repsInput.test {
                    assertEquals("", awaitItem())
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

    @Test
    fun `liveCountedReps bleibt 0 ohne aktives Set auch wenn Samples fliessen`() =
        runTest(dispatcher) {
            withViewModel { vm ->
                vm.liveCountedReps.test {
                    assertEquals(0, awaitItem())
                    // Samples ohne COUNTING-Phase duerfen den Live-Zaehler nicht
                    // bewegen (Shadow laeuft intern, schreibt aber nie hierher).
                    sensorProvider.emitSample(gyro = 12.0)
                    sensorProvider.emitSample(gyro = -8.0)
                    expectNoEvents()
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

    @Test
    fun `stopCountedSet ohne gezaehlte Reps ueberschreibt repsInput nicht`() =
        runTest(dispatcher) {
            withViewModel { vm ->
                vm.setReps("7")
                // Ohne startCountedSet ist liveEngine null und _liveCountedReps=0;
                // stopCountedSet() darf die Nutzereingabe nicht mit "0" ersetzen.
                vm.stopCountedSet()
                dispatcher.scheduler.runCurrent()
                assertEquals("7", vm.repsInput.value)
            }
        }

    @Test
    fun `bestaetigte Reps aus logSet sind die einzige Wahrheit die liveRepCount erhoeht`() =
        runTest(dispatcher) {
            withViewModel { vm ->
                vm.selectExercise(ExerciseInfo(id = 1L, slug = "curl", displayName = "Curl"))
                vm.setWeight("20")
                vm.setReps("12")
                dispatcher.scheduler.runCurrent()
                vm.logSet()
                dispatcher.scheduler.runCurrent()
                // logSet war erfolgreich, repsInput wurde fuer den naechsten Satz
                // geleert. Entscheidend: der geloggte Wert (12) kam aus dem Input,
                // nicht aus einer Engine.
                assertEquals(1, flatSetRepository.logged.size)
                assertEquals(12, flatSetRepository.logged.single().reps)
                assertEquals("", vm.repsInput.value)
            }
        }

    @Test
    fun `setReps markiert Edit auch wenn derselbe Wert erneut eingetippt wird`() =
        runTest(dispatcher) {
            withViewModel { vm ->
                assertEquals("frisch erzeugtes ViewModel: noch nichts editiert", false, vm.repsInputEdited.value)
                vm.setReps("8")
                assertEquals(true, vm.repsInputEdited.value)
                // Kein Wertevergleich (D3): Retippen desselben Werts ist trotzdem
                // eine aktive Bestaetigung, keine "unveraenderte Vorbefuellung".
                vm.setReps("8")
                assertEquals(
                    "erneutes Eintippen desselben Werts bleibt eine aktive Bestaetigung",
                    true,
                    vm.repsInputEdited.value,
                )
            }
        }

    @Test
    fun `repsInputEdited nur durch setReps wahr, logSet setzt fuer den naechsten Satz zurueck`() =
        runTest(dispatcher) {
            withViewModel { vm ->
                vm.selectExercise(ExerciseInfo(id = 1L, slug = "curl", displayName = "Curl"))
                vm.setWeight("20")
                assertEquals("setWeight ist kein Reps-Edit", false, vm.repsInputEdited.value)

                vm.setReps("12")
                assertEquals("setReps markiert eine aktive Nutzereingabe", true, vm.repsInputEdited.value)

                dispatcher.scheduler.runCurrent()
                vm.logSet()
                dispatcher.scheduler.runCurrent()

                assertEquals(1, flatSetRepository.logged.size)
                assertEquals(
                    "nach dem Loggen ist das Feld leer und wieder unediert (naechster Satz)",
                    false,
                    vm.repsInputEdited.value,
                )
            }
        }

    @Test
    fun `logSet zeichnet ein ShadowDiffEvent mit confirmedRepsEdited=true auf`() =
        runTest(dispatcher) {
            withViewModel { vm ->
                vm.selectExercise(ExerciseInfo(id = 1L, slug = "curl", displayName = "Curl"))
                vm.setWeight("20")
                vm.setReps("12")
                dispatcher.scheduler.runCurrent()
                vm.logSet()
                dispatcher.scheduler.runCurrent()

                assertEquals(1, shadowSessionRecorder.recorded.size)
                val event = shadowSessionRecorder.recorded.single()
                assertEquals("aktiv per setReps eingetippt", true, event.confirmedRepsEdited)
                assertEquals(12, event.confirmedReps)
                assertEquals(1L, event.exerciseId)
                // Kein Live-Set gelaufen: liveCountedReps/shadowReps bleiben 0 -
                // delta ist hier kein Freigabe-Signal (D3: nur bei
                // confirmedRepsEdited=true zaehlt confirmedReps ueberhaupt).
                assertEquals(0, event.liveCountedReps)
                assertEquals(0, event.shadowReps)
            }
        }

    // Absichtlich kein Test fuer "unveraenderte Vorbefuellung -> edited=false
    // im Event": dafuer braucht es einen echten liveCountedReps > 0-Zustand,
    // den die aktuellen Fakes (kein Kalibrierungsprofil, kein simulierter
    // Live-Count) nicht sauber treiben koennen - dieselbe Fake-Luecke, die
    // ExerciseEnginePipelineIsolationTest.kt (:domain:sensor) bereits als
    // offenen Punkt fuer den ViewModel-Level-Test benennt. Lieber offen
    // lassen als einen Test schreiben, der etwas anderes prueft als er
    // behauptet.

    // --- Shadow-Engine-Tests (Umbauplan Punkt 1 + 2b) ---------------------
    //
    // Der ShadowDiffEvent traegt shadowReps (der gezaehlte Stand der
    // Shadow-Pipeline). Mit Profil-Achse/Bias und korrekten SPK/NPK muss
    // die Shadow-Pipeline denselben Stream zaehlen wie die Live-Pipeline.
    // Der Event-Weg ist der einzige beobachtbare Kanal der Shadow-Pipeline
    // (shadowRepCount ist private) - deshalb pruefen diese Tests den
    // Shadow-Stand ueber den geloggten Event.

    @Test
    fun `shadow zaehlt mit Profil-Achse wenn Profil vorhanden`() =
        runTest(dispatcher) {
            withViewModel { vm ->
                val exercise = ExerciseInfo(id = 1L, slug = "curl", displayName = "Curl")
                calibrationProfileRepository.put(
                    profile(exerciseId = 1L, axis = listOf(1.0, 0.0, 0.0)),
                )
                sensorProvider.setConnectedDeviceId("AA:BB:CC:DD:EE:FF")
                vm.selectExercise(exercise)
                dispatcher.scheduler.runCurrent()

                // Start des Live-Sets (braucht STREAMING + Profil).
                sensorProvider.setConnectionState(SensorConnectionState.STREAMING)
                vm.startCountedSet()
                dispatcher.scheduler.advanceTimeBy(3_100)
                dispatcher.scheduler.runCurrent()
                assertEquals(
                    "Countdown muss nach 3.1 s abgelaufen sein",
                    TrainViewModel.SetPhase.COUNTING,
                    vm.setPhase.value,
                )

                // Zwei saubere Reps auf gx (rein positives Dreieck, wie in
                // ExerciseEnginePipelineIsolationTest verifiziert).
                val settle = List(60) { 0.0 }
                val rep =
                    (1..15).map { 60.0 * it / 15.0 } + (1..15).map { 60.0 - 60.0 * it / 15.0 }
                val samples =
                    (settle + rep + List(60) { 0.0 } + rep + List(40) { 0.0 })
                        .mapIndexed { i, gx ->
                            com.dropsync.domain.sensor.SensorSample(
                                timestampMs = i * 20L,
                                ax = 0.0,
                                ay = 0.0,
                                az = 9.8,
                                gx = gx,
                                gy = 0.0,
                                gz = 0.0,
                            )
                        }
                emitStream(samples)

                // Live-Zaehler muss beide Reps sehen (Profil-Achse [1,0,0]).
                assertEquals(2, vm.liveCountedReps.value)

                // Set beenden: Zaehlstand in repsInput, dann loggen -> Event
                // traegt den Shadow-Stand.
                vm.stopCountedSet()
                dispatcher.scheduler.runCurrent()
                assertEquals("2", vm.repsInput.value)

                vm.setWeight("20")
                vm.setReps("2")
                dispatcher.scheduler.runCurrent()
                vm.logSet()
                dispatcher.scheduler.runCurrent()

                val event = shadowSessionRecorder.recorded.single()
                assertEquals(
                    "Shadow-Pipeline muss mit Profil-Achse dieselben 2 Reps zaehlen",
                    2,
                    event.shadowReps,
                )
                assertEquals(2, event.liveCountedReps)
            }
        }

    @Test
    fun `shadow bleibt bei 0 ohne Profil auch wenn Samples fliessen`() =
        runTest(dispatcher) {
            withViewModel { vm ->
                val exercise = ExerciseInfo(id = 1L, slug = "curl", displayName = "Curl")
                sensorProvider.setConnectedDeviceId("AA:BB:CC:DD:EE:FF")
                // KEIN Profil im Fake -> Shadow-Engine faellt auf NEUTRAL_AXIS
                // (z-Achse) zurueck; gx-Samples projizieren zu 0.
                vm.selectExercise(exercise)
                dispatcher.scheduler.runCurrent()

                val rep =
                    (1..15).map { 60.0 * it / 15.0 } + (1..15).map { 60.0 - 60.0 * it / 15.0 }
                val samples =
                    (List(60) { 0.0 } + rep)
                        .mapIndexed { i, gx ->
                            com.dropsync.domain.sensor.SensorSample(
                                timestampMs = i * 20L,
                                ax = 0.0,
                                ay = 0.0,
                                az = 9.8,
                                gx = gx,
                                gy = 0.0,
                                gz = 0.0,
                            )
                        }
                emitStream(samples)

                vm.setWeight("20")
                vm.setReps("5")
                dispatcher.scheduler.runCurrent()
                vm.logSet()
                dispatcher.scheduler.runCurrent()

                val event = shadowSessionRecorder.recorded.single()
                assertEquals(
                    "ohne Profil projiziert die Shadow-Engine auf gz: gx-Samples zaehlen nicht",
                    0,
                    event.shadowReps,
                )
            }
        }

    @Test
    fun `Shadow-Recorder Lifecycle startet im init und endet bei finishExercise und disconnect`() =
        runTest(dispatcher) {
            withViewModel { vm ->
                // init -> startSession genau einmal.
                assertEquals("init muss eine Recording-Session starten", 1, shadowSessionRecorder.started.size)
                assertEquals(0, shadowSessionRecorder.ended)

                vm.finishExercise()
                assertEquals("finishExercise muss die Session beenden", 1, shadowSessionRecorder.ended)

                vm.disconnectSensor()
                assertEquals(
                    "disconnectSensor muss die Session ebenfalls beenden",
                    2,
                    shadowSessionRecorder.ended,
                )
            }
        }

    // --- Fakes ------------------------------------------------------------
    // Sensor/FlatSet/Workout/RestTimerPrefs/Clock/CalibrationProfile kommen
    // aus :core:testing (Testinfra-Umbau Schritt 2); nur der hier spezifische
    // Shadow-Recorder und die No-Op-Cues bleiben lokal.

    private class FakeShadowSessionRecorder : ShadowSessionRecorder {
        val recorded = mutableListOf<ShadowDiffEvent>()
        var started: MutableList<String> = mutableListOf()
        var ended: Int = 0

        override fun startSession(sessionId: String) {
            started += sessionId
        }

        override fun recordSet(event: ShadowDiffEvent) {
            recorded += event
        }

        override fun endSession() {
            ended++
        }
    }

    private class NoOpCueOutput : CueOutput {
        override fun speak(cueSessionId: String, secondsRemaining: Int) = Unit
        override fun haptic(cueSessionId: String) = Unit
        override fun tone(cueSessionId: String) = Unit
        override fun stopAll(cueSessionId: String) = Unit
    }
}
