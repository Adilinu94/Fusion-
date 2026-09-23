package com.dropsync.feature.workout

import androidx.lifecycle.viewModelScope
import com.dropsync.core.common.AppError
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
import kotlinx.coroutines.launch
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
import kotlin.math.PI
import kotlin.math.sin

/**
 * T-10/S-7: Fehler der Train-Pfade sind sichtbar (Einmal-Ereignis fuer die
 * Snackbar) statt still. Je Pfad ein Test: Uebung anlegen, Kalibrierprofil
 * laden, gelerntes Profil speichern.
 *
 * Eigenes File (wie [TrainViewModelLearningEventTest]), damit
 * [TrainViewModelTest] die detekt-LargeClass-Grenze nicht reisst; das Setup
 * ist bewusst dasselbe.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TrainViewModelErrorEventTest {
    private val dispatcher = StandardTestDispatcher()

    private lateinit var workoutRepository: FakeWorkoutRepository
    private lateinit var flatSetRepository: FakeFlatSetRepository
    private lateinit var sensorProvider: FakeSensorProvider
    private lateinit var calibrationProfileRepository: FakeCalibrationProfileRepository
    private lateinit var timerEngine: TimerEngine
    private lateinit var heartRateSource: FakeHeartRateSource
    private val dropRestRequestBus = FakeDropRestRequestBus()

    private fun profile(
        exerciseId: Long = 1L,
        deviceId: String = "AA:BB:CC:DD:EE:FF",
        template: List<Double> = List(64) { 0.0 },
        durationMs: Double = 1_300.0,
    ) = CalibrationProfile(
        exerciseId = exerciseId,
        deviceId = deviceId,
        rotationAxis = listOf(0.0, 0.0, 1.0),
        gyroBias = listOf(0.0, 0.0, 0.0),
        repTemplate = template,
        expectedProminence = 200.0,
        detectionThreshold = 15.0,
        noiseFloor = 5.0,
        expectedDurationMs = durationMs,
    )

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
            setLogHaptics = SetLogHaptics { },
            timerEngine = timerEngine,
            restTimerServiceStarter = RestTimerServiceStarter { },
            sensorProvider = sensorProvider,
            calibrationProfileRepository = calibrationProfileRepository,
            setDiagnosticsLog = FakeSetDiagnosticsLog(),
            restTimerPreferences = FakeRestTimerPreferencesRepository(),
            restMusicSettings = FakeRestMusicSettingsRepository(),
            dropSyncStateSource = FakeDropSyncStateSource(),
            shadowSessionRecorder = NoopShadowSessionRecorder(),
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

    @Test
    fun `createExercise-Fehler wird als Ereignis gemeldet`() =
        runTest(dispatcher) {
            withViewModel { vm ->
                workoutRepository.createExerciseFailure = AppError.DatabaseFailure("create")

                val events = mutableListOf<TrainErrorEvent>()
                backgroundScope.launch { vm.errorEvent.collect { events += it } }
                dispatcher.scheduler.runCurrent()

                vm.createExercise("Test-Uebung")
                dispatcher.scheduler.runCurrent()

                assertEquals(listOf(TrainErrorEvent.ExerciseCreationFailed), events)
            }
        }

    @Test
    fun `Profil-Load-Fehler wird als Ereignis gemeldet`() =
        runTest(dispatcher) {
            withViewModel { vm ->
                calibrationProfileRepository.loadFailure = AppError.DatabaseFailure("load")
                sensorProvider.setConnectedDeviceId("AA:BB:CC:DD:EE:FF")

                val events = mutableListOf<TrainErrorEvent>()
                backgroundScope.launch { vm.errorEvent.collect { events += it } }
                dispatcher.scheduler.runCurrent()

                vm.selectExercise(ExerciseInfo(id = 1L, slug = "curl", displayName = "Curl"))
                dispatcher.scheduler.runCurrent()

                assertEquals(listOf(TrainErrorEvent.ProfileLoadFailed), events)
            }
        }

    @Test
    fun `fehlgeschlagener Lern-Save wird als Ereignis gemeldet`() =
        runTest(dispatcher) {
            withViewModel { vm ->
                // Der gespeicherte Template ist invertiert: die Live-Pipeline
                // weist alle Reps ab (0 gezaehlt), der Refiner extrahiert aus
                // den korrigierten Peaks aber ein gueltiges Template und
                // revalidiert 5 Reps -> Kandidat entsteht, nur der Save
                // scheitert.
                calibrationProfileRepository.put(
                    profile(template = List(64) { i -> -100.0 * sin(2.0 * PI * i / 64.0) }),
                )
                calibrationProfileRepository.saveFailure = AppError.DatabaseFailure("save")
                sensorProvider.setConnectedDeviceId("AA:BB:CC:DD:EE:FF")
                vm.selectExercise(ExerciseInfo(id = 1L, slug = "curl", displayName = "Curl"))
                dispatcher.scheduler.runCurrent()

                sensorProvider.setConnectionState(SensorConnectionState.STREAMING)
                vm.startCountedSet()
                dispatcher.scheduler.advanceTimeBy(3_100)
                dispatcher.scheduler.runCurrent()

                emitStream(samples = fiveCycleSet())
                vm.stopCountedSet()
                dispatcher.scheduler.runCurrent()
                assertEquals("invertiertes Template darf nichts zaehlen", 0, vm.liveCountedReps.value)

                val events = mutableListOf<TrainErrorEvent>()
                backgroundScope.launch { vm.errorEvent.collect { events += it } }
                dispatcher.scheduler.runCurrent()

                vm.setWeight("20")
                vm.setReps("5")
                dispatcher.scheduler.runCurrent()
                vm.logSet()
                dispatcher.scheduler.runCurrent()

                assertEquals(listOf(TrainErrorEvent.LearningSaveFailed), events)
                assertEquals("kein Kandidat gespeichert", 0, calibrationProfileRepository.saved.size)
            }
        }

    private fun TestScope.emitStream(samples: List<SensorSample>) {
        samples.chunked(32).forEach { chunk ->
            chunk.forEach { sensorProvider.emit(it) }
            testScheduler.runCurrent()
        }
        // Letzter Pufferrest fuer die Pipeline.
        sensorProvider.emit(
            SensorSample(
                timestampMs = samples.size * 20L,
                ax = 0.0,
                ay = 0.0,
                az = 9.8,
                gx = 0.0,
                gy = 0.0,
                gz = 0.0,
            ),
        )
        testScheduler.runCurrent()
    }

    /** Fuenf Sinus-Zyklen (80 Samples, Amplitude 120) mit je 30 Ruhe-Samples. */
    private fun fiveCycleSet(): List<SensorSample> =
        buildList {
            var t = 0L
            repeat(60) {
                add(
                    SensorSample(
                        timestampMs = t,
                        ax = 0.0,
                        ay = 0.0,
                        az = 9.81,
                        gx = 0.0,
                        gy = 0.0,
                        gz = 0.0,
                    ),
                )
                t += 20
            }
            repeat(5) {
                for (i in 0 until 80) {
                    add(
                        SensorSample(
                            timestampMs = t,
                            ax = 0.0,
                            ay = 0.0,
                            az = 9.81,
                            gx = 0.0,
                            gy = 0.0,
                            gz = 120.0 * sin(2.0 * PI * i / 80.0),
                        ),
                    )
                    t += 20
                }
                repeat(30) {
                    add(
                        SensorSample(
                            timestampMs = t,
                            ax = 0.0,
                            ay = 0.0,
                            az = 9.81,
                            gx = 0.0,
                            gy = 0.0,
                            gz = 0.0,
                        ),
                    )
                    t += 20
                }
            }
        }

    private class NoopShadowSessionRecorder : ShadowSessionRecorder {
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
