package com.dropsync.feature.workout

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dropsync.core.common.AppResult
import com.dropsync.core.model.Equipment
import com.dropsync.core.model.ExerciseKind
import com.dropsync.core.model.MuscleGroup
import com.dropsync.domain.sensor.SensorConnectionState
import com.dropsync.domain.sensor.SensorProvider
import com.dropsync.domain.sensor.accelMagnitude
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
        restTimerPreferences: RestTimerPreferencesRepository,
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
            timerEngine.cancel(CancelReason.USER)
            timerEngine.reset()
            _selectedExercise.value = null
            _lastSet.value = null
            _maxVolumeKg.value = null
            _weightInput.value = ""
            _repsInput.value = ""
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
            _selectedExercise.value = exercise
            loadLastSet(exercise.id)
            loadMaxVolume(exercise.id)
            loadRestPref(exercise.id)
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
        }

        fun logSet() {
            val exercise = _selectedExercise.value ?: return
            val weightKg = _weightInput.value.toDoubleOrNull() ?: return
            val reps = _repsInput.value.toIntOrNull() ?: return
            val weightMilliKg = (weightKg * 1_000_000).toLong()

            viewModelScope.launch {
                when (flatSetRepository.logSet(exercise.id, weightMilliKg, reps)) {
                    is AppResult.Success -> {
                        loadLastSet(exercise.id)
                        loadMaxVolume(exercise.id)
                        loadRecentSets()
                        // Keep weight, reset reps for the next set.
                        _repsInput.value = ""
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

        init {
            loadRecentSets()
            // Same engine tick as TimerViewModel: evaluate() is idempotent,
            // the tick is never the completion source (design step 7.1).
            viewModelScope.launch {
                while (isActive) {
                    timerEngine.evaluate()
                    delay(TICK_MS)
                }
            }
            // Phase 4 step 5: collect live samples for the waveform; a peak
            // (accel magnitude spike) triggers the flash overlay.
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
                }
            }
        }

        private companion object {
            const val TICK_MS = 250L

            /** Samples shown in the live waveform (~4 s at 50 Hz). */
            const val WAVEFORM_WINDOW = 200

            /** Rising-edge delta in g that counts as a rep peak flash. */
            const val PEAK_DELTA_G = 0.4

            /** Minimum magnitude in g for a peak (ignores rest jitter). */
            const val PEAK_MIN_G = 1.3
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
