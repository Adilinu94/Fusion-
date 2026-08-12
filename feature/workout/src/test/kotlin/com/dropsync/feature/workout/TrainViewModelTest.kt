package com.dropsync.feature.workout

import app.cash.turbine.test
import com.dropsync.core.common.AppResult
import com.dropsync.core.testing.FakeClock
import com.dropsync.core.testing.FakeFlatSetRepository
import com.dropsync.core.testing.FakeRestTimerPreferencesRepository
import com.dropsync.core.testing.FakeSensorProvider
import com.dropsync.core.testing.FakeWorkoutRepository
import com.dropsync.domain.sensor.CalibrationProfile
import com.dropsync.domain.sensor.CalibrationProfileRepository
import com.dropsync.domain.timer.CueOutput
import com.dropsync.domain.timer.RestTimerServiceStarter
import com.dropsync.domain.timer.TimerEngine
import com.dropsync.domain.workout.ExerciseInfo
import com.dropsync.feature.workout.shadow.ShadowDiffEvent
import com.dropsync.feature.workout.shadow.ShadowSessionRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
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

    @Test
    fun `repsInput bleibt leer wenn kein Live-Set lief (Shadow zaehlt nicht vor)`() =
        runTest(dispatcher) {
            val vm = viewModel()
            // Kein startCountedSet, kein stopCountedSet: nur Shadow wuerde
            // (bei Profil + Samples) zaehlen. Ohne Live-Set darf nichts in
            // _repsInput landen.
            vm.repsInput.test {
                assertEquals("", awaitItem())
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `liveCountedReps bleibt 0 ohne aktives Set auch wenn Samples fliessen`() =
        runTest(dispatcher) {
            val vm = viewModel()
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

    @Test
    fun `stopCountedSet ohne gezaehlte Reps ueberschreibt repsInput nicht`() =
        runTest(dispatcher) {
            val vm = viewModel()
            vm.setReps("7")
            // Ohne startCountedSet ist liveEngine null und _liveCountedReps=0;
            // stopCountedSet() darf die Nutzereingabe nicht mit "0" ersetzen.
            vm.stopCountedSet()
            dispatcher.scheduler.runCurrent()
            assertEquals("7", vm.repsInput.value)
        }

    @Test
    fun `bestaetigte Reps aus logSet sind die einzige Wahrheit die liveRepCount erhoeht`() =
        runTest(dispatcher) {
            val vm = viewModel()
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

    @Test
    fun `setReps markiert Edit auch wenn derselbe Wert erneut eingetippt wird`() =
        runTest(dispatcher) {
            val vm = viewModel()
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

    @Test
    fun `repsInputEdited nur durch setReps wahr, logSet setzt fuer den naechsten Satz zurueck`() =
        runTest(dispatcher) {
            val vm = viewModel()
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

    @Test
    fun `logSet zeichnet ein ShadowDiffEvent mit confirmedRepsEdited=true auf`() =
        runTest(dispatcher) {
            val vm = viewModel()
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

    // Absichtlich kein Test fuer "unveraenderte Vorbefuellung -> edited=false
    // im Event": dafuer braucht es einen echten liveCountedReps > 0-Zustand,
    // den die aktuellen Fakes (kein Kalibrierungsprofil, kein simulierter
    // Live-Count) nicht sauber treiben koennen - dieselbe Fake-Luecke, die
    // ExerciseEnginePipelineIsolationTest.kt (:domain:sensor) bereits als
    // offenen Punkt fuer den ViewModel-Level-Test benennt. Lieber offen
    // lassen als einen Test schreiben, der etwas anderes prueft als er
    // behauptet.

    // --- Fakes ------------------------------------------------------------
    // Sensor/FlatSet/Workout/RestTimerPrefs/Clock kommen aus :core:testing
    // (Testinfra-Umbau Schritt 2); nur der hier spezifische Shadow-Recorder,
    // das Kalibrierungs-Repo und die No-Op-Cues bleiben lokal.

    private class FakeCalibrationProfileRepository : CalibrationProfileRepository {
        override suspend fun load(exerciseId: Long, deviceId: String): AppResult<CalibrationProfile?> =
            AppResult.Success(null)

        override suspend fun save(profile: CalibrationProfile): AppResult<Unit> = AppResult.Success(Unit)
        override suspend fun delete(exerciseId: Long, deviceId: String): AppResult<Unit> = AppResult.Success(Unit)
    }

    private class FakeShadowSessionRecorder : ShadowSessionRecorder {
        val recorded = mutableListOf<ShadowDiffEvent>()

        override fun recordSet(event: ShadowDiffEvent) {
            recorded += event
        }
    }

    private class NoOpCueOutput : CueOutput {
        override fun speak(cueSessionId: String, secondsRemaining: Int) = Unit
        override fun haptic(cueSessionId: String) = Unit
        override fun tone(cueSessionId: String) = Unit
        override fun stopAll(cueSessionId: String) = Unit
    }
}
