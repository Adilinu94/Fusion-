package com.dropsync.feature.workout

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dropsync.core.common.AppResult
import com.dropsync.domain.workout.ExerciseDetail
import com.dropsync.domain.workout.ExerciseProgressPoint
import com.dropsync.domain.workout.PrRecord
import com.dropsync.domain.workout.ProgressClassification
import com.dropsync.domain.workout.ProgressionClassifier
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

/**
 * Uebungsdetail (Abschnitt 3): PRs in drei getrennten Kategorien,
 * 1RM-Trend (nie PR), Volumen- und Gewichtsverlauf sowie Klassifizierung
 * mit Plateau-Alarm und Vorschlag.
 */
@HiltViewModel
class ExerciseDetailViewModel
    @Inject
    constructor(
        private val workoutRepository: WorkoutRepository,
        savedStateHandle: SavedStateHandle,
    ) : ViewModel() {
        private val locale: String = Locale.getDefault().language

        val exerciseId: Long = savedStateHandle["exerciseId"] ?: 0L

        private val _detail = MutableStateFlow<ExerciseDetail?>(null)
        val detail: StateFlow<ExerciseDetail?> = _detail.asStateFlow()

        /** PRs: HIGHEST_LOAD, HIGHEST_SESSION_VOLUME, MOST_REPS_AT_LOAD (5.4). */
        val personalRecords: StateFlow<List<PrRecord>> =
            workoutRepository.observePersonalRecords(exerciseId).stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5_000),
                emptyList(),
            )

        private val _points = MutableStateFlow<List<ExerciseProgressPoint>>(emptyList())

        /** Je Session ein Punkt, aufsteigend nach Startzeit. */
        val points: StateFlow<List<ExerciseProgressPoint>> = _points.asStateFlow()

        private val _classification = MutableStateFlow<ProgressClassification?>(null)

        /** Progressiv/stagnierend/ruecklaeufig + Plateau + Vorschlag. */
        val classification: StateFlow<ProgressClassification?> = _classification.asStateFlow()

        init {
            viewModelScope.launch {
                val detailResult = workoutRepository.getExerciseDetail(exerciseId, locale)
                if (detailResult is AppResult.Success) {
                    _detail.value = detailResult.value
                }
                val progressResult = workoutRepository.getExerciseProgress(exerciseId)
                if (progressResult is AppResult.Success) {
                    _points.value = progressResult.value
                    _classification.value = ProgressionClassifier.classify(progressResult.value)
                }
            }
        }
    }

/** Listeneintrag der Fortschrittsuebersicht. */
data class ProgressOverviewItem(
    val exerciseId: Long,
    val displayName: String,
    val classification: ProgressClassification?,
)

/**
 * Fortschrittsuebersicht (Abschnitt 3): alle Uebungen mit Trainings-
 * historie samt aktueller Klassifizierung als Einstieg ins Detail.
 */
@HiltViewModel
class ProgressViewModel
    @Inject
    constructor(
        private val workoutRepository: WorkoutRepository,
    ) : ViewModel() {
        private val locale: String = Locale.getDefault().language

        private val _items = MutableStateFlow<List<ProgressOverviewItem>>(emptyList())
        val items: StateFlow<List<ProgressOverviewItem>> = _items.asStateFlow()

        init {
            viewModelScope.launch {
                workoutRepository.observeExerciseLibrary(locale).collect { library ->
                    _items.value =
                        library.mapNotNull { item ->
                            val result = workoutRepository.getExerciseProgress(item.id)
                            val points =
                                (result as? AppResult.Success)?.value ?: return@mapNotNull null
                            if (points.isEmpty()) {
                                // Ohne Historie kein Fortschritt zu bewerten.
                                null
                            } else {
                                ProgressOverviewItem(
                                    exerciseId = item.id,
                                    displayName = item.displayName,
                                    classification = ProgressionClassifier.classify(points),
                                )
                            }
                        }
                }
            }
        }
    }
