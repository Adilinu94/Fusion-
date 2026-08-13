package com.dropsync.feature.workout

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dropsync.core.common.AppResult
import com.dropsync.core.model.Equipment
import com.dropsync.core.model.ExerciseKind
import com.dropsync.core.model.MuscleGroup
import com.dropsync.domain.sensor.CalibrationProfile
import com.dropsync.domain.sensor.CalibrationProfileRepository
import com.dropsync.domain.sensor.ExerciseEngineConfig
import com.dropsync.domain.sensor.ExerciseEnginePipeline
import com.dropsync.domain.sensor.SensorConnectionState
import com.dropsync.domain.sensor.SensorProvider
import com.dropsync.domain.sensor.SensorSample
import com.dropsync.domain.sensor.accelMagnitude
import com.dropsync.domain.sensor.calibration.CalibrationRefiner
import com.dropsync.domain.timer.CancelReason
import com.dropsync.domain.timer.RestTimerPreferencesRepository
import com.dropsync.domain.timer.RestTimerServiceStarter
import com.dropsync.domain.timer.TimerEngine
import com.dropsync.domain.timer.TimerMode
import com.dropsync.domain.timer.TimerState
import com.dropsync.domain.timer.TimerStatus
import com.dropsync.domain.workout.CustomExerciseInput
import com.dropsync.domain.workout.ExerciseInfo
import com.dropsync.domain.workout.FlatSet
import com.dropsync.domain.workout.FlatSetRepository
import com.dropsync.domain.workout.MuscleContribution
import com.dropsync.domain.workout.RestPref
import com.dropsync.domain.workout.WorkoutRepository
import com.dropsync.feature.workout.shadow.ShadowDiffEvent
import com.dropsync.feature.workout.shadow.ShadowSessionRecorder
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for the Train tab (FlowRep Phase 2): flat set log.
 * Phase 3: binds the shared TimerEngine — logging a set starts the rest
 * timer (foreground TimerService), finishing the exercise cancels it.
 */
@HiltViewModel
class TrainViewModel
    @Inject
    constructor(
        private val workoutRepository: WorkoutRepository,
        private val flatSetRepository: FlatSetRepository,
        private val timerEngine: TimerEngine,
        private val restTimerServiceStarter: RestTimerServiceStarter,
        private val sensorProvider: SensorProvider,
        private val calibrationProfileRepository: CalibrationProfileRepository,
        restTimerPreferences: RestTimerPreferencesRepository,
        private val shadowSessionRecorder: ShadowSessionRecorder,
    ) : ViewModel() {
        val exercises: StateFlow<List<ExerciseInfo>> =
            workoutRepository
                .observeExercises("de")
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

        private val _selectedExercise = MutableStateFlow<ExerciseInfo?>(null)
        val selectedExercise: StateFlow<ExerciseInfo?> = _selectedExercise.asStateFlow()

        // Last set of the selected exercise, used as weight placeholder.
        private val _lastSet = MutableStateFlow<FlatSet?>(null)
        val lastSet: StateFlow<FlatSet?> = _lastSet.asStateFlow()

        // Personal record: max volume (weight x reps) of the selected exercise.
        private val _maxVolumeKg = MutableStateFlow<Double?>(null)
        val maxVolumeKg: StateFlow<Double?> = _maxVolumeKg.asStateFlow()

        // Last 5 sets across all exercises (mini history).
        private val _recentSets = MutableStateFlow<List<FlatSet>>(emptyList())
        val recentSets: StateFlow<List<FlatSet>> = _recentSets.asStateFlow()

        private val _weightInput = MutableStateFlow("")
        val weightInput: StateFlow<String> = _weightInput.asStateFlow()

        private val _repsInput = MutableStateFlow("")
        val repsInput: StateFlow<String> = _repsInput.asStateFlow()

        /**
         * True once the user has typed into the reps field for the current
         * set (Shadow-Diff-Harness-Plan D3: Ground-Truth-Regel). Set only in
         * [setReps] (the UI's onValueChange path), reset to false whenever
         * the pipeline pre-fills the field ([stopCountedSet], [logSet]'s
         * cleanup, [finishExercise]) - never derived by comparing values,
         * since retyping the same number is still an active confirmation.
         */
        private val _repsInputEdited = MutableStateFlow(false)
        val repsInputEdited: StateFlow<Boolean> = _repsInputEdited.asStateFlow()

        val canLog: Boolean
            get() = _weightInput.value.toDoubleOrNull() != null && _repsInput.value.toIntOrNull() != null

        // --- Phase 3: rest timer binding -----------------------------------

        /** Shared rest-timer state (drives the train pill countdown). */
        val timerState: StateFlow<TimerState> = timerEngine.state

        // Get-ready lead time (prepMs) mirrored from the timer preferences.
        private val getReady: StateFlow<Pair<Boolean, Int>> =
            combine(
                restTimerPreferences.getReadyEnabled,
                restTimerPreferences.getReadySeconds,
            ) { enabled, seconds -> enabled to seconds }.stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5_000),
                false to RestTimerPreferencesRepository.DEFAULT_GET_READY_SECONDS,
            )

        // Rest seconds of the selected exercise (default 90 s, Phase 2 step 3).
        private val _restSeconds = MutableStateFlow(RestPref.DEFAULT_REST_SECONDS)
        val restSeconds: StateFlow<Int> = _restSeconds.asStateFlow()

        // Drop auto-switch per rest (design doc Phase 3 step 4); wired to
        // DropSync in a later phase, kept as view state for now.
        private val _dropAutoEnabled = MutableStateFlow(false)
        val dropAutoEnabled: StateFlow<Boolean> = _dropAutoEnabled.asStateFlow()

        fun setDropAutoEnabled(enabled: Boolean) {
            _dropAutoEnabled.value = enabled
        }

        /** Skip the running rest timer (train pill action). */
        fun skipRest() {
            timerEngine.cancel(CancelReason.USER)
            timerEngine.reset()
        }

        /** Rule (design step 5): finish exercise cancels the timer at once. */
        fun finishExercise() {
            logShadowDiff("finishExercise")
            timerEngine.cancel(CancelReason.USER)
            timerEngine.reset()
            _selectedExercise.value = null
            _lastSet.value = null
            _maxVolumeKg.value = null
            _weightInput.value = ""
            _repsInput.value = ""
            _repsInputEdited.value = false
            shadowEngine = null
            shadowRepCount = 0
            liveRepCount = 0
        }

        private fun startRestTimer() {
            // A finished rest must be reset before a new start (engine rule).
            if (timerEngine.state.value.status != TimerStatus.IDLE) {
                timerEngine.reset()
            }
            val (enabled, seconds) = getReady.value
            val prepMs = if (enabled) seconds * 1_000L else 0L
            val result =
                timerEngine.start(
                    TimerMode.REST,
                    _restSeconds.value * 1_000L,
                    prepMs,
                )
            if (result is AppResult.Success) {
                // Keep the timer alive in the pocket (foreground service).
                restTimerServiceStarter.startForegroundTimerService()
            }
        }

        fun selectExercise(exercise: ExerciseInfo) {
            logShadowDiff("exerciseSwitch")
            _selectedExercise.value = exercise
            loadLastSet(exercise.id)
            loadMaxVolume(exercise.id)
            loadRestPref(exercise.id)
            resetShadowEngine()
            loadActiveProfile()
        }

        private fun loadRestPref(exerciseId: Long) {
            viewModelScope.launch {
                _restSeconds.value =
                    when (val result = workoutRepository.getRestPref(exerciseId)) {
                        is AppResult.Success -> result.value?.restSeconds ?: RestPref.DEFAULT_REST_SECONDS
                        is AppResult.Failure -> RestPref.DEFAULT_REST_SECONDS
                    }
            }
        }

        private fun loadLastSet(exerciseId: Long) {
            viewModelScope.launch {
                when (val result = flatSetRepository.getLastSet(exerciseId)) {
                    is AppResult.Success -> _lastSet.value = result.value
                    is AppResult.Failure -> _lastSet.value = null
                }
            }
        }

        private fun loadMaxVolume(exerciseId: Long) {
            viewModelScope.launch {
                when (val result = flatSetRepository.getMaxVolumeForExercise(exerciseId)) {
                    is AppResult.Success -> _maxVolumeKg.value = result.value?.let { it / 1_000_000.0 }
                    is AppResult.Failure -> _maxVolumeKg.value = null
                }
            }
        }

        fun setWeight(value: String) {
            _weightInput.value = value
        }

        fun adjustWeight(deltaKg: Double) {
            val current = _weightInput.value.toDoubleOrNull() ?: 0.0
            _weightInput.value = (current + deltaKg).toString()
        }

        fun setReps(value: String) {
            _repsInput.value = value
            _repsInputEdited.value = true
        }

        fun logSet() {
            val exercise = _selectedExercise.value ?: return
            val weightKg = _weightInput.value.toDoubleOrNull() ?: return
            val reps = _repsInput.value.toIntOrNull() ?: return
            val weightMilliKg = (weightKg * 1_000_000).toLong()
            // Captured before any reset below (D3/ADR-0014): reflects what
            // the user actually confirmed for *this* set, not a later state.
            val repsEdited = _repsInputEdited.value

            viewModelScope.launch {
                when (flatSetRepository.logSet(exercise.id, weightMilliKg, reps)) {
                    is AppResult.Success -> {
                        loadLastSet(exercise.id)
                        loadMaxVolume(exercise.id)
                        loadRecentSets()
                        // Live (confirmed) count for the shadow diff (11b).
                        liveRepCount += reps
                        val counted = _liveCountedReps.value
                        // Shadow-Diff-Harness-Plan Schritt 1 (D2/D3/D4):
                        // recorded while confirmedReps/repsEdited/shadowRepCount
                        // still reflect this set, before the learn loop/clear.
                        shadowSessionRecorder.recordSet(
                            ShadowDiffEvent(
                                exerciseId = exercise.id,
                                weightMilliKg = weightMilliKg,
                                confirmedReps = reps,
                                confirmedRepsEdited = repsEdited,
                                liveCountedReps = counted,
                                shadowReps = shadowRepCount,
                            ),
                        )
                        // Learn loop: a logged count that differs from the
                        // live-counted one re-analyses the buffered set and
                        // improves the profile silently (design doc Phase 4).
                        if (setSamples.isNotEmpty() && counted != reps) {
                            applyCorrection(reps)
                        }
                        // Set consumed: clear the live-count state.
                        setSamples.clear()
                        _liveCountedReps.value = 0
                        // Keep weight, reset reps for the next set.
                        _repsInput.value = ""
                        _repsInputEdited.value = false
                        // Rule (design step 5): set done -> rest timer starts.
                        startRestTimer()
                    }

                    is AppResult.Failure -> {
                        // Error display is handled in a later phase.
                    }
                }
            }
        }

        /** Creates a custom exercise (Phase 2 step 4) and selects it on success. */
        fun createExercise(name: String) {
            val trimmed = name.trim()
            if (trimmed.isEmpty()) return
            viewModelScope.launch {
                val result =
                    workoutRepository.createCustomExercise(
                        CustomExerciseInput(
                            displayNames = mapOf("de" to trimmed, "en" to trimmed),
                            kind = ExerciseKind.STRENGTH,
                            equipment = Equipment.OTHER,
                            muscles = listOf(MuscleContribution(MuscleGroup.OTHER, 100)),
                        ),
                    )
                if (result is AppResult.Success) {
                    val created =
                        exercises.value.firstOrNull { it.id == result.value }
                            ?: ExerciseInfo(result.value, trimmed, trimmed)
                    selectExercise(created)
                }
            }
        }

        private fun loadRecentSets() {
            viewModelScope.launch {
                when (val result = flatSetRepository.getRecentSets(5)) {
                    is AppResult.Success -> _recentSets.value = result.value
                    is AppResult.Failure -> _recentSets.value = emptyList()
                }
            }
        }

        // --- Phase 4: live sensor waveform --------------------------------

        /** Connection state of the FlowRep chip (drives waveform visibility). */
        val sensorConnection: StateFlow<SensorConnectionState> = sensorProvider.connectionState

        /** BLE address of the connected chip (drives the calibration entry). */
        val connectedDeviceId: StateFlow<String?> = sensorProvider.connectedDeviceId

        /** One-shot connection error text (German, via BleErrorMapper). */
        private val _sensorError = MutableStateFlow<String?>(null)
        val sensorError: StateFlow<String?> = _sensorError.asStateFlow()

        /**
         * Connects to a FlowRep chip by advertise-name scan (deviceId null).
         * The firmware auto-starts streaming after connect (Phase 4 quirk).
         */
        fun connectSensor() {
            viewModelScope.launch {
                _sensorError.value = null
                when (val result = sensorProvider.connect(null)) {
                    is AppResult.Success -> Unit
                    is AppResult.Failure -> _sensorError.value = sensorErrorText(result.error)
                }
            }
        }

        /** Disconnects the chip; the provider falls back to the fake. */
        fun disconnectSensor() {
            logShadowDiff("disconnect")
            viewModelScope.launch { sensorProvider.disconnect() }
        }

        /**
         * Rolling window of the last [WAVEFORM_WINDOW] acceleration magnitudes
         * (in g), normalized for the waveform. Empty while no chip streams.
         */
        private val _waveform = MutableStateFlow<List<Float>>(emptyList())
        val waveform: StateFlow<List<Float>> = _waveform.asStateFlow()

        /** One-shot peak flash: timestamp of the last detected acceleration peak. */
        private val _lastPeakMs = MutableStateFlow(0L)
        val lastPeakMs: StateFlow<Long> = _lastPeakMs.asStateFlow()

        private var waveformWindow = ArrayDeque<Float>()

        // --- Phase 4 step 6: shadow rep pipeline (design doc section 11b) ---
        //
        // The new ExerciseEnginePipeline runs ALONGSIDE the manual +/- path
        // once a calibration profile exists, but NEVER counts live: its
        // repCount is only compared against the confirmed (logged) count for
        // the shadow DoD. No UI element reads the shadow count.

        /** Shadow pipeline instance; recreated on exercise switch. */
        private var shadowEngine: ExerciseEnginePipeline? = null

        /** Reps the shadow pipeline confirmed this session (never shown). */
        private var shadowRepCount = 0

        /** Confirmed reps of the selected exercise in this session (live). */
        private var liveRepCount = 0

        // --- Phase 4 live counting (start -> countdown -> count -> stop) ----
        //
        // The LIVE pipeline uses the stored calibration profile (rotation
        // axis, gyro bias, template). It only runs during an active set. At
        // set end the counted reps are offered for the log; a user correction
        // re-analyses the buffered set and improves the profile (learn loop).

        /** Live-count set state (drives the start/stop UI + countdown). */
        enum class SetPhase { IDLE, COUNTDOWN, COUNTING }

        private val _setPhase = MutableStateFlow(SetPhase.IDLE)
        val setPhase: StateFlow<SetPhase> = _setPhase.asStateFlow()

        /** Countdown seconds left before counting starts (0 while counting). */
        private val _countdownSeconds = MutableStateFlow(0)
        val countdownSeconds: StateFlow<Int> = _countdownSeconds.asStateFlow()

        /** Reps counted live in the active set (0 unless COUNTING/finished). */
        private val _liveCountedReps = MutableStateFlow(0)
        val liveCountedReps: StateFlow<Int> = _liveCountedReps.asStateFlow()

        /** True once a calibration profile exists for the selected exercise. */
        private val _hasCalibration = MutableStateFlow(false)
        val hasCalibration: StateFlow<Boolean> = _hasCalibration.asStateFlow()

        /** Live pipeline for the active set (null until a set starts). */
        private var liveEngine: ExerciseEnginePipeline? = null

        /** Active calibration profile for the selected exercise+device. */
        private var activeProfile: CalibrationProfile? = null

        /** Raw samples of the active set (buffered for the learn loop). */
        private val setSamples = mutableListOf<SensorSample>()

        private var countdownJob: kotlinx.coroutines.Job? = null

        init {
            loadRecentSets()
            // Same engine tick as TimerViewModel: evaluate() is idempotent,
            // the tick is never the completion source (design step 7.1).
            // The ticker is a separate flow so tests can drive it from
            // TestScope.backgroundScope (auto-cancelled by runTest) instead of
            // relying on an internal isActive flag in the viewModelScope.
            viewModelScope.launch {
                tickerFlow(TICK_MS).collect { timerEngine.evaluate() }
            }
            // Phase 4 step 5: collect live samples for the waveform; a peak
            // (accel magnitude spike) triggers the flash overlay. The same
            // stream also feeds the shadow pipeline (step 6): it observes and
            // counts silently, never affecting the logged set.
            viewModelScope.launch {
                var prevMag = 0.0
                sensorProvider.samples.collect { sample ->
                    val mag = sample.accelMagnitude.toFloat()
                    waveformWindow.addLast(mag)
                    if (waveformWindow.size > WAVEFORM_WINDOW) waveformWindow.removeFirst()
                    _waveform.value = waveformWindow.toList()
                    // Simple peak heuristic for the flash: sharp rising edge.
                    if (mag - prevMag > PEAK_DELTA_G && mag > PEAK_MIN_G) {
                        _lastPeakMs.value = sample.timestampMs
                    }
                    prevMag = mag.toDouble()
                    // Live counting: only during an active set (after the
                    // countdown); raw samples are buffered for the learn loop.
                    if (_setPhase.value == SetPhase.COUNTING) {
                        setSamples.add(sample)
                        liveEngine?.let { engine ->
                            engine.processSample(sample.timestampMs, sample.gx, sample.gy, sample.gz)
                            _liveCountedReps.value = engine.repCount.value
                        }
                    }
                    // Shadow DoD 11b: feed the new pipeline; its repCount is
                    // tracked only for the diff against the live count.
                    shadowEngine?.let { engine ->
                        engine.processSample(sample.timestampMs, sample.gx, sample.gy, sample.gz)
                        if (engine.repCount.value != shadowRepCount) {
                            shadowRepCount = engine.repCount.value
                            Log.d(SHADOW_TAG, "shadow rep=$shadowRepCount live=$liveRepCount")
                        }
                    }
                }
            }
        }

        // --- Live set control ------------------------------------------------

        /**
         * Starts a live-counted set: needs a calibration profile and a
         * streaming chip. A short countdown lets the user get into the start
         * position before the pipeline begins counting.
         */
        fun startCountedSet() {
            if (_setPhase.value != SetPhase.IDLE) return
            val profile = activeProfile ?: return
            if (sensorConnection.value != SensorConnectionState.STREAMING) return
            _setPhase.value = SetPhase.COUNTDOWN
            _countdownSeconds.value = COUNTDOWN_SECONDS
            _liveCountedReps.value = 0
            setSamples.clear()
            liveEngine =
                ExerciseEnginePipeline(
                    ExerciseEngineConfig(
                        rotationAxis = profile.rotationAxis,
                        gyroBias = profile.gyroBias,
                        expectedProminence = profile.expectedProminence,
                        expectedDurationSamples = profile.expectedDurationSamples,
                        hasValidCalibration = true,
                    ),
                ).also { engine ->
                    engine.setTemplate(profile.repTemplate)
                    engine.updateLevels(profile.signalPeakLevel, profile.noisePeakLevel)
                }
            countdownJob?.cancel()
            countdownJob =
                viewModelScope.launch {
                    var remaining = COUNTDOWN_SECONDS
                    while (remaining > 0 && isActive) {
                        _countdownSeconds.value = remaining
                        delay(1_000)
                        remaining--
                    }
                    _countdownSeconds.value = 0
                    _setPhase.value = SetPhase.COUNTING
                }
        }

        /**
         * Ends the active set and copies the counted reps into the reps input
         * (overwrites only if something was counted). The user can still
         * correct the number before logging (learn loop via [applyCorrection]).
         */
        fun stopCountedSet() {
            countdownJob?.cancel()
            val counted = _liveCountedReps.value
            _setPhase.value = SetPhase.IDLE
            _countdownSeconds.value = 0
            if (counted > 0) {
                _repsInput.value = counted.toString()
                _repsInputEdited.value = false
            }
        }

        /**
         * Learn loop: the user corrected the counted reps to [correctedReps].
         * The buffered set is re-analysed so the pipeline parameters would
         * reproduce the true count, and the profile is improved silently.
         */
        fun applyCorrection(correctedReps: Int) {
            val exercise = _selectedExercise.value ?: return
            val deviceId = connectedDeviceId.value ?: return
            val profile = activeProfile ?: return
            if (setSamples.isEmpty() || correctedReps < 0) return
            viewModelScope.launch {
                val improved =
                    CalibrationRefiner.refine(
                        samples = setSamples,
                        correctedReps = correctedReps,
                        profile = profile,
                    ) ?: return@launch
                activeProfile = improved
                calibrationProfileRepository.save(improved)
                Log.d(SHADOW_TAG, "learn: corrected=$correctedReps profile updated")
                // Pre-fill the log with the corrected count.
                _repsInput.value = correctedReps.toString()
            }
        }

        /** Loads the calibration profile for the selected exercise (if any). */
        private fun loadActiveProfile() {
            val exercise =
                _selectedExercise.value ?: run {
                    activeProfile = null
                    _hasCalibration.value = false
                    return
                }
            val deviceId =
                connectedDeviceId.value ?: run {
                    activeProfile = null
                    _hasCalibration.value = false
                    return
                }
            viewModelScope.launch {
                val result = calibrationProfileRepository.load(exercise.id, deviceId)
                activeProfile = (result as? AppResult.Success)?.value
                _hasCalibration.value = activeProfile != null
            }
        }

        /**
         * (Re)creates the shadow pipeline for the selected exercise (step 6).
         * The engine is created with the same rotation axis/bias and level
         * parameters as the live engine once the profile is loaded - the
         * shadow diff is only meaningful when both engines project the gyro
         * signal the same way (Umbauplan Punkt 1 + 2).
         */
        private fun resetShadowEngine() {
            shadowRepCount = 0
            liveRepCount = 0
            val exercise =
                _selectedExercise.value ?: run {
                    shadowEngine = null
                    return
                }
            val deviceId =
                connectedDeviceId.value ?: run {
                    shadowEngine = null
                    return
                }
            shadowEngine = null
            viewModelScope.launch {
                val result = calibrationProfileRepository.load(exercise.id, deviceId)
                val profile = (result as? AppResult.Success)?.value
                shadowEngine =
                    ExerciseEnginePipeline(
                        ExerciseEngineConfig(
                            rotationAxis = profile?.rotationAxis ?: NEUTRAL_AXIS,
                            gyroBias = profile?.gyroBias ?: NEUTRAL_BIAS,
                            expectedProminence = profile?.expectedProminence ?: 50.0,
                            expectedDurationSamples = profile?.expectedDurationSamples ?: 50.0,
                            hasValidCalibration = profile != null,
                        ),
                    )
                profile?.let {
                    shadowEngine?.setTemplate(it.repTemplate)
                    shadowEngine?.updateLevels(it.signalPeakLevel, it.noisePeakLevel)
                    Log.d(
                        SHADOW_TAG,
                        "shadow engine configured: axis=${it.rotationAxis}, " +
                            "template=${it.repTemplate.size} samples, " +
                            "spk=${it.signalPeakLevel}, npk=${it.noisePeakLevel}",
                    )
                }
            }
        }

        /** Logs the shadow-vs-live diff at session end (shadow DoD metric). */
        private fun logShadowDiff(reason: String) {
            if (shadowEngine == null) return
            Log.d(SHADOW_TAG, "diff($reason): shadow=$shadowRepCount live=$liveRepCount")
        }

        private companion object {
            const val TICK_MS = 250L

            /** Samples shown in the live waveform (~4 s at 50 Hz). */
            const val WAVEFORM_WINDOW = 200

            /** Rising-edge delta in g that counts as a rep peak flash. */
            const val PEAK_DELTA_G = 0.4

            /** Minimum magnitude in g for a peak (ignores rest jitter). */
            const val PEAK_MIN_G = 1.3

            /** Logcat tag for the shadow-vs-live diff (DoD 11b). */
            const val SHADOW_TAG = "FlowRepShadow"

            /** Neutral projection axis (identity magnitude) without calibration. */
            val NEUTRAL_AXIS = listOf(0.0, 0.0, 1.0)

            /** Zero gyro bias without calibration. */
            val NEUTRAL_BIAS = listOf(0.0, 0.0, 0.0)

            /** Get-ready countdown before a live-counted set starts. */
            const val COUNTDOWN_SECONDS = 3
        }
    }

/**
 * Infinite ticker emitting every [periodMs]. Tests collect it from
 * `TestScope.backgroundScope`, which `runTest` cancels automatically at the end
 * of the test, so the scheduler can go idle without a production-code flag.
 */
private fun tickerFlow(periodMs: Long): kotlinx.coroutines.flow.Flow<Unit> =
    kotlinx.coroutines.flow.flow {
        while (true) {
            emit(Unit)
            kotlinx.coroutines.delay(periodMs)
        }
    }

/** German user text for a sensor connection failure (BleErrorMapper text). */
private fun sensorErrorText(error: com.dropsync.core.common.AppError): String =
    when (error) {
        is com.dropsync.core.common.AppError.PermissionDenied -> {
            "Bluetooth-Berechtigung fehlt. Bitte in den Einstellungen erlauben."
        }

        is com.dropsync.core.common.AppError.Unknown -> {
            error.debugMessage ?: "Verbindung fehlgeschlagen."
        }

        else -> {
            "Verbindung fehlgeschlagen."
        }
    }
