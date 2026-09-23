package com.dropsync.feature.workout

import android.util.Log
import androidx.activity.result.contract.ActivityResultContract
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dropsync.core.common.AppResult
import com.dropsync.core.common.Clock
import com.dropsync.core.model.Equipment
import com.dropsync.core.model.ExerciseKind
import com.dropsync.core.model.MuscleGroup
import com.dropsync.core.model.PlaylistLabel
import com.dropsync.core.model.RestMode
import com.dropsync.domain.audio.AudioEngineRepository
import com.dropsync.domain.audio.DspConfig
import com.dropsync.domain.health.HealthPermissionContract
import com.dropsync.domain.health.HeartRateAvailability
import com.dropsync.domain.health.HeartRateSource
import com.dropsync.domain.library.LibraryBrowseRepository
import com.dropsync.domain.playback.RestMusicSettingsRepository
import com.dropsync.domain.sensor.ActiveSetController
import com.dropsync.domain.sensor.ActiveSetPhase
import com.dropsync.domain.sensor.CalibrationProfile
import com.dropsync.domain.sensor.CalibrationProfileRepository
import com.dropsync.domain.sensor.RepCountPlausibility
import com.dropsync.domain.sensor.SensorConnectionState
import com.dropsync.domain.sensor.SensorErrorReason
import com.dropsync.domain.sensor.SensorProvider
import com.dropsync.domain.sensor.SensorSample
import com.dropsync.domain.sensor.SetAbortReason
import com.dropsync.domain.sensor.SetDiagnostics
import com.dropsync.domain.sensor.SetDiagnosticsLog
import com.dropsync.domain.sensor.SetTrace
import com.dropsync.domain.sensor.SignalQuality
import com.dropsync.domain.sensor.accelMagnitude
import com.dropsync.domain.sensor.calibration.CalibrationRefiner
import com.dropsync.domain.sensor.calibration.ProfileLearningEvent
import com.dropsync.domain.sensor.calibration.ProfileLearningPolicy
import com.dropsync.domain.timer.CancelReason
import com.dropsync.domain.timer.DropLandingPlanner
import com.dropsync.domain.timer.DropRestRequestBus
import com.dropsync.domain.timer.DropSyncState
import com.dropsync.domain.timer.DropSyncStateSource
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
import com.dropsync.domain.workout.SetLogHaptics
import com.dropsync.domain.workout.WorkoutRepository
import com.dropsync.feature.workout.shadow.SampleWindow
import com.dropsync.feature.workout.shadow.ShadowDiffEvent
import com.dropsync.feature.workout.shadow.ShadowSessionRecorder
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.math.BigDecimal
import java.util.UUID
import javax.inject.Inject
import kotlin.math.roundToLong

/** C3: Kann Drop-Auto fuer die naechste Pause ueberhaupt landen? */
sealed interface DropAutoReadiness {
    data object Ready : DropAutoReadiness

    data class Blocked(
        val reason: DropAutoBlockReason,
    ) : DropAutoReadiness
}

/** C3: Grund, warum Drop-Auto nichts bewirken kann (direkt am Schalter). */
enum class DropAutoBlockReason {
    NO_REST_PLAYLIST,
    NO_WORK_PLAYLIST,
}

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
        private val setLogHaptics: SetLogHaptics,
        private val timerEngine: TimerEngine,
        private val restTimerServiceStarter: RestTimerServiceStarter,
        private val sensorProvider: SensorProvider,
        private val calibrationProfileRepository: CalibrationProfileRepository,
        private val setDiagnosticsLog: SetDiagnosticsLog,
        restTimerPreferences: RestTimerPreferencesRepository,
        private val restMusicSettings: RestMusicSettingsRepository,
        private val dropSyncStateSource: DropSyncStateSource,
        private val shadowSessionRecorder: ShadowSessionRecorder,
        private val heartRateSource: HeartRateSource,
        private val dropRestRequestBus: DropRestRequestBus,
        // C3 (5.3): Bereitschaftsgrund am Schalter und Ducking am Ort.
        private val browseRepository: LibraryBrowseRepository,
        private val audioEngine: AudioEngineRepository,
        @HealthPermissionContract
        val healthPermissionContract: ActivityResultContract<Set<String>, Set<String>>,
        private val clock: Clock,
        private val dispatchers: com.dropsync.core.common.DispatcherProvider,
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

        /**
         * A1 (T-1): Log/Undo/Haptik laufen im [SetLogController]; die UI
         * hoert auf [setLogEvents] (Snackbar mit Rueckgaengig).
         */
        private val setLogController = SetLogController(flatSetRepository, setLogHaptics)
        val setLogEvents: Flow<SetLogEvent> = setLogController.events

        /**
         * A4/T-4: eigener, kurzlebiger Scope fuer das Schliessen der
         * Recording-Session in [onCleared] — der `viewModelScope` ist dort
         * bereits gecancelt.
         */
        private val closingScope = CoroutineScope(SupervisorJob() + dispatchers.io)

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

        /**
         * RC-4: true, wenn der letzte gezaehlte Satz mit 0 Reps endete. Die
         * UI zeigt dann einen Hinweis samt Handlung statt einer stummen
         * leeren Zahl. Wird beim naechsten Start, Loggen und Uebungswechsel
         * zurueckgesetzt.
         */
        private val _countedZero = MutableStateFlow(false)
        val countedZero: StateFlow<Boolean> = _countedZero.asStateFlow()

        /**
         * True, sobald beide Felder belastbare Werte tragen. Das Gewicht
         * wird kommatolerant geparst ("92,5" und "92.5" sind gleichwertig) —
         * die selbe Regel wie in [logSet], sonst waere der Button bei
         * deutscher Tastatur still disabled (Befund: Dezimalkomma).
         */
        val canLog: Boolean
            get() = parseWeightMilliKg(_weightInput.value) != null && _repsInput.value.toIntOrNull() != null

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

        /**
         * C3 (5.3): Musikmodus je Uebung. NORMAL uebernimmt den globalen
         * Drop-Auto-Schalter, DROPSYNC fordert fuer diese Uebung immer eine
         * Landung an (wirkt auch bei global NORMAL).
         */
        private val _restMode = MutableStateFlow(RestMode.NORMAL)
        val restMode: StateFlow<RestMode> = _restMode.asStateFlow()

        /** C3: true, sobald die Uebung eine eigene Praeferenz besitzt. */
        private val restPrefConfigured = MutableStateFlow(false)

        /**
         * C3: Revisionszaehler gegen das Ueberholen — ein spaet eintreffender
         * Ladevorgang darf eine frischere Nutzerauswahl nicht ueberschreiben.
         */
        private var restPrefRevision = 0

        /**
         * C3: Bereitschaft von Drop-Auto — der Dialog nennt den Grund,
         * wenn der Schalter nichts bewirken kann (Pausen-/Work-Playlist).
         */
        val dropAutoReadiness: StateFlow<DropAutoReadiness> =
            combine(
                browseRepository.playlistsByLabel(PlaylistLabel.REST),
                browseRepository.playlistsByLabel(PlaylistLabel.WORK),
            ) { rest, work ->
                when {
                    rest.isEmpty() -> DropAutoReadiness.Blocked(DropAutoBlockReason.NO_REST_PLAYLIST)
                    work.isEmpty() -> DropAutoReadiness.Blocked(DropAutoBlockReason.NO_WORK_PLAYLIST)
                    else -> DropAutoReadiness.Ready
                }
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DropAutoReadiness.Ready)

        /** C3 (P-3): Ducking der Pausenmusik am Ort (dieselbe DSP-Quelle). */
        val restDuckDb: StateFlow<Double> =
            audioEngine.dspConfig
                .map { it.restDuckDb }
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DspConfig().restDuckDb)

        fun setRestDuckDb(db: Double) {
            viewModelScope.launch {
                val current = audioEngine.dspConfig.first()
                val clamped = db.coerceIn(DspConfig.REST_DUCK_MIN_DB, DspConfig.REST_DUCK_MAX_DB)
                audioEngine.updateDspConfig(current.copy(restDuckDb = clamped))
            }
        }

        /**
         * C3 (5.3): Pausendauer und Musikmodus der gewaehlten Uebung
         * speichern und sofort anwenden.
         */
        fun setRestPref(
            restSeconds: Int,
            restMode: RestMode,
        ) {
            val exercise = _selectedExercise.value ?: return
            restPrefRevision++
            _restSeconds.value = restSeconds
            _restMode.value = restMode
            restPrefConfigured.value = true
            viewModelScope.launch {
                val result = workoutRepository.setRestPref(exercise.id, restSeconds, restMode)
                if (result is AppResult.Failure) {
                    errorEvents.trySend(TrainErrorEvent.RestPrefSaveFailed)
                }
            }
        }

        // Drop-Auto-Switch per rest (design doc Phase 3 step 4). Befund
        // 3.14: der Schalter ist verdrahtet — bei laufender Pause mit
        // aktivem Schalter fordert der Train-Tab eine Drop-Landung an.
        // MP-13: persistiert (Default an), damit die Produktabsicht
        // "Musik landet am Pausenende" nicht bei jedem Start verloren geht.
        // Eagerly: `startRestTimer` liest `.value` ohne Collector; ein
        // WhileSubscribed-Flow lieferte dort sonst den Initialwert.
        val dropAutoEnabled: StateFlow<Boolean> =
            restMusicSettings.dropAutoEnabled.stateIn(
                viewModelScope,
                SharingStarted.Eagerly,
                RestMusicSettingsRepository.DEFAULT_DROP_AUTO_ENABLED,
            )

        fun setDropAutoEnabled(enabled: Boolean) {
            viewModelScope.launch { restMusicSettings.setDropAutoEnabled(enabled) }
        }

        /**
         * C15 (PR-3): der wirksame DropSync-Schalter im Countdown — die
         * Uebungs-Praeferenz, sonst der globale Schalter (eine Wahrheit).
         * Eagerly: [wantsDropLanding] liest `.value` ohne Collector.
         */
        val effectiveDropAuto: StateFlow<Boolean> =
            combine(dropAutoEnabled, _restMode, restPrefConfigured) { global, mode, configured ->
                if (configured) mode == RestMode.DROPSYNC else global
            }.stateIn(
                viewModelScope,
                SharingStarted.Eagerly,
                RestMusicSettingsRepository.DEFAULT_DROP_AUTO_ENABLED,
            )

        /**
         * C15 (PR-3/PR-4): Schalter in der Pausen-Konsole. Bei laufender
         * Pause plant er sofort; unter einer Minute ist er wirkungslos
         * (die UI bietet ihn dort gar nicht erst an).
         */
        fun setDropAutoForCurrentRest(enabled: Boolean) {
            if (enabled &&
                timerEngine.state.value.remainingMs < DropLandingPlanner.MIN_DROP_AUTO_REST_MS
            ) {
                return
            }
            val exercise = _selectedExercise.value
            if (exercise != null && restPrefConfigured.value) {
                // Die Uebung hat eine Praeferenz: sie ist die Wahrheit (5.3).
                setRestPref(_restSeconds.value, if (enabled) RestMode.DROPSYNC else RestMode.NORMAL)
            } else {
                setDropAutoEnabled(enabled)
            }
            if (enabled) dropRestRequestBus.request()
        }

        /**
         * P1-10: Der DropSync-Plan-Zustand aus dem Player-Feature, damit die
         * Train-Konsole "DropSync · Titel · Ziel" zeigen kann (Design 4.5).
         */
        val dropSyncState: StateFlow<DropSyncState> = dropSyncStateSource.state

        /**
         * P1-10: "Plan abbrechen" in der Konsole — die Pause laeuft weiter,
         * nur die geplante Landung wird zurueckgenommen.
         */
        fun cancelDropPlan() {
            dropSyncStateSource.cancelPlan()
        }

        /** Skip the running rest timer (train pill action). */
        fun skipRest() {
            timerEngine.cancel(CancelReason.USER)
            timerEngine.reset()
        }

        /** Extends the active local rest by fifteen seconds. */
        fun addRestTime() {
            timerEngine.addTime(15_000)
        }

        /** Rule (design step 5): finish exercise cancels the timer at once. */
        fun finishExercise() {
            logShadowDiff("finishExercise")
            // A4/T-4: KEIN endSession mehr — die Recording-Session laeuft bis
            // onCleared weiter (sonst waere nach dem ersten Abschluss Schluss).
            abortActiveSet(SetAbortReason.EXERCISE_FINISHED)
            // A1: ein offenes Undo gehoert zur alten Sitzung.
            setLogController.clearUndo()
            timerEngine.cancel(CancelReason.USER)
            timerEngine.reset()
            _selectedExercise.value = null
            _lastSet.value = null
            _maxVolumeKg.value = null
            _weightInput.value = ""
            _repsInput.value = ""
            _repsInputEdited.value = false
            _countedZero.value = false
            repSourceTracker.onSetReset()
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
                // Drop-Auto (Befund 3.14): fire-and-forget an den Player.
                // C3 (5.3): Eine Uebung mit eigener Praeferenz entscheidet
                // selbst; ohne Praeferenz gilt der globale Schalter.
                // C15 (PR-4): unter einer Minute keine Drop-Auto-Landung.
                if (wantsDropLanding() &&
                    _restSeconds.value * 1_000L >= DropLandingPlanner.MIN_DROP_AUTO_REST_MS
                ) {
                    dropRestRequestBus.request()
                }
            }
        }

        private fun wantsDropLanding(): Boolean = effectiveDropAuto.value

        fun selectExercise(exercise: ExerciseInfo) {
            logShadowDiff("exerciseSwitch")
            abortActiveSet(SetAbortReason.EXERCISE_CHANGED)
            // A1: Uebungswechsel verwirft ein offenes Undo der alten Uebung.
            setLogController.clearUndo()
            _countedZero.value = false
            repSourceTracker.onSetReset()
            _selectedExercise.value = exercise
            loadLastSet(exercise.id)
            loadMaxVolume(exercise.id)
            loadRestPref(exercise.id)
            resetShadowEngine()
            loadActiveProfile()
        }

        private fun loadRestPref(exerciseId: Long) {
            val revision = restPrefRevision
            viewModelScope.launch {
                val result = workoutRepository.getRestPref(exerciseId)
                // C3: Hat der Nutzer inzwischen gespeichert, gilt seine Wahl.
                if (revision != restPrefRevision) return@launch
                when (result) {
                    is AppResult.Success -> {
                        val pref = result.value
                        _restSeconds.value = pref?.restSeconds ?: RestPref.DEFAULT_REST_SECONDS
                        _restMode.value = pref?.restMode ?: RestMode.NORMAL
                        restPrefConfigured.value = pref != null
                    }

                    is AppResult.Failure -> {
                        _restSeconds.value = RestPref.DEFAULT_REST_SECONDS
                        _restMode.value = RestMode.NORMAL
                        restPrefConfigured.value = false
                    }
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

        /**
         * Gewicht um [deltaKg] schrittweise anpassen. Gerechnet wird in
         * ganzen Millikilogramm — nie als Double-String-Arithmetik, sonst
         * entstehen Artefakte wie "22.499999999999996" im Eingabefeld.
         */
        fun adjustWeight(deltaKg: Double) {
            val deltaMilliKg = (deltaKg * 1_000_000).roundToLong()
            val current = parseWeightMilliKg(_weightInput.value) ?: 0L
            val next = (current + deltaMilliKg).coerceAtLeast(0L)
            _weightInput.value = formatMilliKgForInput(next)
        }

        fun setReps(value: String) {
            _repsInput.value = value
            _repsInputEdited.value = true
        }

        fun logSet() {
            val exercise = _selectedExercise.value ?: return
            // Kommatolerant (HALF_UP auf ganze Gramm): "92,5" und "92.5"
            // landen beide bei 92_500_000 Millikilogramm.
            val weightMilliKg = parseWeightMilliKg(_weightInput.value) ?: return
            val reps = _repsInput.value.toIntOrNull() ?: return
            // Umbauplan Phase 10.5: Eingabevalidierung an der UI-Grenze.
            if (weightMilliKg < 0 || weightMilliKg > MAX_REASONABLE_WEIGHT_MILLIKG) return
            if (reps <= 0 || reps > MAX_REASONABLE_REPS) return
            // Captured before any reset below (D3/ADR-0014): reflects what
            // the user actually confirmed for *this* set, not a later state.
            val repsEdited = _repsInputEdited.value

            viewModelScope.launch {
                when (setLogController.logSet(exercise.id, weightMilliKg, reps)) {
                    is AppResult.Success -> {
                        loadLastSet(exercise.id)
                        loadMaxVolume(exercise.id)
                        loadRecentSets()
                        // Live (confirmed) count for the shadow diff (11b).
                        liveRepCount += reps
                        // Paket C: Trace vom Controller nehmen (unveraenderlich).
                        val trace = activeSetController.finishAndTakeTrace()
                        val counted = trace?.predictedReps ?: 0
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
                                shadowReps = counted,
                                // RC-17: Ablehnungsmechanismen mit ins JSONL.
                                rejectionCounts = trace?.diagnostics?.rejectionCounts ?: emptyMap(),
                                // RC-16: restliche Live-Diagnose fuer den
                                // Live-vs-Replay-Vergleich im Offline-Harness.
                                diagnostics = trace?.diagnostics,
                            ),
                        )
                        // Paket D: Lernpfad ueber den unveraenderlichen Trace.
                        if (trace != null) {
                            // Umbauplan 2026-09-04 Phase 0: die Rohsamples
                            // gehoeren zum eben geschriebenen set-Event. Muss
                            // NACH recordSet stehen (der Recorder leitet den
                            // setIndex daraus ab) und VOR learnFromTrace, damit
                            // ein Fehler im Lernpfad die Aufnahme nicht
                            // verhindert.
                            shadowSessionRecorder.recordSamples(
                                SampleWindow(
                                    exerciseId = exercise.id,
                                    measuredSampleRateHz = trace.measuredSampleRateHz,
                                    samples = trace.samples,
                                    // Nachtrag Phase 0.7: ohne Achse und Bias
                                    // ist ein Offline-Replay sinnlos, denn sie
                                    // bestimmen, WELCHES Signal die Pipeline
                                    // sieht - und sie sind kalibriert, also aus
                                    // keiner anderen Quelle rekonstruierbar.
                                    profile = activeProfile,
                                ),
                            )
                            learnFromTrace(trace, reps)
                        }
                        // Keep weight, reset reps for the next set.
                        _repsInput.value = ""
                        _repsInputEdited.value = false
                        _countedZero.value = false
                        // RC-5: die Quellen-Zeile gehoert zum abgeschlossenen
                        // Satz, nicht in den naechsten hinein.
                        repSourceTracker.onSetReset()
                        // Rule (design step 5): set done -> rest timer starts.
                        startRestTimer()
                    }

                    is AppResult.Failure -> {
                        // Befund 3.14: Fehlerpfade nicht mehr stumm. Der
                        // [SetLogController] emittiert [SetLogEvent.LogFailed];
                        // der Screen zeigt ihn ueber den Shell-Snackbar-Host.
                    }
                }
            }
        }

        /**
         * A1 (T-1): nimmt den zuletzt geloggten Satz zurueck. Der Satz wird
         * geloescht und die PRs aus der Resthistorie neu berechnet
         * ([SetLogController]); die UI-Buchhaltung (Live-Zaehler, Listen,
         * Quelle) zieht nach.
         */
        fun undoLastSet() {
            viewModelScope.launch {
                when (val outcome = setLogController.undoLast()) {
                    null -> {
                        Unit
                    }

                    is AppResult.Success -> {
                        val undo = outcome.value
                        liveRepCount = (liveRepCount - undo.reps).coerceAtLeast(0)
                        repSourceTracker.onSetReset()
                        loadLastSet(undo.exerciseId)
                        loadMaxVolume(undo.exerciseId)
                        loadRecentSets()
                    }

                    is AppResult.Failure -> {
                        Unit
                    }
                }
            }
        }

        /**
         * P1-12/RC-6: Ergebnis des Lernpfads als Einmal-Ereignis fuer die UI
         * ("Kalibrierung verfeinert (Revision N)", Rollback, uebersprungen).
         * Vorher landete jedes Ergebnis nur im Log.
         *
         * T-10: `Channel(BUFFERED)` statt `SharedFlow` — ein Ereignis ohne
         * offenen Collector ging beim SharedFlow sofort verloren; hier
         * wartet es, bis der Screen es abholt. `trySend` blockiert den
         * Satz-Pfad nie (Puffer 64, Drops praktisch ausgeschlossen).
         */
        private val learningEvents = Channel<ProfileLearningEvent>(Channel.BUFFERED)
        val learningEvent: Flow<ProfileLearningEvent> = learningEvents.receiveAsFlow()

        /**
         * T-10/S-7: sichtbare Fehler (Uebung anlegen, Profil laden,
         * Lern-Save) als Einmal-Ereignis; vorher waren diese Pfade stumm.
         */
        private val errorEvents = Channel<TrainErrorEvent>(Channel.BUFFERED)
        val errorEvent: Flow<TrainErrorEvent> = errorEvents.receiveAsFlow()

        /**
         * RC-7/RC-17: Diagnose-Snapshot nach jedem gestoppten Satz als
         * Einmal-Ereignis fuer den Satz-Report (Snackbar). Wird zugleich in
         * den [SetDiagnosticsLog] geschrieben, aus dem das Diagnose-Panel in
         * den Einstellungen den letzten Stand liest.
         */
        private val setReports = Channel<SetDiagnostics>(Channel.BUFFERED)
        val setReport: Flow<SetDiagnostics> = setReports.receiveAsFlow()

        /**
         * Paket D: Lernpfad. Arbeitet nur mit unveraenderlichen Traces, lernt
         * nur bei brauchbarer Signalqualitaet und speichert Kandidaten, die
         * erst nach genug validierten Sets aktiv werden. Bei klarer
         * Verschlechterung rollt die App zur letzten guten Revision zurueck.
         *
         * P2-Fix #19: die Autokorrelations-Zweitmeinung wirkt als zusaetzliches
         * Gate. Widerspricht die im Signal messbare Periodizitaet dem
         * BESTAETIGTEN Zaehlerstand deutlich, wird nichts gelernt — dann passt
         * entweder die Nutzereingabe nicht zum Mitschnitt oder der Sensor sass
         * so schlecht, dass die Bewegung nicht im Signal steht. In beiden
         * Faellen wuerde der Refiner die Parameter in die falsche Richtung
         * ziehen.
         */
        private suspend fun learnFromTrace(
            trace: SetTrace,
            confirmedReps: Int,
        ) {
            val profile = activeProfile ?: return
            // Guard: kein Profilwechsel waehrend des Sets.
            if (profile.revision != trace.profileRevision) return
            // Guard: UNRELIABLE-Streams trainieren nie.
            if (trace.signalQuality == SignalQuality.UNRELIABLE) {
                learningEvents.trySend(ProfileLearningEvent.SkippedUnreliable)
                return
            }
            if (trace.samples.isEmpty()) return
            if (!plausibilityAllowsLearning(trace, confirmedReps)) {
                learningEvents.trySend(ProfileLearningEvent.SkippedImplausible)
                return
            }

            val diff = kotlin.math.abs(confirmedReps - trace.predictedReps)

            if (trace.predictedReps != confirmedReps) {
                // RC-6: Der Refiner rechnet (Brute-Force-Sweep) nicht auf dem
                // Main-Thread; die Anzeige bleibt fluessig.
                val candidate =
                    withContext(dispatchers.default) {
                        CalibrationRefiner.refine(trace.samples, confirmedReps, profile)
                    }
                if (candidate == null) {
                    // T-10/S-7: vorher stumm (`?: return`) — jetzt sichtbar.
                    learningEvents.trySend(ProfileLearningEvent.SkippedNotReproducible)
                    return
                }
                when (calibrationProfileRepository.save(candidate)) {
                    is AppResult.Success -> {
                        Log.d(SHADOW_TAG, "learn: candidate revision=${candidate.revision}")
                        learningEvents.trySend(ProfileLearningEvent.Refined(candidate.revision))
                    }

                    is AppResult.Failure -> {
                        Log.w(SHADOW_TAG, "learn: Kandidat konnte nicht gespeichert werden")
                        errorEvents.trySend(TrainErrorEvent.LearningSaveFailed)
                    }
                }
            } else {
                // Validierte Sets zaehlen fuer die Kandidaten-Promotion.
                when (calibrationProfileRepository.noteValidatedSet(trace.exerciseId, trace.deviceId)) {
                    is AppResult.Success -> Unit
                    is AppResult.Failure -> Log.w(SHADOW_TAG, "learn: noteValidatedSet fehlgeschlagen")
                }
            }

            // Rollback-Regel: zwei schlechte validierte Sets in Folge -> zurueck.
            // RC-13: Der Puffer gehoert je Uebung/Geraet; ein Uebungswechsel
            // darf die Bewertung nicht in die naechste Uebung tragen.
            val recentDiffs =
                recentDiffsByKey.getOrPut("${trace.exerciseId}:${trace.deviceId}") { mutableListOf() }
            recentDiffs.add(diff)
            if (ProfileLearningPolicy.shouldRollback(recentDiffs)) {
                val rolledBack = calibrationProfileRepository.rollback(trace.exerciseId, trace.deviceId)
                if (rolledBack is AppResult.Success && rolledBack.value) {
                    recentDiffs.clear()
                    loadActiveProfile()
                    learningEvents.trySend(ProfileLearningEvent.RolledBack)
                    Log.d(SHADOW_TAG, "learn: rollback auf letzte gute Revision")
                }
            }
        }

        /**
         * P2-Fix #19: prueft die bestaetigte Rep-Zahl gegen die
         * Autokorrelations-Schaetzung des Mitschnitts.
         *
         * Absichtlich nur ein VETO, keine Korrektur: die Autokorrelation kann
         * die Zahl nicht exakt bestimmen (Randeffekte, Tempowechsel), sie
         * erkennt aber sehr gut, ob im Signal ueberhaupt eine passende
         * Periodizitaet steckt. INCONCLUSIVE (zu kurzes oder zu
         * unregelmaessiges Set) blockiert nicht — sonst wuerde die App bei
         * kurzen Saetzen nie mehr lernen.
         */
        private fun plausibilityAllowsLearning(
            trace: SetTrace,
            confirmedReps: Int,
        ): Boolean {
            val result = trace.plausibility ?: return true
            val estimated = result.estimatedReps ?: return true
            if (result.verdict == RepCountPlausibility.Verdict.INCONCLUSIVE) return true
            val deviation = kotlin.math.abs(confirmedReps - estimated)
            if (deviation <= MAX_PLAUSIBILITY_DEVIATION) return true
            Log.w(
                SHADOW_TAG,
                "learn: uebersprungen - Autokorrelation erwartet ~$estimated Reps, " +
                    "bestaetigt wurden $confirmedReps (Periode=${result.periodSeconds}s)",
            )
            return false
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
                when (result) {
                    is AppResult.Success -> {
                        val created =
                            exercises.value.firstOrNull { it.id == result.value }
                                ?: ExerciseInfo(result.value, trimmed, trimmed)
                        selectExercise(created)
                    }

                    is AppResult.Failure -> {
                        // T-10: vorher stumm — der Nutzer sah nur, dass die
                        // Uebung nicht in der Liste auftaucht.
                        Log.w(SHADOW_TAG, "createExercise fehlgeschlagen: ${result.error}")
                        errorEvents.trySend(TrainErrorEvent.ExerciseCreationFailed)
                    }
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

        /** Umbauplan Phase 3: Signalqualitaet (GOOD/DEGRADED/UNRELIABLE). */
        val signalQuality: StateFlow<SignalQuality> =
            sensorProvider.health
                .map { it.quality }
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SignalQuality.UNRELIABLE)

        // --- Herzfrequenz-Badge (Herzfrequenz-Plan Phase 2) -----------------

        /** Verfuegbarkeits-/Berechtigungszustand der Health-Connect-Quelle. */
        val heartRateAvailability: StateFlow<HeartRateAvailability> =
            heartRateSource.availability
                .stateIn(
                    viewModelScope,
                    SharingStarted.WhileSubscribed(5_000),
                    HeartRateAvailability.HEALTH_CONNECT_NOT_AVAILABLE,
                )

        /** Letzter bekannter Puls (null, solange keiner vorliegt). */
        val heartRateSample: StateFlow<Int?> =
            heartRateSource.latestSample
                .map { it?.bpm }
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

        /** Permission-Strings fuer den Health-Connect-Berechtigungs-Launcher. */
        val heartRatePermissions: Set<String> = heartRateSource.requiredPermissions

        /**
         * Nach Dialog-Ergebnis oder App-Resume: Verfuegbarkeit neu bestimmen
         * und bei vorhandener Berechtigung die Daten nachladen (Plan 3.4:
         * nur Foreground-Aufrufe, gesteuert durch diese UI-Kadenz).
         */
        fun refreshHeartRate() {
            viewModelScope.launch {
                heartRateSource.refreshAvailability()
                if (heartRateAvailability.value == HeartRateAvailability.READY ||
                    heartRateAvailability.value == HeartRateAvailability.NO_RECENT_DATA
                ) {
                    heartRateSource.refresh()
                }
            }
        }

        /**
         * Grund des letzten Verbindungsfehlers, null wenn keiner vorliegt.
         *
         * P3-Fix #27: das ViewModel liefert einen klassifizierten
         * [SensorErrorReason] statt eines fertigen deutschen Satzes. Den Text
         * waehlt die Composable-Schicht aus `strings.xml` — nur dort ist die
         * Locale bekannt.
         */
        private val _sensorError = MutableStateFlow<SensorErrorReason?>(null)
        val sensorError: StateFlow<SensorErrorReason?> = _sensorError.asStateFlow()

        /**
         * Connects to a FlowRep chip by advertise-name scan (deviceId null).
         * The firmware auto-starts streaming after connect (Phase 4 quirk).
         */
        fun connectSensor() {
            viewModelScope.launch {
                _sensorError.value = null
                when (val result = sensorProvider.connect(null)) {
                    is AppResult.Success -> Unit
                    is AppResult.Failure -> _sensorError.value = SensorErrorReason.from(result.error)
                }
            }
        }

        /** Disconnects the chip; the provider falls back to the fake. */
        fun disconnectSensor() {
            logShadowDiff("disconnect")
            // A4/T-4: die Aufzeichnung laeuft ueber den Disconnect hinaus
            // weiter (Sensor kann wieder verbunden werden).
            abortActiveSet(SetAbortReason.DISCONNECT)
            viewModelScope.launch { sensorProvider.disconnect() }
        }

        /**
         * Rolling window of the last [WAVEFORM_WINDOW] acceleration magnitudes
         * (in g), normalized for the waveform. Empty while no chip streams.
         *
         * P1-Fix: der Puffer ist ein festes FloatArray, das je Sample nur an
         * einer Stelle ueberschrieben und als unveraenderlicher Snapshot
         * veroeffentlicht wird. Vorher entstand pro Sample ein
         * `ArrayDeque.toList()` — bei 50 Hz also 50 Listen mit je 200
         * geboxten Floats pro Sekunde.
         */
        private val _waveform = MutableStateFlow(FloatArray(0))
        val waveform: StateFlow<FloatArray> = _waveform.asStateFlow()

        /** One-shot peak flash: timestamp of the last detected acceleration peak. */
        private val _lastPeakMs = MutableStateFlow(0L)
        val lastPeakMs: StateFlow<Long> = _lastPeakMs.asStateFlow()

        /** Ringpuffer der Magnituden; [waveformFill] zaehlt bis WAVEFORM_WINDOW. */
        private val waveformRing = FloatArray(WAVEFORM_WINDOW)
        private var waveformHead = 0
        private var waveformFill = 0

        /** RC-11: letzte Veroeffentlichung, damit nicht jedes Sample recomposed. */
        private var lastWaveformPublishMs: Long? = null

        /**
         * Uebernimmt eine Magnitude in den Ringpuffer und veroeffentlicht den
         * Snapshot in Trackreihenfolge (aeltestes Sample zuerst).
         *
         * RC-11: Der Ring wird weiter mit jedem Sample (50 Hz) gefuellt, der
         * Snapshot aber nur im Frame-Takt (~30 Hz) veroeffentlicht — sonst
         * laufen pro Sekunde 50 Compose-Recompositions und 50 Allokationen
         * gegen die Anzeige.
         */
        private fun pushWaveformSample(magnitude: Float) {
            waveformRing[waveformHead] = magnitude
            waveformHead = (waveformHead + 1) % WAVEFORM_WINDOW
            if (waveformFill < WAVEFORM_WINDOW) waveformFill++
            val now = clock.elapsedRealtimeMs()
            val last = lastWaveformPublishMs
            if (last != null && now - last < WAVEFORM_PUBLISH_INTERVAL_MS) return
            lastWaveformPublishMs = now
            val snapshot = FloatArray(waveformFill)
            val start = if (waveformFill < WAVEFORM_WINDOW) 0 else waveformHead
            for (i in 0 until waveformFill) {
                snapshot[i] = waveformRing[(start + i) % WAVEFORM_WINDOW]
            }
            _waveform.value = snapshot
        }

        /** Confirmed reps of the selected exercise in this session (live). */
        private var liveRepCount = 0

        // --- Phase 4 live counting (start -> countdown -> count -> stop) ----
        //
        // Paket C: der Set-Lifecycle liegt im ActiveSetController
        // (domain:sensor). Das ViewModel delegiert und behaelt nur UI-nahe
        // Belange (Profil laden, Guards, Eingabefeld, Lernpfad).

        /** Umbauplan Phase 6: Set-Lifecycle-Controller (ein Controller pro ViewModel). */
        private val activeSetController =
            ActiveSetController(
                scope = viewModelScope,
                samples = sensorProvider.samples,
                connectionState = sensorProvider.connectionState,
                health = sensorProvider.health,
                clock = clock,
                // RC-1: Zaehlpipeline off-main; im Test derselbe Test-Dispatcher.
                workerDispatcher = dispatchers.default,
            )

        /** Live-count set state (drives the start/stop UI + countdown). */
        val setPhase: StateFlow<ActiveSetPhase> = activeSetController.phase

        /** Countdown seconds left before counting starts (0 while counting). */
        val countdownSeconds: StateFlow<Int> = activeSetController.countdownRemaining

        /** Reps counted live in the active set (0 unless COUNTING/finished). */
        val liveCountedReps: StateFlow<Int> = activeSetController.countedReps

        /**
         * Umbauplan 2026-09-04 Phase 7: die Autokorrelations-Zweitmeinung des
         * letzten abgeschlossenen Sets, sofern sie dem Zaehlerstand deutlich
         * widerspricht. null bedeutet **keine Aussage** — nicht "bestaetigt".
         *
         * Sichtbar ist der Hinweis genau im Fenster zwischen [stopCountedSet]
         * und [logSet]: also solange der Nutzer die Zahl noch korrigieren kann.
         * Danach raeumt [ActiveSetController.abort] die Zweitmeinung ab.
         *
         * Er verschwindet, sobald der Nutzer das Rep-Feld angefasst hat
         * ([repsInputEdited]). Das ist Absicht und nicht nur Kosmetik: der
         * Zweck des Hinweises ist, eine *aktive* Bestaetigung oder Korrektur
         * auszuloesen (D3-Regel, ADR-0014 — nur editierte Werte zaehlen als
         * unabhaengige Wahrheit). Ist die Editierung passiert, hat er seine
         * Aufgabe erfuellt.
         *
         * `Eagerly` und nicht `WhileSubscribed`: ohne Abonnenten liefert ein
         * lazy geteilter Flow nur seinen Initialwert — `.value` waere dann
         * `null`, obwohl eine Aussage vorliegt. Bei einem Zustand, dessen
         * ganzer Sinn ein kurzes Zeitfenster ist, ist ein `.value`, der luegt,
         * eine Falle. Die Kosten sind vernachlaessigbar: der Flow verknuepft
         * zwei StateFlows ohne eigene Arbeit.
         */
        val plausibilityHint: StateFlow<PlausibilityHint?> =
            combine(
                activeSetController.lastPlausibility,
                _repsInputEdited,
            ) { result, edited ->
                if (edited) null else result?.toHintOrNull()
            }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

        /**
         * RC-5: Herkunft der Rep-Zahl im Hero (UI-Handbuch 7.4). Zustand und
         * Ableitung liegen in [RepSourceTracker] — das ViewModel haelt nur
         * den Flow und meldet die Ereignisse (Stop, Reset, Abriss).
         */
        private val repSourceTracker = RepSourceTracker(viewModelScope, _repsInputEdited, sensorConnection)
        val repsSource: StateFlow<RepsSource> = repSourceTracker.source

        /** True once a calibration profile exists for the selected exercise. */
        private val _hasCalibration = MutableStateFlow(false)
        val hasCalibration: StateFlow<Boolean> = _hasCalibration.asStateFlow()

        /** Active calibration profile for the selected exercise+device. */
        private var activeProfile: CalibrationProfile? = null

        /** Paket D: Abweichungen der letzten Sets fuer die Rollback-Regel. */
        private val recentDiffsByKey = mutableMapOf<String, MutableList<Int>>()

        private var profileLoadJob: kotlinx.coroutines.Job? = null

        init {
            loadRecentSets()
            // Shadow-Diff-Harness Schritt 2/3: eine Recording-Session pro
            // ViewModel-Leben; der NoOp-Recorder in Tests ignoriert das.
            // A4/T-4: die Session ueberlebt finishExercise/Disconnect — sie
            // endet erst in onCleared, sonst faellt die restliche Sitzung aus
            // der Aufzeichnung.
            viewModelScope.launch {
                shadowSessionRecorder.startSession(UUID.randomUUID().toString().take(8))
            }
            // Paket C: Abort bei Verbindungsverlust / UNRELIABLE uebernimmt
            // der ActiveSetController selbst (health/connection-Flows).
            // Same engine tick as TimerViewModel: evaluate() is idempotent,
            // the tick is never the completion source (design step 7.1).
            // The ticker is a separate flow so tests can drive it from
            // TestScope.backgroundScope (auto-cancelled by runTest) instead of
            // relying on an internal isActive flag in the viewModelScope.
            viewModelScope.launch {
                tickerFlow(TICK_MS).collect { timerEngine.evaluate() }
            }
            // Phase 4 step 5: collect live samples for the waveform; a peak
            // (accel magnitude spike) triggers the flash overlay. Die
            // Live-Zaehlung selbst laeuft im ActiveSetController — hier wird
            // NUR die Anzeige gespeist.
            //
            // P1-Fix: die frueher hier zusaetzlich mitlaufende "Shadow"-Engine
            // ist entfernt. Sie war seit Umbauplan Punkt 1 mit identischer
            // Achse, identischem Bias und identischem Threshold konfiguriert
            // wie die Live-Engine im ActiveSetController, rechnete also
            // dasselbe Ergebnis — zum Preis einer zweiten vollstaendigen
            // Pipeline (SignalChain + PeakDetector + TemplateMatcher +
            // PhaseValidator + QualityScorer) auf jedem Sample.
            viewModelScope.launch {
                sensorProvider.samples.collect { sample ->
                    pushWaveformSample(sample.accelMagnitude.toFloat())
                }
            }
            // C14 (T-14): Der Peak-Blitz haengt am echten Rep-Event der
            // Engine (dieselbe Quelle wie die Live-Zahl) statt an einer
            // Flanken-Heuristik, die auch bei Stoessen ohne Rep feuerte.
            viewModelScope.launch {
                activeSetController.repEvents.collect { event ->
                    _lastPeakMs.value = event.timestampMs
                }
            }
            // RC-5/Design 8.1: Ein Abriss des laufenden Streams ist ein eigener
            // Zustand der Rep-Quelle ("Sensor getrennt — Reps per +/- weiter");
            // die Regel (nur ein zuvor echt streamender Chip zaehlt) liegt im
            // [RepSourceTracker].
            viewModelScope.launch {
                sensorConnection.collect { repSourceTracker.onConnectionChanged(it) }
            }
        }

        // --- Live set control ------------------------------------------------

        /**
         * Starts a live-counted set: needs a calibration profile and a
         * streaming chip. A short countdown lets the user get into the start
         * position before the pipeline begins counting. Paket C: der
         * [ActiveSetController] uebernimmt Countdown, Engine und Puffer.
         */
        fun startCountedSet() {
            if (setPhase.value != ActiveSetPhase.IDLE) return
            val profile = activeProfile ?: return
            if (sensorConnection.value != SensorConnectionState.STREAMING) return
            val deviceId = connectedDeviceId.value ?: return
            _countedZero.value = false
            // RC-5: ein neuer Zaehlversuch beginnt ohne Alt-Zaehlstand; die
            // Quelle zeigt waehrend der Zaehlung SENSOR.
            repSourceTracker.onSetReset()
            activeSetController.start(profile.exerciseId, deviceId, profile)
        }

        /**
         * Ends the active set and copies the counted reps into the reps input
         * (overwrites only if something was counted). The user can still
         * correct the number before logging.
         *
         * RC-4: Bei 0 erkannten Reps bleibt das Feld leer, aber der Zustand
         * [countedZero] wird gesetzt, damit die UI einen Grund-Hinweis samt
         * Handlung zeigen kann.
         *
         * B5/RC-1: UI-Fassade ohne Rueckgabe. Der Stop laeuft in
         * [viewModelScope], weil er den Sample-Worker per Join abwartet
         * (suspend); Zaehlstand-Uebernahme und Report-Emission passieren
         * deshalb in derselben Coroutine — die UI liest die Zustaende aus den
         * Flows.
         */
        fun stopCountedSet() {
            viewModelScope.launch {
                val counted = activeSetController.stop()
                _countedZero.value = counted == 0
                if (counted > 0) {
                    // RC-5: der Zaehlstand bleibt fuer die Quellen-Zeile erhalten
                    // ("Sensor erkannte 7"), auch wenn der Nutzer gleich korrigiert.
                    repSourceTracker.onCountedSetStopped(counted)
                    _repsInput.value = counted.toString()
                    _repsInputEdited.value = false
                }
                // RC-7/RC-17: Report direkt nach dem Stop zeigen - unabhaengig
                // davon, ob der Nutzer den Satz danach loggt oder verwirft.
                activeSetController.lastDiagnostics.value?.let { report ->
                    setReports.trySend(report)
                    setDiagnosticsLog.record(report)
                }
            }
        }

        /**
         * P0-Fix / Umbauplan Phase 6: bricht ein aktives Set atomar ab -
         * Countdown, Engine, Zaehlstand und Samplebuffer werden GEMEINSAM
         * zurueckgesetzt. Idempotent; darf aus jedem Zustand aufgerufen werden.
         */
        private fun abortActiveSet(reason: SetAbortReason) {
            activeSetController.abort(reason)
            if (reason != SetAbortReason.CLEARED) {
                Log.d(SHADOW_TAG, "abortActiveSet($reason)")
            }
        }

        /** Loads the calibration profile for the selected exercise (if any). */
        private fun loadActiveProfile() {
            // P0-Fix: Übung und Gerät kombiniert betrachten; bei späterem
            // Verbinden wird das Profil automatisch nachgeladen. flatMapLatest
            // verwirft veraltete Loads (Übungs-/Gerätewechsel).
            profileLoadJob?.cancel()
            profileLoadJob =
                viewModelScope.launch {
                    combine(_selectedExercise, connectedDeviceId) { exercise, deviceId ->
                        exercise?.id to deviceId
                    }.distinctUntilChanged()
                        .flatMapLatest { (exerciseId, deviceId) ->
                            flow {
                                if (exerciseId == null || deviceId == null) {
                                    activeProfile = null
                                    _hasCalibration.value = false
                                    emit(Unit)
                                    return@flow
                                }
                                val result = calibrationProfileRepository.load(exerciseId, deviceId)
                                when (result) {
                                    is AppResult.Success -> {
                                        activeProfile = result.value
                                        _hasCalibration.value = activeProfile != null
                                    }

                                    is AppResult.Failure -> {
                                        // T-10/S-7: ein Load-Fehler ist NICHT
                                        // "kein Profil vorhanden" — die UI
                                        // zeigte bisher faelschlich "nicht
                                        // kalibriert".
                                        activeProfile = null
                                        _hasCalibration.value = false
                                        Log.w(SHADOW_TAG, "Profil-Load fehlgeschlagen: ${result.error}")
                                        errorEvents.trySend(TrainErrorEvent.ProfileLoadFailed)
                                    }
                                }
                                emit(Unit)
                            }
                        }.collect {}
                }
        }

        /**
         * (Re)sets the per-exercise counters for the diff harness. Die frueher
         * hier erzeugte zweite Engine ist entfallen (siehe Sample-Collector).
         */
        private fun resetShadowEngine() {
            liveRepCount = 0
        }

        /** Logs the confirmed-vs-counted diff at session end (DoD metric). */
        private fun logShadowDiff(reason: String) {
            if (liveRepCount == 0) return
            Log.d(SHADOW_TAG, "diff($reason): live=$liveRepCount")
        }

        /** P0-Fix: ViewModel-Ende raeumt das aktive Set vollstaendig ab. */
        override fun onCleared() {
            setLogController.clearUndo()
            abortActiveSet(SetAbortReason.CLEARED)
            // A4/T-4: Recording-Session erst hier schliessen. viewModelScope
            // ist an dieser Stelle schon gecancelt, deshalb ein eigener,
            // kurzlebiger Scope (kein runBlocking auf Main).
            closingScope.launch {
                try {
                    shadowSessionRecorder.endSession()
                } finally {
                    closingScope.cancel()
                }
            }
        }

        /**
         * Gewichtseingabe -> Long in der Train-Tab-Speicherkonvention
         * (kg x 1_000_000), kommatolerant ("92,5" == "92.5").
         * Ungueltige Eingaben liefern null statt zu werfen, damit UI-Grenzen
         * (canLog, logSet, adjustWeight) damit arbeiten koennen.
         *
         * Bewusst NICHT [WorkoutMath.roundKgInputToMilliKg]: die
         * Domain-Fassade rechnet x1000 (Gramm), der Train-Pfad
         * (FlatSet.volumeKg, TrainScreen-Anzeige, Altbestand in der DB) mit
         * x1_000_000. Ein Wechsel der Stelle wuerde Bestandsdaten stumm um
         * den Faktor 1000 verkleinern — die Vereinheitlichung der beiden
         * Konventionen ist eine eigene Migrationsaufgabe.
         */
        private fun parseWeightMilliKg(input: String): Long? =
            runCatching {
                BigDecimal(input.trim().replace(',', '.'))
                    .multiply(BigDecimal(MILLIKG_PER_KG))
                    .setScale(0, java.math.RoundingMode.HALF_UP)
                    .longValueExact()
            }.getOrNull()

        /**
         * Millikilogramm als Eingabetext: ganze Werte ohne Nachkommastellen
         * ("20"), gebrochene schlank ("22.5") — nie ein Double-Artefakt.
         */
        private fun formatMilliKgForInput(milliKg: Long): String =
            BigDecimal(milliKg)
                .divide(BigDecimal(MILLIKG_PER_KG))
                .stripTrailingZeros()
                .toPlainString()

        private companion object {
            const val TICK_MS = 250L

            /** Samples shown in the live waveform (~4 s at 50 Hz). */
            const val WAVEFORM_WINDOW = 200

            /**
             * RC-11: Mindestabstand zweier Waveform-Veroeffentlichungen.
             * 33 ms entsprechen ~30 Bildern/s — schneller sieht niemand.
             */
            const val WAVEFORM_PUBLISH_INTERVAL_MS = 33L

            /** Logcat tag for the confirmed-vs-counted diff (DoD 11b). */
            const val SHADOW_TAG = "FlowRepShadow"

            /** Umbauplan Phase 10.5: sinnvolle Eingabegrenzen. */
            const val MILLIKG_PER_KG = 1_000_000
            const val MAX_REASONABLE_WEIGHT_MILLIKG = 1_000L * MILLIKG_PER_KG
            const val MAX_REASONABLE_REPS = 500

            /**
             * P2-Fix #19: erlaubte Abweichung zwischen bestaetigter Rep-Zahl
             * und Autokorrelations-Schaetzung. 2 ist bewusst tolerant: die
             * letzte Wiederholung ist am Set-Ende oft unvollstaendig und
             * Tempowechsel innerhalb des Satzes verschieben die Periode.
             */
            const val MAX_PLAUSIBILITY_DEVIATION = 2
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
