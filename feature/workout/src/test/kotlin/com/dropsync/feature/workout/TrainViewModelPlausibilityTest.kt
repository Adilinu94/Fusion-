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
import com.dropsync.domain.sensor.RepCountPlausibility
import com.dropsync.domain.sensor.SensorConnectionState
import com.dropsync.domain.sensor.SensorSample
import com.dropsync.domain.timer.RestTimerServiceStarter
import com.dropsync.domain.timer.TimerEngine
import com.dropsync.domain.workout.ExerciseInfo
import com.dropsync.domain.workout.SetLogHaptics
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
import org.junit.Before
import org.junit.Test

/**
 * Umbauplan 2026-09-04 Phase 7: die Autokorrelations-Zweitmeinung wird
 * sichtbar. Diese Tests halten die drei Regeln fest, an denen die Anzeige
 * scheitern kann:
 *
 * 1. Sie erscheint nur bei deutlicher Abweichung (SUSPICIOUS), nicht bei
 *    einer Rep Unterschied — sonst stumpft sie ab und wird ignoriert.
 * 2. Sie verschwindet, sobald der Nutzer das Feld angefasst hat. Der Zweck
 *    ist eine aktive Korrektur oder Bestaetigung (D3-Regel, ADR-0014); ist
 *    die passiert, hat der Hinweis seine Aufgabe erfuellt.
 * 3. Sie ueberlebt keinen Abbruch. Ein Hinweis zu einem Satz, den es nicht
 *    mehr gibt, ist schlimmer als kein Hinweis.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TrainViewModelPlausibilityTest {
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
            setLogHaptics = SetLogHaptics { },
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
            detectionThreshold = 15.0,
            noiseFloor = 5.0,
            expectedDurationMs = 2_000.0,
        )

    /**
     * Ein Satz, der die Autokorrelation zwangslaeufig widersprechen laesst:
     * das Signal traegt acht saubere Perioden, die Peak-Detektion zaehlt aber
     * nur zwei davon als Rep (die uebrigen sind zu schwach fuer die
     * kalibrierte Schwelle). Genau der Fall, fuer den der Hinweis gebaut ist.
     */
    private fun eightPeriodTwoRepSamples(): List<SensorSample> {
        val settle = List(60) { 0.0 }
        // Zwei zaehlbare Reps (grosse Amplitude, vollstaendiger Zyklus).
        val rep =
            (1..15).map { 60.0 * it / 15.0 } +
                (1..30).map { 60.0 - 120.0 * it / 30.0 } +
                (1..15).map { -60.0 + 60.0 * it / 15.0 }
        // Danach sechs schwache Zyklen gleicher Periode: die Autokorrelation
        // sieht sie, die Schwelle (15.0) nicht.
        val weak =
            (1..6).flatMap {
                (1..15).map { i -> 8.0 * i / 15.0 } +
                    (1..30).map { i -> 8.0 - 16.0 * i / 30.0 } +
                    (1..15).map { i -> -8.0 + 8.0 * i / 15.0 }
            }
        return (settle + rep + List(60) { 0.0 } + rep + weak + List(40) { 0.0 })
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
    fun `ohne abgeschlossenes Set gibt es keinen Hinweis`() =
        runTest(dispatcher) {
            withViewModel { vm ->
                vm.plausibilityHint.test {
                    assertNull(awaitItem())
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

    @Test
    fun `deutliche Abweichung erzeugt einen Hinweis mit beiden Zahlen`() =
        runTest(dispatcher) {
            withViewModel { vm ->
                runSet(vm, eightPeriodTwoRepSamples())

                val hint = vm.plausibilityHint.value
                requireNotNull(hint) { "Hinweis erwartet bei 2 gezaehlten vs. 8 Perioden" }
                assertEquals("Hinweis muss den gezaehlten Stand tragen", 2, hint.countedReps)
                // Die exakte Schaetzung haengt am Signal; entscheidend ist,
                // dass sie deutlich abweicht und nicht der Zaehlstand ist.
                assert(hint.estimatedReps >= 4) {
                    "Periodenschaetzung sollte deutlich ueber 2 liegen, war ${hint.estimatedReps}"
                }
            }
        }

    @Test
    fun `Editieren des Rep-Felds laesst den Hinweis verschwinden`() =
        runTest(dispatcher) {
            withViewModel { vm ->
                runSet(vm, eightPeriodTwoRepSamples())
                requireNotNull(vm.plausibilityHint.value) { "Hinweis erwartet" }

                // Der Nutzer korrigiert (oder bestaetigt aktiv) die Zahl: der
                // Hinweis hat seinen Zweck erfuellt (D3, ADR-0014).
                vm.setReps("8")
                testScheduler.runCurrent()
                assertNull(vm.plausibilityHint.value)
            }
        }

    @Test
    fun `finishExercise raeumt den Hinweis ab`() =
        runTest(dispatcher) {
            withViewModel { vm ->
                runSet(vm, eightPeriodTwoRepSamples())
                requireNotNull(vm.plausibilityHint.value) { "Hinweis erwartet" }

                vm.finishExercise()
                testScheduler.runCurrent()
                assertNull(
                    "ein Hinweis zu einem beendeten Satz darf nicht stehenbleiben",
                    vm.plausibilityHint.value,
                )
            }
        }

    // --- Abbildungsregel isoliert (ohne Pipeline) --------------------------
    //
    // Die Schwelle "nur SUSPICIOUS" ist die eine Designentscheidung dieser
    // Phase, die vom Umbauplan abweicht (der nannte "Abweichung > 0"). Sie
    // wird deshalb direkt geprueft, nicht nur ueber einen Pipelinelauf.

    @Test
    fun `BORDERLINE erzeugt keinen Hinweis`() {
        val result =
            RepCountPlausibility.Result(
                periodSeconds = 2.0,
                estimatedReps = 9,
                periodicityStrength = 0.8,
                verdict = RepCountPlausibility.Verdict.BORDERLINE,
                countedReps = 8,
            )
        assertNull(
            "eine Rep Abweichung ist am Set-Ende der Normalfall, kein Verdacht",
            result.toHintOrNull(),
        )
    }

    @Test
    fun `CONSISTENT und INCONCLUSIVE erzeugen keinen Hinweis`() {
        val consistent =
            RepCountPlausibility.Result(2.0, 8, 0.9, RepCountPlausibility.Verdict.CONSISTENT, 8)
        val inconclusive =
            RepCountPlausibility.Result(null, null, 0.1, RepCountPlausibility.Verdict.INCONCLUSIVE, 8)
        assertNull(consistent.toHintOrNull())
        // Wichtig: INCONCLUSIVE ist "keine Aussage", nicht "bestaetigt" - und
        // genau deshalb gibt es dafuer KEINEN eigenen Anzeigezustand.
        assertNull(inconclusive.toHintOrNull())
    }

    @Test
    fun `SUSPICIOUS ohne Schaetzung erzeugt keinen Hinweis`() {
        // Sollte nicht vorkommen (SUSPICIOUS impliziert eine Schaetzung), aber
        // die Abbildung darf daran nicht mit einer nichtssagenden Zahl scheitern.
        val result =
            RepCountPlausibility.Result(
                periodSeconds = 2.0,
                estimatedReps = null,
                periodicityStrength = 0.8,
                verdict = RepCountPlausibility.Verdict.SUSPICIOUS,
                countedReps = 8,
            )
        assertNull(result.toHintOrNull())
    }

    @Test
    fun `SUSPICIOUS mit Schaetzung erzeugt einen Hinweis`() {
        val result =
            RepCountPlausibility.Result(
                periodSeconds = 2.0,
                estimatedReps = 8,
                periodicityStrength = 0.8,
                verdict = RepCountPlausibility.Verdict.SUSPICIOUS,
                countedReps = 16,
            )
        val hint = requireNotNull(result.toHintOrNull())
        assertEquals(16, hint.countedReps)
        assertEquals(8, hint.estimatedReps)
    }

    private class NoOpCueOutput : com.dropsync.domain.timer.CueOutput {
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
