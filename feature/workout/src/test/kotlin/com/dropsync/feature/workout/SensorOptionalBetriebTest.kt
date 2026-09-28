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
import com.dropsync.domain.timer.RestTimerServiceStarter
import com.dropsync.domain.timer.TimerEngine
import com.dropsync.domain.workout.ExerciseInfo
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Die App bleibt ohne Sensor vollständig nutzbar: manuelle Sätze sind
 * speicherbar, während Auto-Zählen ohne Verbindung/Kalibrierung ehrlich
 * als nicht verfügbar angezeigt wird.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SensorOptionalBetriebTest {
    private val dispatcher = StandardTestDispatcher()

    private lateinit var workoutRepository: FakeWorkoutRepository
    private lateinit var flatSetRepository: FakeFlatSetRepository
    private lateinit var sensorProvider: FakeSensorProvider
    private lateinit var calibrationProfileRepository: FakeCalibrationProfileRepository
    private lateinit var timerEngine: TimerEngine
    private lateinit var shadowSessionRecorder: FakeShadowSessionRecorder
    private lateinit var heartRateSource: FakeHeartRateSource
    private val dropRestRequestBus = FakeDropRestRequestBus()

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

    @Test
    fun `manueller satz laesst sich ohne sensor speichern`() =
        runTest(dispatcher) {
            withViewModel { vm ->
                assertNull("Es ist kein Sensor verbunden", vm.connectedDeviceId.value)
                vm.selectExercise(EXERCISE)
                runCurrent()
                vm.setWeight("80")
                vm.setReps("8")
                runCurrent()

                assertTrue("Manuelle Eingaben muessen speicherbar sein", vm.canLog)
                assertFalse("Ohne Chip gibt es keine Auto-Kalibrierung", vm.hasCalibration.value)
                vm.logSet()
                runCurrent()
                assertTrue("Der manuelle Satz muss gespeichert worden sein", flatSetRepository.logged.isNotEmpty())
            }
        }

    @Test
    fun `geraeteprofil wird nach verbindung geladen`() =
        runTest(dispatcher) {
            calibrationProfileRepository.put(profile(DEVICE_ID))
            withViewModel { vm ->
                vm.selectExercise(EXERCISE)
                runCurrent()
                assertFalse("Ohne verbundenen Chip bleibt Auto-Zaehlen aus", vm.hasCalibration.value)

                sensorProvider.setConnectedDeviceId(DEVICE_ID)
                runCurrent()
                assertTrue("Nach Verbindung muss das Geraeteprofil geladen werden", vm.hasCalibration.value)
            }
        }

    @Test
    fun `sensor ohne profil bleibt manuell nutzbar`() =
        runTest(dispatcher) {
            sensorProvider.setConnectionState(SensorConnectionState.CONNECTED)
            sensorProvider.setConnectedDeviceId(DEVICE_ID)
            withViewModel { vm ->
                vm.selectExercise(EXERCISE)
                runCurrent()
                assertFalse("Ohne Profil ist Auto-Zaehlen nicht startbar", vm.hasCalibration.value)
                vm.setWeight("60")
                vm.setReps("10")
                runCurrent()
                assertTrue("Manuelle Eingabe bleibt auch mit Sensor ohne Profil moeglich", vm.canLog)
            }
        }

    private fun profile(deviceId: String) =
        CalibrationProfile(
            exerciseId = EXERCISE.id,
            deviceId = deviceId,
            rotationAxis = listOf(1.0, 0.0, 0.0),
            gyroBias = listOf(0.0, 0.0, 0.0),
            repTemplate = List(64) { 0.0 },
            expectedProminence = 1.0,
            detectionThreshold = 15.0,
            noiseFloor = 5.0,
            expectedDurationMs = 2_000.0,
        )

    private suspend fun TestScope.withViewModel(block: suspend (TrainViewModel) -> Unit) {
        val vm =
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
                shadowSessionRecorder = shadowSessionRecorder,
                heartRateSource = heartRateSource,
                dropRestRequestBus = dropRestRequestBus,
                browseRepository = FakeLibraryBrowseRepository(),
                audioEngine = FakeAudioEngineRepository(),
                healthPermissionContract = TestHealthPermissionContract(),
                clock = FakeClock(),
                dispatchers = TestDispatcherProvider(dispatcher),
            )
        try {
            block(vm)
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    private companion object {
        const val DEVICE_ID = "AA:BB:CC:DD:EE:FF"

        val EXERCISE = ExerciseInfo(id = 1L, slug = "bench-press", displayName = "Bankdrücken")
    }
}
