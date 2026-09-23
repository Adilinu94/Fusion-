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
 * A4 (T-4): Die Recording-Session gehoert dem ViewModel-Leben, nicht dem
 * einzelnen Satz oder der Sensorverbindung. Vorher beendete `finishExercise`
 * (und `disconnectSensor`) die Aufzeichnung, sodass alles nach dem ersten
 * Abschluss aus dem Corpus fiel.
 *
 * Eigenes File, weil [TrainViewModelTest] sonst die detekt-LargeClass-Grenze
 * reisst; das Setup ist bewusst dasselbe.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TrainViewModelShadowSessionTest {
    private val dispatcher = StandardTestDispatcher()

    private lateinit var workoutRepository: FakeWorkoutRepository
    private lateinit var flatSetRepository: FakeFlatSetRepository
    private lateinit var sensorProvider: FakeSensorProvider
    private lateinit var calibrationProfileRepository: FakeCalibrationProfileRepository
    private lateinit var timerEngine: TimerEngine
    private lateinit var recorder: RecordingShadowSessionRecorder

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
        recorder = RecordingShadowSessionRecorder()
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
            shadowSessionRecorder = recorder,
            heartRateSource = FakeHeartRateSource(),
            dropRestRequestBus = FakeDropRestRequestBus(),
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

    @Test
    fun `zwei saetze nach finishExercise liegen in derselben recording-session`() =
        runTest(dispatcher) {
            withViewModel { vm ->
                val exercise = ExerciseInfo(id = 1L, slug = "curl", displayName = "Curl")
                vm.selectExercise(exercise)
                dispatcher.scheduler.runCurrent()

                vm.setWeight("20")
                vm.setReps("8")
                dispatcher.scheduler.runCurrent()
                vm.logSet()
                dispatcher.scheduler.runCurrent()

                vm.finishExercise()
                dispatcher.scheduler.runCurrent()

                vm.selectExercise(exercise)
                dispatcher.scheduler.runCurrent()
                vm.setWeight("20")
                vm.setReps("6")
                dispatcher.scheduler.runCurrent()
                vm.logSet()
                dispatcher.scheduler.runCurrent()

                assertEquals(
                    "beide Saetze muessen aufgezeichnet sein",
                    2,
                    recorder.recordedSets.size,
                )
                assertEquals(
                    "finishExercise darf die Recording-Session nicht beenden",
                    0,
                    recorder.endSessionCalls,
                )
                assertEquals("genau eine Recording-Session", 1, recorder.startedSessions.size)
            }
        }

    @Test
    fun `disconnectSensor beendet die recording-session nicht`() =
        runTest(dispatcher) {
            withViewModel { vm ->
                vm.disconnectSensor()
                dispatcher.scheduler.runCurrent()

                assertEquals(0, recorder.endSessionCalls)
            }
        }

    private class RecordingShadowSessionRecorder : ShadowSessionRecorder {
        val startedSessions = mutableListOf<String>()
        val recordedSets = mutableListOf<ShadowDiffEvent>()
        var endSessionCalls = 0

        override suspend fun startSession(sessionId: String) {
            startedSessions += sessionId
        }

        override suspend fun recordSet(event: ShadowDiffEvent) {
            recordedSets += event
        }

        override suspend fun recordSamples(window: SampleWindow) = Unit

        override suspend fun endSession() {
            endSessionCalls++
        }
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
