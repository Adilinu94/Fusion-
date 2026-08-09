package com.dropsync.feature.workout

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dropsync.core.model.Equipment
import com.dropsync.core.model.ExerciseKind
import com.dropsync.domain.workout.CustomExerciseInput
import com.dropsync.domain.workout.ExerciseLibraryItem
import com.dropsync.domain.workout.MuscleContribution
import com.dropsync.domain.workout.WorkoutRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Locale
import javax.inject.Inject

/**
 * Uebungsbibliothek (Schritt 9.1/9.2): Liste mit Equipment, Suche und
 * Neuanlage eigener Uebungen mit Muskel-Mapping.
 */
@HiltViewModel
class ExerciseLibraryViewModel
    @Inject
    constructor(
        private val workoutRepository: WorkoutRepository,
    ) : ViewModel() {
        private val locale: String = Locale.getDefault().language

        private val _query = MutableStateFlow("")

        /** Freitextsuche ueber den lokalisierten Anzeigenamen. */
        val query: StateFlow<String> = _query.asStateFlow()

        val items: StateFlow<List<ExerciseLibraryItem>> =
            workoutRepository
                .observeExerciseLibrary(locale)
                .combine(_query) { items, query ->
                    val trimmed = query.trim()
                    if (trimmed.isEmpty()) {
                        items
                    } else {
                        items.filter { it.displayName.contains(trimmed, ignoreCase = true) }
                    }
                }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

        fun setQuery(value: String) {
            _query.value = value
        }

        /** Legt eine eigene Uebung an; Slug wird aus dem Namen erzeugt (9.2). */
        fun createExercise(
            nameDe: String,
            nameEn: String,
            kind: ExerciseKind,
            equipment: Equipment,
            muscles: List<MuscleContribution>,
        ) {
            if (nameDe.isBlank() || nameEn.isBlank()) return
            viewModelScope.launch {
                workoutRepository.createCustomExercise(
                    CustomExerciseInput(
                        displayNames = mapOf("de" to nameDe.trim(), "en" to nameEn.trim()),
                        kind = kind,
                        equipment = equipment,
                        muscles = muscles.filter { it.percent in 1..100 },
                    ),
                )
            }
        }
    }
