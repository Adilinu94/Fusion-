package com.dropsync.feature.workout

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dropsync.core.model.Equipment
import com.dropsync.core.model.ExerciseKind
import com.dropsync.domain.workout.CustomExerciseInput
import com.dropsync.domain.workout.ExerciseLibraryItem
import com.dropsync.domain.workout.ExerciseTarget
import com.dropsync.domain.workout.MuscleContribution
import com.dropsync.domain.workout.TargetRepository
import com.dropsync.domain.workout.WorkoutMath
import com.dropsync.domain.workout.WorkoutRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
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
        private val targetRepository: TargetRepository,
    ) : ViewModel() {
        private val locale: String = Locale.getDefault().language

        private val _query = MutableStateFlow("")

        /** Freitextsuche ueber den lokalisierten Anzeigenamen. */
        val query: StateFlow<String> = _query.asStateFlow()

        private val _loaded = MutableStateFlow(false)

        /**
         * Befund 7.1.4: Ladezustand der Uebungsliste — die Flows starten mit
         * leerer Liste, sonst saehe der Erstaufruf wie "keine Uebungen" aus.
         * Kippt nach der ersten echten Emission (auch bei leerem Bestand).
         */
        val isLoading: StateFlow<Boolean> =
            _loaded
                .map { loaded -> !loaded }
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

        val items: StateFlow<List<ExerciseLibraryItem>> =
            workoutRepository
                .observeExerciseLibrary(locale)
                .onEach { _loaded.value = true }
                .combine(_query) { items, query ->
                    val trimmed = query.trim()
                    if (trimmed.isEmpty()) {
                        items
                    } else {
                        items.filter { it.displayName.contains(trimmed, ignoreCase = true) }
                    }
                }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

        /** Archivierte Uebungen (Schritt 7): keine Suche, kleine Wiederherstellungs-Sektion. */
        val archivedItems: StateFlow<List<ExerciseLibraryItem>> =
            workoutRepository
                .observeArchivedExerciseLibrary(locale)
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

        /**
         * Ziele nach Uebungs-ID (Entscheidung 13, DB v9): Die Pflege lebt
         * hier in der Bibliothek, nicht im TrainScreen. Als Map, damit jede
         * Zeile ihr Ziel ohne eigene Abfrage findet.
         */
        val targets: StateFlow<Map<Long, ExerciseTarget>> =
            targetRepository
                .observeAllTargets()
                .map { list -> list.associateBy { it.exerciseId } }
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

        fun setQuery(value: String) {
            _query.value = value
        }

        /** Archivieren ist umkehrbar (Restore in derselben Sektion) — kein Dialog noetig. */
        fun archiveExercise(exerciseId: Long) {
            viewModelScope.launch {
                workoutRepository.archiveExercise(exerciseId)
            }
        }

        fun restoreExercise(exerciseId: Long) {
            viewModelScope.launch {
                workoutRepository.restoreExercise(exerciseId)
            }
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

        /**
         * Setzt das Ziel einer Uebung (Entscheidung 14). Gewicht kommt als
         * Textfeld-Inhalt: Das Parsen gehoert hierher, weil die Eingabe hier
         * entsteht — der Kern nimmt Zahlen, keine Strings.
         *
         * Ungueltige Eingaben werden verworfen statt gemeldet: Der Dialog
         * gibt den Speichern-Knopf erst frei, wenn beide Felder gefuellt
         * sind, deshalb ist ein Fehlerpfad hier unerreichbar.
         */
        fun setTarget(
            exerciseId: Long,
            weightKgInput: String,
            repsInput: String,
        ) {
            val weightMilliKg =
                runCatching { WorkoutMath.roundKgInputToMilliKg(weightKgInput) }.getOrNull() ?: return
            val reps = repsInput.trim().toIntOrNull() ?: return
            viewModelScope.launch {
                targetRepository.setTarget(exerciseId, weightMilliKg, reps)
            }
        }

        fun clearTarget(exerciseId: Long) {
            viewModelScope.launch {
                targetRepository.clearTarget(exerciseId)
            }
        }
    }
