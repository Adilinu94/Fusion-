package com.dropsync.feature.workout

import androidx.lifecycle.viewModelScope
import com.dropsync.core.testing.FakeCalibrationProfileRepository
import com.dropsync.core.testing.FakeClock
import com.dropsync.core.testing.FakeDropRestRequestBus
import com.dropsync.core.testing.FakeDropSyncStateSource
import com.dropsync.core.testing.FakeFlatSetRepository
import com.dropsync.core.testing.FakeHeartRateSource
import com.dropsync.core.testing.FakeLibraryBrowseRepository
import com.dropsync.core.testing.FakeRestMusicSettingsRepository
import com.dropsync.core.testing.FakeRestTimerPreferencesRepository
import com.dropsync.core.testing.FakeSensorProvider
import com.dropsync.core.testing.FakeSetDiagnosticsLog
import com.dropsync.core.testing.FakeWorkoutRepository
import com.dropsync.core.testing.TestDispatcherProvider
import com.dropsync.domain.sensor.CalibrationProfile
import com.dropsync.domain.sensor.SensorConnectionState
import com.dropsync.domain.sensor.SensorSample
import com.dropsync.domain.timer.CueOutput
import com.dropsync.domain.timer.RestTimerServiceStarter
import com.dropsync.domain.timer.TimerEngine
import com.dropsync.domain.workout.ExerciseInfo
import com.dropsync.domain.workout.SetLogHaptics
import com.dropsync.feature.workout.shadow.SampleWindow
import com.dropsync.feature.workout.shadow.ShadowDiffEvent
import com.dropsync.feature.workout.shadow.ShadowSessionRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * P2-20/RC-5: Quelle der Rep-Zahl im Hero (UI-Handbuch 7.4). Die Regeln
 * selbst liegen in [repsSourceOf] (siehe [WorkoutConsoleStateTest]); hier
 * geht es um die Verdrahtung im [TrainViewModel]: Zaehlstand aus dem Stop,
 * Korrektur, Abriss und das Zuruecksetzen nach dem Loggen.
 *
 * Eigenes File, weil [TrainViewModelTest] sonst die detekt-LargeClass-Grenze
 * reisst; das Setup ist bewusst dasselbe.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TrainViewModelRepSourceTest {
    private val dispatcher = StandardTestDispatcher()

    private lateinit var workoutRepository: FakeWorkoutRepository
    private lateinit var flatSetRepository: FakeFlatSetRepository
    private lateinit var sensorProvider: FakeSensorProvider
    private lateinit var calibrationProfileRepository: FakeCalibrationProfileRepository
    private lateinit var timerEngine: TimerEngine
    private lateinit var shadowSessionRecorder: FakeShadowSessionRecorder
    private lateinit var heartRateSource: FakeHeartRateSource
    private val dropRestRequestBus = FakeDropRestRequestBus()

    private fun profile(
        exerciseId: Long = 1L,
        deviceId: String = "AA:BB:CC:DD:EE:FF",
    ) = CalibrationProfile(
        exerciseId = exerciseId,
        deviceId = deviceId,
        rotationAxis = listOf(1.0, 0.0, 0.0),
        gyroBias = listOf(0.0, 0.0, 0.0),
        repTemplate = List(64) { 0.0 },
        expectedProminence = 1.0,
        detectionThreshold = 15.0,
        noiseFloor = 5.0,
        expectedDurationMs = 2_000.0,
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
        heartRateSource = FakeHeartRateSource()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel(): TrainViewModel =
        TrainViewModel(
            workoutRepository = workoutRepository,
            flatSetRepository = flatSetRepository,
            setLogHaptics = SetLogHaptics { },
            timerEngine = timerEngine,
            restTimerServiceStarter = RestTimerServiceStarter { },
            sensorProvider = sensorProvider,
            calibrationProfileRepository = calibrationProfileRepository,
            setDiagnosticsLog = FakeSetDiagnosticsLog(),
            restTimerPreferences = FakeRestTimerPreferencesRepository(),
            restMusicSettings = FakeRestMusicSettingsRepository(),
            dropSyncStateSource = FakeDropSyncStateSource(),
            shadowSessionRecorder = shadowSessionRecorder,
            heartRateSource = heartRateSource,
            dropRestRequestBus = dropRestRequestBus,
            browseRepository = FakeLibraryBrowseRepository(),
            audioEngine = FakeAudioEngineRepository(),
            healthPermissionContract = TestHealthPermissionContract(),
            clock = FakeClock(),
            dispatchers = TestDispatcherProvider(dispatcher),
        )

    private suspend fun TestScope.withViewModel(block: suspend (TrainViewModel) -> Unit) {
        val vm = viewModel()
        try {
            block(vm)
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    private fun TestScope.emitStream(samples: List<SensorSample>) {
        samples.chunked(32).forEach { chunk ->
            chunk.forEach { sensorProvider.emit(it) }
            testScheduler.runCurrent()
        }
    }

    /**
     * Startet ein Live-Set, laesst den Countdown ablaufen und spielt [reps]
     * vollstaendige Zwei-Phasen-Zyklen auf gx ein (Profil-Achse [1,0,0]).
     */
    private fun TestScope.runLiveSet(
        vm: TrainViewModel,
        reps: Int,
    ) {
        sensorProvider.setConnectionState(SensorConnectionState.STREAMING)
        vm.startCountedSet()
        dispatcher.scheduler.advanceTimeBy(3_100)
        dispatcher.scheduler.runCurrent()
        val cycle =
            (1..15).map { 60.0 * it / 15.0 } +
                (1..30).map { 60.0 - 120.0 * it / 30.0 } +
                (1..15).map { -60.0 + 60.0 * it / 15.0 }
        val samples =
            (List(60) { 0.0 } + (0 until reps).flatMap { cycle + List(60) { 0.0 } } + List(40) { 0.0 })
                .mapIndexed { i, gx ->
                    SensorSample(
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
    }

    private fun TestScope.prepareCountedSet(vm: TrainViewModel) {
        calibrationProfileRepository.put(profile(exerciseId = 1L))
        sensorProvider.setConnectedDeviceId("AA:BB:CC:DD:EE:FF")
        vm.selectExercise(ExerciseInfo(id = 1L, slug = "curl", displayName = "Curl"))
        dispatcher.scheduler.runCurrent()
    }

    @Test
    fun `Rep-Quelle ohne Zaehlstand ist MANUELL`() =
        runTest(dispatcher) {
            withViewModel { vm ->
                assertEquals(RepsSource.Manual, vm.repsSource.value)
                vm.selectExercise(ExerciseInfo(id = 1L, slug = "curl", displayName = "Curl"))
                vm.setReps("10")
                dispatcher.scheduler.runCurrent()
                assertEquals(
                    "Handeingabe ohne Zaehlstand bleibt MANUELL",
                    RepsSource.Manual,
                    vm.repsSource.value,
                )
            }
        }

    @Test
    fun `gestoppter Live-Satz setzt die Quelle auf AUTO`() =
        runTest(dispatcher) {
            withViewModel { vm ->
                prepareCountedSet(vm)
                runLiveSet(vm, reps = 2)
                assertEquals(2, vm.liveCountedReps.value)

                vm.stopCountedSet()
                dispatcher.scheduler.runCurrent()

                assertEquals("2", vm.repsInput.value)
                assertEquals(
                    "ungeprueft uebernommener Zaehlstand ist AUTO",
                    RepsSource.Sensor,
                    vm.repsSource.value,
                )
            }
        }

    @Test
    fun `Korrektur nach dem Stop macht die Quelle zu MANUELL KORRIGIERT`() =
        runTest(dispatcher) {
            withViewModel { vm ->
                prepareCountedSet(vm)
                runLiveSet(vm, reps = 2)
                vm.stopCountedSet()
                dispatcher.scheduler.runCurrent()

                vm.setReps("3")
                dispatcher.scheduler.runCurrent()

                assertEquals(
                    "die Quelle traegt den Original-Zaehlstand fuer den Hinweis",
                    RepsSource.SensorWithManualCorrection(2),
                    vm.repsSource.value,
                )
            }
        }

    @Test
    fun `logSet raeumt die Rep-Quelle fuer den naechsten Satz`() =
        runTest(dispatcher) {
            withViewModel { vm ->
                prepareCountedSet(vm)
                runLiveSet(vm, reps = 2)
                vm.stopCountedSet()
                dispatcher.scheduler.runCurrent()

                vm.setWeight("20")
                vm.setReps("2")
                dispatcher.scheduler.runCurrent()
                vm.logSet()
                dispatcher.scheduler.runCurrent()

                assertEquals(
                    "nach dem Loggen startet der naechste Satz ohne Zaehlstand",
                    RepsSource.Manual,
                    vm.repsSource.value,
                )
            }
        }

    @Test
    fun `Abriss des Streams macht die Quelle zu SENSOR GETRENNT`() =
        runTest(dispatcher) {
            withViewModel { vm ->
                sensorProvider.setConnectionState(SensorConnectionState.STREAMING)
                dispatcher.scheduler.runCurrent()
                sensorProvider.setConnectionState(SensorConnectionState.DISCONNECTED)
                dispatcher.scheduler.runCurrent()

                assertEquals(
                    "ohne Chip sind +/- der Hauptzaehler",
                    RepsSource.SensorDisconnected,
                    vm.repsSource.value,
                )
            }
        }

    // --- lokale Test-Helfer (bewusst dieselben wie TrainViewModelTest) -----

    private class FakeShadowSessionRecorder : ShadowSessionRecorder {
        override suspend fun startSession(sessionId: String) = Unit

        override suspend fun recordSet(event: ShadowDiffEvent) = Unit

        override suspend fun recordSamples(window: SampleWindow) = Unit

        override suspend fun endSession() = Unit
    }

    private class NoOpCueOutput : CueOutput {
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
    private class TestHealthPermissionContract :
        androidx.activity.result.contract.ActivityResultContract<
            Set<String>,
            Set<String>,
        >() {
        override fun createIntent(
            context: android.content.Context,
            input: Set<String>,
        ): android.content.Intent = android.content.Intent()

        override fun parseResult(
            resultCode: Int,
            intent: android.content.Intent?,
        ): Set<String> = emptySet()
    }
}
