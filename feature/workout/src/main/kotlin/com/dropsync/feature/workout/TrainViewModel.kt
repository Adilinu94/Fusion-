package com.dropsync.feature.workout

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dropsync.core.common.AppResult
import com.dropsync.core.model.Equipment
import com.dropsync.core.model.ExerciseKind
import com.dropsync.core.model.MuscleGroup
import com.dropsync.domain.workout.CustomExerciseInput
import com.dropsync.domain.workout.ExerciseInfo
import com.dropsync.domain.workout.FlatSet
import com.dropsync.domain.workout.FlatSetRepository
import com.dropsync.domain.workout.MuscleContribution
import com.dropsync.domain.workout.WorkoutRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for the Train tab (FlowRep Phase 2): flat set log.
 */
@HiltViewModel
class TrainViewModel
    @Inject
    constructor(
        private val workoutRepository: WorkoutRepository,
        private val flatSetRepository: FlatSetRepository,
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

        fun selectExercise(exercise: ExerciseInfo) {
            _selectedExercise.value = exercise
            loadLastSet(exercise.id)
            loadMaxVolume(exercise.id)
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

        init {
            loadRecentSets()
        }
    }
