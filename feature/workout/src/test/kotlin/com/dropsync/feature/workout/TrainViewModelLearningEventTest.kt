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
import com.dropsync.domain.sensor.calibration.ProfileLearningEvent
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

/**
 * P1-12/RC-6: Der Lernpfad meldet sein Ergebnis als Ereignis (statt nur ins
 * Log). Geprueft wird der erreichbare Skip-Pfad: Widerspricht die
 * bestaetigte Zahl der im Signal messbaren Periodizitaet, lernt die App
 * nichts — und sagt das jetzt sichtbar.
 *
 * Eigenes File, weil [TrainViewModelTest] sonst die detekt-LargeClass-Grenze
 * reisst; das Setup ist bewusst dasselbe.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TrainViewModelLearningEventTest {
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

    @Test
    fun `unplausible Bestaetigung wird als Lern-Ereignis gemeldet`() =
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
                vm.stopCountedSet()
                dispatcher.scheduler.runCurrent()

                val learningEvents = mutableListOf<ProfileLearningEvent>()
                backgroundScope.launch { vm.learningEvent.collect { learningEvents += it } }
                dispatcher.scheduler.runCurrent()

                vm.setWeight("20")
                // B2 (RC-19): Die Autokorrelation rundet die letzte,
                // angeschnittene Wiederholung jetzt auf (statt kaufmaennisch)
                // — die Schaetzung liegt damit naeher an einer plausiblen
                // Korrektur. Fuer den Veto-Fall (Abweichung > 2) braucht es
                // deshalb eine eindeutig unplausible Eingabe.
                vm.setReps("6")
                dispatcher.scheduler.runCurrent()
                vm.logSet()
                dispatcher.scheduler.runCurrent()

                assertEquals(
                    "Signal und Eingabe passen nicht zusammen -> sichtbarer Skip",
                    listOf(ProfileLearningEvent.SkippedImplausible),
                    learningEvents,
                )
                assertEquals(0, calibrationProfileRepository.saved.size)
            }
        }

    @Test
    fun `zu kurzer Satz wird als nicht reproduzierbar gemeldet`() =
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

                // T-10/S-7: 45 Samples (< 1 s) — der Refiner kann daraus kein
                // Kandidatenprofil bilden; vorher war dieser Pfad stumm.
                val samples =
                    List(45) { i ->
                        SensorSample(
                            timestampMs = i * 20L,
                            ax = 0.0,
                            ay = 0.0,
                            az = 9.8,
                            gx = 0.0,
                            gy = 0.0,
                            gz =
                                if (i in
                                    10..39
                                ) {
                                    60.0 * kotlin.math.sin(2.0 * kotlin.math.PI * (i - 10) / 15.0)
                                } else {
                                    0.0
                                },
                        )
                    }
                emitStream(samples)
                vm.stopCountedSet()
                dispatcher.scheduler.runCurrent()

                val learningEvents = mutableListOf<ProfileLearningEvent>()
                backgroundScope.launch { vm.learningEvent.collect { learningEvents += it } }
                dispatcher.scheduler.runCurrent()

                vm.setWeight("20")
                vm.setReps("2")
                dispatcher.scheduler.runCurrent()
                vm.logSet()
                dispatcher.scheduler.runCurrent()

                assertEquals(
                    "Zu kurzer Satz -> sichtbarer Nicht-reproduzierbar-Skip",
                    listOf(ProfileLearningEvent.SkippedNotReproducible),
                    learningEvents,
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
