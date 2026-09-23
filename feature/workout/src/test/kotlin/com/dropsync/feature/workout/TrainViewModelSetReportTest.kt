package com.dropsync.feature.workout

import androidx.lifecycle.viewModelScope
import app.cash.turbine.test
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
 * P2-17/RC-7: Nach jedem gestoppten Satz erscheint der Diagnose-Report
 * (Snackbar) und landet zugleich im [com.dropsync.domain.sensor.SetDiagnosticsLog],
 * aus dem das Diagnose-Panel in den Einstellungen den letzten Stand liest.
 *
 * Eigenes File, weil [TrainViewModelTest] sonst die detekt-LargeClass-Grenze
 * reisst; das Setup ist bewusst dasselbe.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TrainViewModelSetReportTest {
    private val dispatcher = StandardTestDispatcher()

    private lateinit var workoutRepository: FakeWorkoutRepository
    private lateinit var flatSetRepository: FakeFlatSetRepository
    private lateinit var sensorProvider: FakeSensorProvider
    private lateinit var calibrationProfileRepository: FakeCalibrationProfileRepository
    private lateinit var timerEngine: TimerEngine
    private lateinit var shadowSessionRecorder: FakeShadowSessionRecorder
    private lateinit var heartRateSource: FakeHeartRateSource
    private lateinit var diagnosticsLog: FakeSetDiagnosticsLog
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
        diagnosticsLog = FakeSetDiagnosticsLog()
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
            setDiagnosticsLog = diagnosticsLog,
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

    @Test
    fun `satz-report erscheint nach stop und landet im diagnose-log`() =
        runTest(dispatcher) {
            withViewModel { vm ->
                val exercise = ExerciseInfo(id = 1L, slug = "curl", displayName = "Curl")
                calibrationProfileRepository.put(profile(exerciseId = 1L))
                sensorProvider.setConnectedDeviceId("AA:BB:CC:DD:EE:FF")
                vm.selectExercise(exercise)
                dispatcher.scheduler.runCurrent()

                sensorProvider.setConnectionState(SensorConnectionState.STREAMING)
                vm.startCountedSet()
                dispatcher.scheduler.advanceTimeBy(3_100)
                dispatcher.scheduler.runCurrent()

                val settle = List(60) { 0.0 }
                val rep =
                    (1..15).map { 60.0 * it / 15.0 } +
                        (1..30).map { 60.0 - 120.0 * it / 30.0 } +
                        (1..15).map { -60.0 + 60.0 * it / 15.0 }
                val samples =
                    (settle + rep + List(60) { 0.0 } + rep + List(40) { 0.0 })
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

                vm.setReport.test {
                    vm.stopCountedSet()
                    dispatcher.scheduler.runCurrent()
                    val report = awaitItem()
                    assertEquals(2, report.countedReps)
                    assertEquals(true, report.framesProcessed > 0)
                }
                assertEquals(
                    "Report muss im Diagnose-Log landen (Diagnose-Panel)",
                    1,
                    diagnosticsLog.recorded.size,
                )
                assertEquals(2, diagnosticsLog.recorded.single().countedReps)
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
