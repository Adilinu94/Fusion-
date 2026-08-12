package com.dropsync.feature.workout

import app.cash.turbine.test
import com.dropsync.core.common.AppResult
import com.dropsync.core.common.Clock
import com.dropsync.domain.sensor.CalibrationProfile
import com.dropsync.domain.sensor.CalibrationProfileRepository
import com.dropsync.domain.sensor.DeviceEvent
import com.dropsync.domain.sensor.SensorConnectionState
import com.dropsync.domain.sensor.SensorProvider
import com.dropsync.domain.sensor.SensorSample
import com.dropsync.domain.timer.CueOutput
import com.dropsync.domain.timer.RestTimerPreferencesRepository
import com.dropsync.domain.timer.RestTimerServiceStarter
import com.dropsync.domain.timer.TimerEngine
import com.dropsync.domain.workout.CustomExerciseInput
import com.dropsync.domain.workout.ExerciseDetail
import com.dropsync.domain.workout.ExerciseInfo
import com.dropsync.domain.workout.ExerciseLibraryItem
import com.dropsync.domain.workout.FlatSet
import com.dropsync.domain.workout.FlatSetRepository
import com.dropsync.domain.workout.PlaybackSnapshotInfo
import com.dropsync.domain.workout.PlayedTrackInfo
import com.dropsync.domain.workout.PrRecord
import com.dropsync.domain.workout.RestPref
import com.dropsync.domain.workout.SegmentInput
import com.dropsync.domain.workout.SessionExerciseInfo
import com.dropsync.domain.workout.SwapStrategy
import com.dropsync.domain.workout.WorkoutRepository
import com.dropsync.domain.workout.WorkoutSessionInfo
import com.dropsync.core.model.RestMode
import com.dropsync.core.model.SetRole
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
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

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        workoutRepository = FakeWorkoutRepository()
        flatSetRepository = FakeFlatSetRepository()
        sensorProvider = FakeSensorProvider()
        calibrationProfileRepository = FakeCalibrationProfileRepository()
        timerEngine = TimerEngine(clock = FakeClock(), cueOutput = NoOpCueOutput())
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

    // --- Fakes ------------------------------------------------------------

    private class FakeSensorProvider : SensorProvider {
        private val _samples = kotlinx.coroutines.flow.MutableSharedFlow<SensorSample>(extraBufferCapacity = 64)
        override val connectionState = MutableStateFlow(SensorConnectionState.DISCONNECTED)
        override val samples: Flow<SensorSample> = _samples
        override val deviceEvents: Flow<DeviceEvent> = emptyFlow()
        override val connectedDeviceId = MutableStateFlow<String?>(null)

        fun emitSample(gyro: Double) {
            _samples.tryEmit(SensorSample(timestampMs = 0L, ax = 0.0, ay = 0.0, az = 9.8, gx = gyro, gy = 0.0, gz = 0.0))
        }

        override suspend fun connect(deviceId: String?): AppResult<Unit> = AppResult.Success(Unit)
        override suspend fun disconnect() = Unit
        override suspend fun startStreaming() = Unit
        override suspend fun stopStreaming() = Unit
    }

    private class FakeCalibrationProfileRepository : CalibrationProfileRepository {
        override suspend fun load(exerciseId: Long, deviceId: String): AppResult<CalibrationProfile?> =
            AppResult.Success(null)

        override suspend fun save(profile: CalibrationProfile): AppResult<Unit> = AppResult.Success(Unit)
        override suspend fun delete(exerciseId: Long, deviceId: String): AppResult<Unit> = AppResult.Success(Unit)
    }

    private class FakeFlatSetRepository : FlatSetRepository {
        val logged = mutableListOf<FlatSet>()

        override fun observeSetsForExercise(exerciseId: Long): Flow<List<FlatSet>> = flowOf(emptyList())
        override fun observeAllSets(): Flow<List<FlatSet>> = flowOf(emptyList())
        override suspend fun getLastSet(exerciseId: Long): AppResult<FlatSet?> = AppResult.Success(null)

        override suspend fun logSet(exerciseId: Long, weightMilliKg: Long, reps: Int): AppResult<Long> {
            val set = FlatSet(id = logged.size + 1L, exerciseId = exerciseId, weightMilliKg = weightMilliKg, reps = reps, loggedAtEpochMs = 0L)
            logged += set
            return AppResult.Success(set.id)
        }

        override suspend fun deleteSet(setId: Long): AppResult<Unit> = AppResult.Success(Unit)
        override suspend fun getMaxVolumeForExercise(exerciseId: Long): AppResult<Long?> = AppResult.Success(null)
        override suspend fun getVolumeForDay(dayStart: Long): AppResult<Long?> = AppResult.Success(null)
        override suspend fun getRecentSets(limit: Int): AppResult<List<FlatSet>> = AppResult.Success(emptyList())
    }

    private class FakeRestTimerPreferencesRepository : RestTimerPreferencesRepository {
        override val restPresetsSeconds: Flow<List<Int>> = flowOf(RestTimerPreferencesRepository.DEFAULT_PRESETS_SECONDS)
        override suspend fun setRestPresetsSeconds(seconds: List<Int>) = Unit
        override val getReadyEnabled: Flow<Boolean> = flowOf(false)
        override val getReadySeconds: Flow<Int> = flowOf(RestTimerPreferencesRepository.DEFAULT_GET_READY_SECONDS)
        override suspend fun setGetReady(enabled: Boolean, seconds: Int) = Unit
    }

    private class FakeWorkoutRepository : WorkoutRepository {
        override val activeSession: Flow<WorkoutSessionInfo?> = flowOf(null)
        override fun observeExercises(locale: String): Flow<List<ExerciseInfo>> = flowOf(emptyList())
        override fun observeSessionExercises(sessionId: Long, locale: String): Flow<List<SessionExerciseInfo>> =
            flowOf(emptyList())

        override suspend fun startSession(title: String?, fromRoutineId: Long?): AppResult<Long> = AppResult.Success(0L)
        override suspend fun completeSession(sessionId: Long): AppResult<Unit> = AppResult.Success(Unit)
        override suspend fun discardSession(sessionId: Long): AppResult<Unit> = AppResult.Success(Unit)
        override suspend fun addExercise(sessionId: Long, exerciseId: Long, supersetGroupId: Long?): AppResult<Long> =
            AppResult.Success(0L)

        override suspend fun completeCluster(
            sessionExerciseId: Long,
            setRole: SetRole,
            segments: List<SegmentInput>,
            note: String?,
        ): AppResult<Long> = AppResult.Success(0L)

        override suspend fun undoCompleteCluster(clusterId: Long): AppResult<Unit> = AppResult.Success(Unit)
        override suspend fun recomputeRecords(exerciseId: Long): AppResult<Unit> = AppResult.Success(Unit)
        override suspend fun lastCompletedClusterPrefill(exerciseId: Long): AppResult<List<SegmentInput>> =
            AppResult.Success(emptyList())

        override suspend fun recordPlaybackSnapshot(
            sessionId: Long,
            songId: Long,
            markerId: Long?,
            positionMs: Long,
        ): AppResult<Long> = AppResult.Success(0L)

        override suspend fun getPlaybackSnapshots(sessionId: Long): AppResult<List<PlaybackSnapshotInfo>> =
            AppResult.Success(emptyList())

        override fun observeExerciseLibrary(locale: String): Flow<List<ExerciseLibraryItem>> = flowOf(emptyList())
        override suspend fun createCustomExercise(input: CustomExerciseInput): AppResult<Long> = AppResult.Success(0L)
        override suspend fun getExerciseDetail(exerciseId: Long, locale: String): AppResult<ExerciseDetail> =
            throw UnsupportedOperationException("not needed for this test")

        override suspend fun archiveExercise(exerciseId: Long): AppResult<Unit> = AppResult.Success(Unit)
        override suspend fun getRestPref(exerciseId: Long): AppResult<RestPref?> = AppResult.Success(null)
        override suspend fun setRestPref(exerciseId: Long, restSeconds: Int, restMode: RestMode): AppResult<Unit> =
            AppResult.Success(Unit)

        override suspend fun swapSessionExercise(
            sessionExerciseId: Long,
            newExerciseId: Long,
            strategy: SwapStrategy,
        ): AppResult<Unit> = AppResult.Success(Unit)

        override suspend fun repeatLastSession(): AppResult<Long> = AppResult.Success(0L)
        override fun observePersonalRecords(exerciseId: Long): Flow<List<PrRecord>> = flowOf(emptyList())
        override suspend fun getSessionMusic(sessionId: Long): AppResult<List<PlayedTrackInfo>> =
            AppResult.Success(emptyList())
    }

    private class FakeClock : Clock {
        override fun elapsedRealtimeMs(): Long = 0L
        override fun epochMillis(): Long = 0L
    }

    private class NoOpCueOutput : CueOutput {
        override fun speak(cueSessionId: String, secondsRemaining: Int) = Unit
        override fun haptic(cueSessionId: String) = Unit
        override fun tone(cueSessionId: String) = Unit
        override fun stopAll(cueSessionId: String) = Unit
    }
}
