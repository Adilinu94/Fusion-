package com.dropsync.feature.workout

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dropsync.core.common.AppResult
import com.dropsync.core.model.RestMode
import com.dropsync.core.model.SetRole
import com.dropsync.domain.timer.DropRestRequestBus
import com.dropsync.domain.timer.RestTimerPreferencesRepository
import com.dropsync.domain.timer.TimerEngine
import com.dropsync.domain.timer.TimerMode
import com.dropsync.domain.workout.ExerciseInfo
import com.dropsync.domain.workout.PlayedTrackInfo
import com.dropsync.domain.workout.RestPref
import com.dropsync.domain.workout.SegmentInput
import com.dropsync.domain.workout.SessionExerciseInfo
import com.dropsync.domain.workout.SwapStrategy
import com.dropsync.domain.workout.WorkoutMath
import com.dropsync.domain.workout.WorkoutRepository
import com.dropsync.domain.workout.WorkoutSessionInfo
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Locale
import javax.inject.Inject

/** Ergebnis eines Satzabschlusses fuer die Undo-Snackbar (12.5). */
data class CompletedClusterUi(
    val clusterId: Long,
    val summary: String,
)

/** Vorbelegung der Eingabefelder aus dem letzten Satz derselben Uebung (9.4). */
data class PrefillUi(
    val weight: String,
    val reps: String,
    val perHand: Boolean,
)

/** Get-Ready-Vorlauf (Musik-Workout-Plan B9): an/aus + Dauer in Sekunden. */
data class GetReadyUi(
    val enabled: Boolean,
    val seconds: Int,
)

@HiltViewModel
class WorkoutViewModel
    @Inject
    constructor(
        private val workoutRepository: WorkoutRepository,
        private val timerEngine: TimerEngine,
        private val dropRestRequestBus: DropRestRequestBus,
        restTimerPreferences: RestTimerPreferencesRepository,
    ) : ViewModel() {
        private val locale: String = Locale.getDefault().language

        /** Bearbeitbare Rest-Schnellwahl in Sekunden (B8); Chips im Rest-Dialog. */
        val restPresetsSeconds: StateFlow<List<Int>> =
            restTimerPreferences.restPresetsSeconds.stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5_000),
                RestTimerPreferencesRepository.DEFAULT_PRESETS_SECONDS,
            )

        /** Get-Ready-Vorlauf (B9); startRest zieht ihn als prepMs heran. */
        private val getReady: StateFlow<GetReadyUi> =
            combine(
                restTimerPreferences.getReadyEnabled,
                restTimerPreferences.getReadySeconds,
            ) { enabled, seconds -> GetReadyUi(enabled, seconds) }.stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5_000),
                GetReadyUi(false, RestTimerPreferencesRepository.DEFAULT_GET_READY_SECONDS),
            )

        /** Hoechstens eine aktive Session (Bauplan 9.8). */
        val activeSession: StateFlow<WorkoutSessionInfo?> =
            workoutRepository.activeSession.stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5_000),
                null,
            )

        /** Uebungsauswahl: Slug-basiert, lokalisiert (9.1). */
        val exercises: StateFlow<List<ExerciseInfo>> =
            workoutRepository.observeExercises(locale).stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5_000),
                emptyList(),
            )

        /** Uebungen der aktiven Session in stabiler Reihenfolge (9.3). */
        @OptIn(ExperimentalCoroutinesApi::class)
        val sessionExercises: StateFlow<List<SessionExerciseInfo>> =
            workoutRepository.activeSession
                .flatMapLatest { session ->
                    if (session == null) {
                        flowOf(emptyList())
                    } else {
                        workoutRepository.observeSessionExercises(session.id, locale)
                    }
                }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

        private val _lastCompleted = MutableStateFlow<CompletedClusterUi?>(null)

        /** Letzter Abschluss; UI bietet 10 s lang Rueckgaengig an (12.5). */
        val lastCompleted: StateFlow<CompletedClusterUi?> = _lastCompleted.asStateFlow()

        private val _restPrefs = MutableStateFlow<Map<Long, RestPref>>(emptyMap())

        /** Pro Uebung gemerkter Resttimer, Schluessel = exerciseId (Abschnitt 8). */
        val restPrefs: StateFlow<Map<Long, RestPref>> = _restPrefs.asStateFlow()

        private val _prefills = MutableStateFlow<Map<Long, PrefillUi>>(emptyMap())

        /** Prefill je Sessionuebung, Schluessel = sessionExerciseId (9.4). */
        val prefills: StateFlow<Map<Long, PrefillUi>> = _prefills.asStateFlow()

        private val _lastPlayedTrack = MutableStateFlow<PlayedTrackInfo?>(null)

        /** Zuletzt zur Session erfasster Track fuer den Chip (11.1). */
        val lastPlayedTrack: StateFlow<PlayedTrackInfo?> = _lastPlayedTrack.asStateFlow()

        fun startSession() {
            viewModelScope.launch {
                workoutRepository.startSession(title = null, fromRoutineId = null)
            }
        }

        fun completeSession() {
            val session = activeSession.value ?: return
            viewModelScope.launch { workoutRepository.completeSession(session.id) }
        }

        /** Setzt nur status = DISCARDED; loescht keine Daten (9.8). */
        fun discardSession() {
            val session = activeSession.value ?: return
            viewModelScope.launch { workoutRepository.discardSession(session.id) }
        }

        fun addExercise(exerciseId: Long) {
            val session = activeSession.value ?: return
            viewModelScope.launch {
                workoutRepository.addExercise(session.id, exerciseId, supersetGroupId = null)
            }
        }

        /**
         * Schliesst einen einfachen Arbeitssatz ab. Gewichtseingabe in kg
         * (Komma oder Punkt); [perHand] setzt loadMultiplier = 2 fuer zwei
         * gleich schwere Implementseiten (5.4).
         */
        fun completeSet(
            sessionExerciseId: Long,
            weightInput: String,
            repsInput: String,
            perHand: Boolean,
            summary: String,
        ) {
            val loadMilliKg =
                runCatching { WorkoutMath.roundKgInputToMilliKg(weightInput) }.getOrNull()
                    ?: return
            if (loadMilliKg < 0) return
            val reps = repsInput.trim().toIntOrNull() ?: return
            if (reps <= 0) return
            viewModelScope.launch {
                val result =
                    workoutRepository.completeCluster(
                        sessionExerciseId = sessionExerciseId,
                        setRole = SetRole.WORKING,
                        segments =
                            listOf(
                                SegmentInput(
                                    externalLoadMilliKgPerImplement = loadMilliKg,
                                    loadMultiplier = if (perHand) 2 else 1,
                                    reps = reps,
                                ),
                            ),
                        note = null,
                    )
                if (result is AppResult.Success) {
                    _lastCompleted.value = CompletedClusterUi(result.value, summary)
                }
            }
        }

        /** Rueckgaengig innerhalb des Snackbar-Fensters (12.5). */
        fun undoLastCompleted() {
            val completed = _lastCompleted.value ?: return
            viewModelScope.launch {
                workoutRepository.undoCompleteCluster(completed.clusterId)
                _lastCompleted.value = null
            }
        }

        fun clearLastCompleted() {
            _lastCompleted.value = null
        }

        /** Laedt Prefill (9.4) und Rest-Praeferenz (Abschnitt 8) einer Sessionuebung. */
        fun loadExerciseExtras(sessionExercise: SessionExerciseInfo) {
            viewModelScope.launch {
                val prefillResult =
                    workoutRepository.lastCompletedClusterPrefill(sessionExercise.exerciseId)
                if (prefillResult is AppResult.Success) {
                    val first = prefillResult.value.firstOrNull()
                    val load = first?.externalLoadMilliKgPerImplement
                    val reps = first?.reps
                    if (first != null && load != null && reps != null) {
                        _prefills.value = _prefills.value +
                            (
                                sessionExercise.id to
                                    PrefillUi(
                                        weight = formatKg(load),
                                        reps = reps.toString(),
                                        perHand = first.loadMultiplier == 2,
                                    )
                            )
                    }
                }
                val prefResult = workoutRepository.getRestPref(sessionExercise.exerciseId)
                if (prefResult is AppResult.Success) {
                    val pref = prefResult.value
                    if (pref != null) {
                        _restPrefs.value = _restPrefs.value + (sessionExercise.exerciseId to pref)
                    }
                }
            }
        }

        /** Merkt Restdauer und Rest-Modus pro Uebung (kein globaler Standard). */
        fun setRestPref(
            exerciseId: Long,
            restSeconds: Int,
            restMode: RestMode,
        ) {
            if (restSeconds <= 0) return
            viewModelScope.launch {
                workoutRepository.setRestPref(exerciseId, restSeconds, restMode)
                _restPrefs.value = _restPrefs.value + (exerciseId to RestPref(restSeconds, restMode))
            }
        }

        /**
         * Startet den Rest gemaess Praeferenz: fester REST-Timer ueber die
         * eine TimerEngine oder DropSync-Wunsch an den Player (8, 11.2).
         */
        fun startRest(exerciseId: Long) {
            val pref =
                _restPrefs.value[exerciseId]
                    ?: RestPref(DEFAULT_REST_SECONDS, RestMode.NORMAL)
            if (pref.restMode == RestMode.DROPSYNC) {
                dropRestRequestBus.request()
            } else {
                // Get-Ready (B9): optionaler 3-2-1-Vorlauf vor dem Rest-Countdown.
                val prep = getReady.value
                val prepMs = if (prep.enabled) prep.seconds * 1_000L else 0L
                timerEngine.start(TimerMode.REST, pref.restSeconds * 1_000L, prepMs)
            }
        }

        /** Uebungstausch KEEP/MOVE/DISCARD mitten in der Session (Schritt 9). */
        fun swapExercise(
            sessionExerciseId: Long,
            newExerciseId: Long,
            strategy: SwapStrategy,
        ) {
            viewModelScope.launch {
                workoutRepository.swapSessionExercise(sessionExerciseId, newExerciseId, strategy)
            }
        }

        /** Letzte abgeschlossene Session mit einem Tap wiederholen (9.6). */
        fun repeatLastSession() {
            viewModelScope.launch { workoutRepository.repeatLastSession() }
        }

        /** Speichert die aktive Session als wiederverwendbare Routine (9.7). */
        fun saveSessionAsRoutine(name: String) {
            val session = activeSession.value ?: return
            if (name.isBlank()) return
            viewModelScope.launch {
                workoutRepository.createRoutineFromSession(session.id, name.trim())
            }
        }

        /** Aktualisiert den zuletzt erfassten Track der Session (11.1). */
        fun refreshSessionMusic() {
            val session = activeSession.value ?: return
            viewModelScope.launch {
                val result = workoutRepository.getSessionMusic(session.id)
                if (result is AppResult.Success) {
                    _lastPlayedTrack.value = result.value.lastOrNull()
                }
            }
        }

        private companion object {
            /** Vorgabe, solange die Uebung noch keine Praeferenz hat. */
            const val DEFAULT_REST_SECONDS = 90
        }
    }

/** Millikilogramm als kg-Eingabetext, z. B. 92500 -> "92.5" (5.4). */
internal fun formatKg(milliKg: Long): String {
    val whole = milliKg / 1000
    val frac = milliKg % 1000
    if (frac == 0L) return whole.toString()
    val fracText = frac.toString().padStart(3, '0').trimEnd('0')
    return "$whole.$fracText"
}
