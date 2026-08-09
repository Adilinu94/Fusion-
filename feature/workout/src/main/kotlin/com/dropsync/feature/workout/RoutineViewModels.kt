package com.dropsync.feature.workout

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dropsync.core.common.AppResult
import com.dropsync.domain.workout.ExerciseInfo
import com.dropsync.domain.workout.RoutineDetail
import com.dropsync.domain.workout.RoutineEntry
import com.dropsync.domain.workout.RoutineInfo
import com.dropsync.domain.workout.WorkoutRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Locale
import javax.inject.Inject

/** Routinenliste (Schritt 9.7): anlegen, oeffnen, daraus starten. */
@HiltViewModel
class RoutinesViewModel
    @Inject
    constructor(
        private val workoutRepository: WorkoutRepository,
    ) : ViewModel() {
        val routines: StateFlow<List<RoutineInfo>> =
            workoutRepository.observeRoutines().stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5_000),
                emptyList(),
            )

        /** Startet eine Session aus der Routine (RoutineExpander in Data). */
        fun startFromRoutine(routineId: Long) {
            viewModelScope.launch {
                workoutRepository.startSession(title = null, fromRoutineId = routineId)
            }
        }
    }

/** Eintrag im Routinen-Editor, bevor die Routine gespeichert wird. */
data class RoutineDraftEntry(
    val exerciseId: Long,
    val displayName: String,
    val targetSets: Int,
    val restSeconds: Int?,
)

/**
 * Routinendetail bzw. Neuanlage (Schritt 9.7): routineId == 0 baut eine
 * neue Routine aus Uebungen zusammen; sonst Detailansicht mit Start.
 */
@HiltViewModel
class RoutineEditViewModel
    @Inject
    constructor(
        private val workoutRepository: WorkoutRepository,
        savedStateHandle: SavedStateHandle,
    ) : ViewModel() {
        private val locale: String = Locale.getDefault().language

        val routineId: Long = savedStateHandle["routineId"] ?: 0L

        private val _detail = MutableStateFlow<RoutineDetail?>(null)

        /** Nur im Detailmodus (routineId > 0) belegt. */
        val detail: StateFlow<RoutineDetail?> = _detail.asStateFlow()

        /** Uebungsauswahl fuer den Editor. */
        val exercises: StateFlow<List<ExerciseInfo>> =
            workoutRepository.observeExercises(locale).stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5_000),
                emptyList(),
            )

        private val _draftEntries = MutableStateFlow<List<RoutineDraftEntry>>(emptyList())

        /** Reihenfolge = Listenreihenfolge; orderIndex wird beim Speichern gesetzt. */
        val draftEntries: StateFlow<List<RoutineDraftEntry>> = _draftEntries.asStateFlow()

        private val _saved = MutableStateFlow(false)

        /** Signalisiert der UI die erfolgreiche Anlage (zurueck navigieren). */
        val saved: StateFlow<Boolean> = _saved.asStateFlow()

        init {
            if (routineId > 0) {
                viewModelScope.launch {
                    val result = workoutRepository.getRoutineDetail(routineId, locale)
                    if (result is AppResult.Success) {
                        _detail.value = result.value
                    }
                }
            }
        }

        fun addDraftEntry(
            exercise: ExerciseInfo,
            targetSets: Int,
            restSeconds: Int?,
        ) {
            if (targetSets <= 0) return
            _draftEntries.value = _draftEntries.value +
                RoutineDraftEntry(exercise.id, exercise.displayName, targetSets, restSeconds)
        }

        fun removeDraftEntry(index: Int) {
            _draftEntries.value = _draftEntries.value.filterIndexed { i, _ -> i != index }
        }

        fun saveRoutine(name: String) {
            val entries = _draftEntries.value
            if (name.isBlank() || entries.isEmpty()) return
            viewModelScope.launch {
                val result =
                    workoutRepository.createRoutine(
                        name = name.trim(),
                        entries =
                            entries.mapIndexed { index, entry ->
                                RoutineEntry(
                                    exerciseId = entry.exerciseId,
                                    orderIndex = index,
                                    supersetGroupId = null,
                                    targetSets = entry.targetSets,
                                    targetRepsMin = null,
                                    targetRepsMax = null,
                                    restSeconds = entry.restSeconds,
                                )
                            },
                    )
                if (result is AppResult.Success) {
                    _saved.value = true
                }
            }
        }

        /** Startet eine Session aus dieser Routine (Detailmodus). */
        fun startSessionFromRoutine() {
            if (routineId <= 0) return
            viewModelScope.launch {
                workoutRepository.startSession(title = null, fromRoutineId = routineId)
            }
        }
    }
