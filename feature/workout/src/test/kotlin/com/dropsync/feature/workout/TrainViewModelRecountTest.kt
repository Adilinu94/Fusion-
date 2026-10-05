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
import com.dropsync.domain.timer.RestTimerServiceStarter
import com.dropsync.domain.timer.TimerEngine
import com.dropsync.domain.workout.ExerciseInfo
import com.dropsync.feature.workout.shadow.NoOpShadowSessionRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

@OptIn(ExperimentalCoroutinesApi::class)
class TrainViewModelRecountTest {
    private val dispatcher = StandardTestDispatcher()

    private lateinit var workoutRepository: FakeWorkoutRepository
    private lateinit var flatSetRepository: FakeFlatSetRepository
    private lateinit var sensorProvider: FakeSensorProvider
    private lateinit var calibrationProfileRepository: FakeCalibrationProfileRepository
    private lateinit var timerEngine: TimerEngine
    private lateinit var heartRateSource: FakeHeartRateSource

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        workoutRepository = FakeWorkoutRepository()
        flatSetRepository = FakeFlatSetRepository()
        sensorProvider = FakeSensorProvider()
        calibrationProfileRepository = FakeCalibrationProfileRepository()
        timerEngine = TimerEngine(clock = FakeClock(), cueOutput = NoOpCueOutput())
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
            setLogHaptics = NoopSetLogHaptics,
            timerEngine = timerEngine,
            restTimerServiceStarter = RestTimerServiceStarter { },
            sensorProvider = sensorProvider,
            calibrationProfileRepository = calibrationProfileRepository,
            setDiagnosticsLog = FakeSetDiagnosticsLog(),
            restTimerPreferences = FakeRestTimerPreferencesRepository(),
            restMusicSettings = FakeRestMusicSettingsRepository(),
            dropSyncStateSource = FakeDropSyncStateSource(),
            shadowSessionRecorder = NoOpShadowSessionRecorder(),
            heartRateSource = heartRateSource,
            dropRestRequestBus = FakeDropRestRequestBus(),
            browseRepository = FakeLibraryBrowseRepository(),
            audioEngine = FakeAudioEngineRepository(),
            healthPermissionContract = TestHealthPermissionContract(),
            clock = FakeClock(),
            dispatchers = TestDispatcherProvider(dispatcher),
        )

    private suspend fun kotlinx.coroutines.test.TestScope.withViewModel(block: suspend (TrainViewModel) -> Unit) {
        val vm = viewModel()
        try {
            block(vm)
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    private fun profile() =
        CalibrationProfile(
            exerciseId = 1L,
            deviceId = "AA:BB:CC:DD:EE:FF",
            rotationAxis = listOf(1.0, 0.0, 0.0),
            gyroBias = listOf(0.0, 0.0, 0.0),
            repTemplate = List(64) { 0.0 },
            expectedProminence = 1.0,
            // Schwelle weit ueber dem Signal (Amplitude 60): die Live-Zaehlung sieht keinen Peak und
            // zaehlt 0. Die Nachzaehlung arbeitet profilunabhaengig auf der Satz-Amplitude und zaehlt
            // den Satz trotzdem - so ist die Abweichung deterministisch.
            detectionThreshold = 500.0,
            noiseFloor = 5.0,
            expectedDurationMs = 2_400.0,
        )

    /** [reps] saubere Wiederholungen (Sinus-Lappen, 2,4 s) bei 50 Hz mit Ruhe davor und danach. */
    private fun cleanSet(reps: Int): List<SensorSample> {
        val signal = ArrayList<Double>()
        repeat(60) { signal.add(0.0) }
        repeat(reps) {
            for (k in 0 until 54) signal.add(60.0 * sin(PI * (k + 0.5) / 54))
            for (k in 0 until 66) signal.add(-60.0 * 54.0 / 66.0 * sin(PI * (k + 0.5) / 66))
        }
        repeat(100) { signal.add(0.0) }
        return signal.mapIndexed { i, gx ->
            SensorSample(timestampMs = i * 20L, ax = 0.0, ay = 0.0, az = 9.8, gx = gx, gy = 0.0, gz = 0.0)
        }
    }

    private fun kotlinx.coroutines.test.TestScope.emitStream(samples: List<SensorSample>) {
        samples.chunked(32).forEach { chunk ->
            chunk.forEach { sensorProvider.emit(it) }
            testScheduler.runCurrent()
        }
    }

    /** Faehrt ein Live-Set hoch, spielt [samples] ein und stoppt es. */
    private suspend fun kotlinx.coroutines.test.TestScope.runSet(
        vm: TrainViewModel,
        samples: List<SensorSample>,
    ) {
        calibrationProfileRepository.put(profile())
        sensorProvider.setConnectedDeviceId("AA:BB:CC:DD:EE:FF")
        vm.selectExercise(ExerciseInfo(id = 1L, slug = "curl", displayName = "Curl"))
        testScheduler.runCurrent()
        sensorProvider.setConnectionState(SensorConnectionState.STREAMING)
        vm.startCountedSet()
        testScheduler.advanceTimeBy(3_100)
        testScheduler.runCurrent()
        emitStream(samples)
        vm.stopCountedSet()
        testScheduler.runCurrent()
    }

    @Test
    fun `ohne abgeschlossenes Set gibt es keinen Vorschlag`() =
        runTest(dispatcher) {
            withViewModel { vm -> assertNull(vm.recountSuggestion.value) }
        }

    @Test
    fun `abweichende Nachzaehlung wird als Vorschlag mit beiden Zahlen angeboten`() =
        runTest(dispatcher) {
            withViewModel { vm ->
                runSet(vm, cleanSet(10))

                assertEquals(RecountSuggestion(liveReps = 0, analysisReps = 10), vm.recountSuggestion.value)
            }
        }

    @Test
    fun `adoptRecount setzt die Zahl und raeumt den Vorschlag ab`() =
        runTest(dispatcher) {
            withViewModel { vm ->
                runSet(vm, cleanSet(10))
                requireNotNull(vm.recountSuggestion.value) { "Vorschlag erwartet" }

                vm.adoptRecount()
                testScheduler.runCurrent()

                assertEquals("10", vm.repsInput.value)
                assertTrue("die Uebernahme ist eine aktive Korrektur (D3)", vm.repsInputEdited.value)
                assertNull(vm.recountSuggestion.value)
            }
        }

    @Test
    fun `manuelles Editieren laesst den Vorschlag verschwinden und uebernimmt nichts`() =
        runTest(dispatcher) {
            withViewModel { vm ->
                runSet(vm, cleanSet(10))
                requireNotNull(vm.recountSuggestion.value) { "Vorschlag erwartet" }

                vm.setReps("9")
                testScheduler.runCurrent()

                assertNull(vm.recountSuggestion.value)
                assertEquals("9", vm.repsInput.value)
            }
        }
}
