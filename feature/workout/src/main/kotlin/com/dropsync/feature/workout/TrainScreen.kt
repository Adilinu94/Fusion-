package com.dropsync.feature.workout

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dropsync.core.designsystem.component.BrandButtonGhost
import com.dropsync.core.designsystem.component.BrandButtonPrimary
import com.dropsync.core.designsystem.component.FlowRepPrimaryButton
import com.dropsync.core.designsystem.component.FlowRepSectionHeader
import com.dropsync.core.designsystem.component.FlowRepSurface
import com.dropsync.core.designsystem.theme.isWide
import com.dropsync.core.designsystem.theme.rememberAccentTextColor
import com.dropsync.core.designsystem.theme.rememberWindowWidthSizeClass
import com.dropsync.core.model.RestMode
import com.dropsync.domain.sensor.ActiveSetPhase
import com.dropsync.domain.sensor.RepRejectionReason
import com.dropsync.domain.sensor.SensorConnectionState
import com.dropsync.domain.sensor.SensorErrorReason
import com.dropsync.domain.sensor.SetDiagnostics
import com.dropsync.domain.sensor.SignalQuality
import com.dropsync.domain.sensor.calibration.ProfileLearningEvent
import com.dropsync.domain.timer.DropLandingPlanner
import com.dropsync.domain.timer.DropSyncMode
import com.dropsync.domain.timer.DropSyncState
import com.dropsync.domain.timer.TimerMode
import com.dropsync.domain.timer.TimerStatus
import com.dropsync.domain.timer.TimingConfidence
import com.dropsync.domain.workout.ExerciseInfo
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Train-Tab (FlowRep-Design Phase 2): flaches Satz-Log ohne Session.
 * Uebungs-Chip, Gewicht +/-2.5, Rep-Eingabe, Satz speichern, PR-Volumen.
 */
@Composable
fun TrainScreen(
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
    onOpenCalibration: (exerciseId: Long, deviceId: String) -> Unit = { _, _ -> },
    onOpenLibrary: () -> Unit = {},
    /**
     * B-ARCH-2 (P2-17, Nutzerentscheidung "Verdrahten"): Einstieg in den
     * standalone Resttimer (`:feature:timer`, Route `timer`). Die App reicht
     * die Navigation hierher — das Feature importiert kein anderes Feature
     * (Modulregel 3.2/4, Architekturtest).
     */
    onOpenTimer: () -> Unit = {},
    /**
     * B4-Shell-Snackbar (Befund 3.14): Fehler beim Satz-Loggen landen im
     * zentralen Host der Shell statt stumm zu verschwinden. Optional,
     * damit Previews ohne Shell funktionieren.
     */
    snackbarHostState: SnackbarHostState? = null,
    viewModel: TrainViewModel = hiltViewModel(),
) {
    val exercises by viewModel.exercises.collectAsStateWithLifecycle()
    val selectedExercise by viewModel.selectedExercise.collectAsStateWithLifecycle()
    val lastSet by viewModel.lastSet.collectAsStateWithLifecycle()
    val maxVolumeKg by viewModel.maxVolumeKg.collectAsStateWithLifecycle()
    val recentSets by viewModel.recentSets.collectAsStateWithLifecycle()
    val weightInput by viewModel.weightInput.collectAsStateWithLifecycle()
    val repsInput by viewModel.repsInput.collectAsStateWithLifecycle()
    val timerState by viewModel.timerState.collectAsStateWithLifecycle()
    // C3 (5.3): Pausendauer/Musikmodus je Uebung, Bereitschaft und Ducking.
    val restSeconds by viewModel.restSeconds.collectAsStateWithLifecycle()
    val restMode by viewModel.restMode.collectAsStateWithLifecycle()
    val dropAutoReadiness by viewModel.dropAutoReadiness.collectAsStateWithLifecycle()
    val restDuckDb by viewModel.restDuckDb.collectAsStateWithLifecycle()
    // C15 (PR-3): der wirksame DropSync-Schalter fuer die laufende Pause.
    val effectiveDropAuto by viewModel.effectiveDropAuto.collectAsStateWithLifecycle()
    val sensorConnection by viewModel.sensorConnection.collectAsStateWithLifecycle()
    val connectedDeviceId by viewModel.connectedDeviceId.collectAsStateWithLifecycle()
    val sensorError by viewModel.sensorError.collectAsStateWithLifecycle()
    val waveform by viewModel.waveform.collectAsStateWithLifecycle()
    val lastPeakMs by viewModel.lastPeakMs.collectAsStateWithLifecycle()
    val setPhase by viewModel.setPhase.collectAsStateWithLifecycle()
    val countdownSeconds by viewModel.countdownSeconds.collectAsStateWithLifecycle()
    val liveCountedReps by viewModel.liveCountedReps.collectAsStateWithLifecycle()
    val plausibilityHint by viewModel.plausibilityHint.collectAsStateWithLifecycle()
    val recountSuggestion by viewModel.recountSuggestion.collectAsStateWithLifecycle()
    val countedZero by viewModel.countedZero.collectAsStateWithLifecycle()
    // RC-5: Quelle der Rep-Zahl im Hero (AUTO / KORRIGIERT / MANUELL / GETRENNT).
    val repsSource by viewModel.repsSource.collectAsStateWithLifecycle()
    val hasCalibration by viewModel.hasCalibration.collectAsStateWithLifecycle()
    val signalQuality by viewModel.signalQuality.collectAsStateWithLifecycle()
    // P1-10: sichtbarer DropSync-Zustand (Design 4.5) in der Rest-Konsole.
    val dropSyncState by viewModel.dropSyncState.collectAsStateWithLifecycle()
    // Herzfrequenz-Badge (Herzfrequenz-Plan Phase 2): Health-Connect-Zustand.
    val heartRateAvailability by viewModel.heartRateAvailability.collectAsStateWithLifecycle()
    val heartRateSample by viewModel.heartRateSample.collectAsStateWithLifecycle()

    var showCreateDialog by remember { mutableStateOf(false) }
    // C3 (5.3): Dialog fuer Pausendauer und Musikmodus der Uebung.
    var showRestPrefDialog by remember { mutableStateOf(false) }

    // Befund 3.14 / P1-12: Einmal-Ereignisse (Log-Fehler, Lernpfad) als
    // Shell-Snackbar — ausgelagert, damit der Screen ueberschaubar bleibt.
    // P2-17/RC-7: der Satz-Report nach dem Stop laeuft ueber denselben Host.
    TrainEventSnackbars(
        snackbarHostState = snackbarHostState,
        setLogEvents = viewModel.setLogEvents,
        onUndoSetLog = { viewModel.undoLastSet() },
        learningEvent = viewModel.learningEvent,
        errorEvent = viewModel.errorEvent,
        setReport = viewModel.setReport,
    )

    // Health-Connect-Berechtigung (Plan 3.2): der generische Contract kommt
    // injiziert aus :data:health (Hilt-Qualifier); das Feature kennt kein
    // SDK-Typ.
    val healthPermissionLauncher =
        rememberLauncherForActivityResult(viewModel.healthPermissionContract) {
            viewModel.refreshHeartRate()
        }

    // POST_NOTIFICATIONS mit Kontext (B3): keine kommentarlose Anfrage mehr —
    // eine Karte erklaert den Nutzen (Timer mit Skip/+15 s bei dunklem
    // Bildschirm), der Nutzer entscheidet per Button. Ablehnen -> Xiaomi-
    // Fallback (Service laeuft, Cues feuern), „Spaeter" blendet fuer die
    // Sitzung aus.
    val context = LocalContext.current
    var notificationsAllowed by remember {
        mutableStateOf(
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    var notificationsDismissed by remember { mutableStateOf(false) }
    val notificationPermissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            notificationsAllowed = granted
        }

    // C2: Auf Tablet/Landscape bekommt der Inhalt mehr Rand, damit die
    // Eingabe-Karten nicht ueber die ganze Breite laufen (die Sektionen
    // bringen selbst 16 dp mit).
    val wide = rememberWindowWidthSizeClass().isWide

    // 2026-09-27 (Befund 12.3, UI-Hebel 2): der Bildschirm ging mitten
    // im Training aus. Beim Gewicht-Einstellen hat der Nutzer beide
    // Haende voll (Stange, Teller) und liegt 90 Sekunden in der Pause —
    // nach 30 s ohne Eingabe dimmt Android den Bildschirm, und nach
    // 60 s ist er aus. Der Nutzer muss dann mit nassen Haenden
    // entsperren, **waehrend er auf der Liege liegt**.
    //
    // Nur im Train-Tab und nur solange der Bildschirm sichtbar ist:
    // `DisposableEffect` raeumt beim Verlassen garantiert auf, damit
    // kein anderer Screen den Bildschirm offen haelt.
    val view = LocalView.current
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }

    Column(
        modifier =
            modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(
                    start = if (wide) 24.dp else 0.dp,
                    end = if (wide) 24.dp else 0.dp,
                    top = contentPadding.calculateTopPadding() + 8.dp,
                    bottom = contentPadding.calculateBottomPadding() + 24.dp,
                ),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (!notificationsAllowed && !notificationsDismissed) {
            NotificationRationaleCard(
                onActivate = { notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) },
                onLater = { notificationsDismissed = true },
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
        // Übungs-Chips + "Neue Übung"
        ExerciseChipRow(
            exercises = exercises,
            selectedId = selectedExercise?.id,
            onSelect = { viewModel.selectExercise(it) },
            onCreateNew = { showCreateDialog = true },
            onOpenLibrary = onOpenLibrary,
        )

        // RC-8 / Design 8.1: Kalibrier-Status direkt an der Uebungszeile.
        // Zeigt, ob die gewaehlte Uebung fuer den verbundenen Chip kalibriert
        // ist, und fuehrt von dort in den Wizard (bzw. zur Neu-Kalibrierung).
        CalibrationStatusEntry(
            deviceId = connectedDeviceId,
            exerciseId = selectedExercise?.id,
            connection = sensorConnection,
            hasCalibration = hasCalibration,
            onOpenCalibration = onOpenCalibration,
            modifier = Modifier.padding(horizontal = 16.dp),
        )

        // A.4/RC-5: Der Sensor-Status ist eine Kopfzeile (Chip + Qualitaet +
        // Puls), keine konkurrierende Karte unter der Eingabe. Der Fehlertext
        // steht direkt unter dem Chip (Design 8.1).
        SensorHeaderRow(
            connection = sensorConnection,
            sensorError = sensorError,
            signalQuality = signalQuality,
            heartRateAvailability = heartRateAvailability,
            heartRateBpm = heartRateSample,
            onRequestHeartRatePermission = {
                healthPermissionLauncher.launch(viewModel.heartRatePermissions)
            },
            onHeartRateResume = { viewModel.refreshHeartRate() },
            onConnect = { viewModel.connectSensor() },
            onDisconnect = { viewModel.disconnectSensor() },
            modifier = Modifier.padding(horizontal = 16.dp),
        )

        // P1-10/A.4: Die Konsole haengt an genau einem Modus (statt an einer
        // Kette von Booleans); pro Zustand gibt es genau eine Primaeraktion.
        // Restzeit und DropSync-Plan kommen aus Timer bzw. Koordinator.
        val timerRemainingMs = timerState.remainingMs
        val timerMode = timerState.session?.mode
        val restActive =
            timerState.status == TimerStatus.PREPARING ||
                timerState.status == TimerStatus.RUNNING || timerState.status == TimerStatus.PAUSED
        when (
            workoutConsoleMode(
                hasExercise = selectedExercise != null,
                restActive = restActive,
                dropLanded = dropSyncState is DropSyncState.Landed,
            )
        ) {
            WorkoutConsoleMode.IDLE -> {
                EmptyConsoleHero(modifier = Modifier.padding(horizontal = 16.dp))
            }

            WorkoutConsoleMode.SET_ENTRY -> {
                SetEntryHero(
                    exerciseName = selectedExercise?.displayName,
                    weightKg = weightInput,
                    lastWeightKg = lastSet?.let { it.weightMilliKg / 1_000_000.0 },
                    reps = repsInput,
                    // 2026-09-27 (UI-Hebel 1): der letzte Reps-Stand als
                    // Platzhalter, genau wie beim Gewicht. Der Nutzer
                    // tippt sonst bei jedem Satz dieselbe Zahl neu.
                    lastReps = lastSet?.reps,
                    repsSource = repsSource,
                    setPhase = setPhase,
                    countdownSeconds = countdownSeconds,
                    liveCountedReps = liveCountedReps,
                    streaming = sensorConnection == SensorConnectionState.STREAMING,
                    signalQuality = signalQuality,
                    hasCalibration = hasCalibration,
                    waveform = waveform,
                    lastPeakMs = lastPeakMs,
                    plausibilityHint = plausibilityHint,
                    recountSuggestion = recountSuggestion,
                    countedZero = countedZero,
                    maxVolumeKg = maxVolumeKg,
                    restSeconds = restSeconds,
                    restMode = restMode,
                    canLog = viewModel.canLog,
                    onWeightChange = { viewModel.setWeight(it) },
                    onIncrement = { viewModel.adjustWeight(2.5) },
                    onDecrement = { viewModel.adjustWeight(-2.5) },
                    onRepsChange = { viewModel.setReps(it) },
                    onAdoptRecount = { viewModel.adoptRecount() },
                    onStartSet = { viewModel.startCountedSet() },
                    onStopSet = { viewModel.stopCountedSet() },
                    onLogSet = { viewModel.logSet() },
                    onOpenRestPref = { showRestPrefDialog = true },
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }

            WorkoutConsoleMode.REST_RUNNING,
            WorkoutConsoleMode.GO_CUE,
            -> {
                RestConsole(
                    remainingMs = timerRemainingMs,
                    mode = timerMode,
                    timerStatus = timerState.status,
                    dropSyncState = dropSyncState,
                    restDuckDb = restDuckDb,
                    dropAutoChecked = effectiveDropAuto,
                    onAddTime = { viewModel.addRestTime() },
                    onSkip = { viewModel.skipRest() },
                    onCancelPlan = { viewModel.cancelDropPlan() },
                    onFinish = { viewModel.finishExercise() },
                    onOpenTimer = onOpenTimer,
                    onSetRestDuckDb = { viewModel.setRestDuckDb(it) },
                    onSetDropAuto = { viewModel.setDropAutoForCurrentRest(it) },
                    lastSetText =
                        lastSet?.let {
                            stringResource(R.string.train_rest_last_set, it.weightMilliKg / 1_000_000.0, it.reps)
                        },
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
        }

        // Mini-Verlauf (letzte 5 Saetze)
        if (recentSets.isNotEmpty()) {
            FlowRepSurface(
                modifier = Modifier.padding(horizontal = 16.dp),
                contentPadding = PaddingValues(20.dp),
            ) {
                FlowRepSectionHeader(title = stringResource(R.string.train_recent_sets))
                Spacer(Modifier.height(12.dp))
                recentSets.take(5).forEachIndexed { index, set ->
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text =
                                stringResource(
                                    R.string.train_set_index,
                                    (index + 1).toString().padStart(2, '0'),
                                ),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.width(40.dp),
                        )
                        Text(
                            text = stringResource(R.string.train_set_reps, set.reps),
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text =
                                stringResource(
                                    R.string.train_set_weight,
                                    set.weightMilliKg / 1_000_000.0,
                                ),
                            style = MaterialTheme.typography.titleMedium,
                            color = rememberAccentTextColor(),
                        )
                    }
                }
            }
        }
    }

    if (showCreateDialog) {
        CreateExerciseDialog(
            onDismiss = { showCreateDialog = false },
            onCreate = { name ->
                viewModel.createExercise(name)
                showCreateDialog = false
            },
        )
    }

    if (showRestPrefDialog) {
        RestPrefDialog(
            restSeconds = restSeconds,
            restMode = restMode,
            readiness = dropAutoReadiness,
            onDismiss = { showRestPrefDialog = false },
            onConfirm = { seconds, mode ->
                viewModel.setRestPref(seconds, mode)
                showRestPrefDialog = false
            },
        )
    }
}

/**
 * RC-8: Sichtbarkeits-Entscheidung der Kalibrier-Zeile (ausgelagert, damit
 * [TrainScreen] nicht weiter an Komplexitaet waechst): nur mit gewaehlter
 * Uebung, verbundenem Chip und erreichbarem Geraet.
 */
@Composable
private fun CalibrationStatusEntry(
    deviceId: String?,
    exerciseId: Long?,
    connection: SensorConnectionState,
    hasCalibration: Boolean,
    onOpenCalibration: (exerciseId: Long, deviceId: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (deviceId == null || exerciseId == null) return
    if (!chipReadyForCalibration(connection)) return
    CalibrationStatusLine(
        hasCalibration = hasCalibration,
        deviceId = deviceId,
        onOpenCalibration = { onOpenCalibration(exerciseId, deviceId) },
        modifier = modifier,
    )
}

/** Chip verbunden (bereit oder schon am Streamen) — Basis fuer Kalibrier-Einstiege. */
private fun chipReadyForCalibration(connection: SensorConnectionState): Boolean =
    connection == SensorConnectionState.CONNECTED || connection == SensorConnectionState.STREAMING

/**
 * RC-8 / Design 8.1: Kalibrier-Status an der Uebungszeile. Ein Haekchen plus
 * "Kalibriert fuer FlowRep #XXXX — neu kalibrieren" oeffnet den Wizard; ohne
 * Profil heisst der Eintrag "noch nicht kalibriert — jetzt kalibrieren".
 *
 * Die Kurz-Kennung kommt aus den letzten vier Zeichen der Geraeteadresse
 * (echte Daten, keine erfundene Seriennummer).
 */
@Composable
private fun CalibrationStatusLine(
    hasCalibration: Boolean,
    deviceId: String,
    onOpenCalibration: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (hasCalibration) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = null,
                tint = rememberAccentTextColor(),
                modifier = Modifier.size(16.dp),
            )
        }
        TextButton(
            onClick = onOpenCalibration,
            modifier = Modifier.heightIn(min = 48.dp),
        ) {
            Text(
                stringResource(
                    if (hasCalibration) {
                        R.string.calibration_status_recalibrate
                    } else {
                        R.string.calibration_status_missing
                    },
                    shortDeviceLabel(deviceId),
                ),
            )
        }
    }
}

/**
 * RC-8: Kurz-Kennung des verbundenen Chips fuer die Kalibrier-Zeile. Aus der
 * BLE-Adresse werden die letzten vier Hex-Zeichen genommen ("AA:BB:CC:DD:EE:FF"
 * -> "#EEFF"); fehlt verwertbarer Text, bleibt die Kennung leer und die Zeile
 * nennt nur "FlowRep".
 */
internal fun shortDeviceLabel(deviceId: String): String {
    val compact = deviceId.filter { it.isLetterOrDigit() }
    val tail = compact.takeLast(4).uppercase()
    return if (tail.isEmpty()) "" else "#$tail"
}

@Composable
private fun ExerciseChipRow(
    exercises: List<ExerciseInfo>,
    selectedId: Long?,
    onSelect: (ExerciseInfo) -> Unit,
    onCreateNew: () -> Unit,
    onOpenLibrary: () -> Unit = {},
) {
    val chipListState = remember { LazyListState() }
    LaunchedEffect(Unit) {
        chipListState.scrollToItem(0)
    }
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        state = chipListState,
    ) {
        items(exercises, key = { it.id }) { exercise ->
            FilterChip(
                selected = selectedId == exercise.id,
                onClick = { onSelect(exercise) },
                label = { Text(exercise.displayName) },
                modifier = Modifier.heightIn(min = 48.dp),
            )
        }
        item(key = "create_new") {
            AssistChip(
                onClick = onCreateNew,
                label = { Text(stringResource(R.string.train_new_exercise_chip)) },
                modifier = Modifier.heightIn(min = 48.dp),
            )
        }
        // Schritt 7: Die Bibliothek ist der Ort fuer Uebungs-Pflege und Ziele
        // (Entscheidung 13) — ein Chip Abstand von der Schnellanlage.
        item(key = "open_library") {
            AssistChip(
                onClick = onOpenLibrary,
                label = { Text(stringResource(R.string.library_title)) },
                modifier = Modifier.heightIn(min = 48.dp),
            )
        }
    }
}

/**
 * Befund 3.14 / P1-12: Einmal-Ereignisse der Train-UI (Satz-Log mit Undo,
 * Lernpfad-Ergebnis) als Snackbar. Ausgelagert, damit [TrainScreen] nicht
 * weiter an Komplexitaet waechst; die Texte liegen hier an einem Ort.
 *
 * P2-17/RC-7: nach jedem gestoppten Satz erscheint zusaetzlich der
 * Satz-Report (erkannt, Rate, Aussetzer, ZuPT, klassifizierte Ablehnungen).
 * A1/T-1: "Satz gespeichert" traegt die Aktion "Rueckgaengig".
 */
@Composable
internal fun TrainEventSnackbars(
    snackbarHostState: SnackbarHostState?,
    setLogEvents: Flow<SetLogEvent>,
    onUndoSetLog: () -> Unit,
    learningEvent: Flow<ProfileLearningEvent>,
    errorEvent: Flow<TrainErrorEvent>,
    setReport: Flow<SetDiagnostics>,
) {
    val logErrorMessage = stringResource(R.string.train_log_error)
    val setSavedMessage = stringResource(R.string.workout_set_saved)
    val setUndoneMessage = stringResource(R.string.workout_set_undone)
    val undoLabel = stringResource(R.string.workout_undo)
    val learningRefinedTemplate = stringResource(R.string.train_learning_refined)
    val learningRolledBack = stringResource(R.string.train_learning_rolled_back)
    val learningSkippedImplausible = stringResource(R.string.train_learning_skipped_implausible)
    val learningSkippedUnreliable = stringResource(R.string.train_learning_skipped_unreliable)
    val learningSkippedNotReproducible =
        stringResource(R.string.train_learning_skipped_not_reproducible)
    // 2026-09-27 (UI-Hebel 3): nur noch die Aussetzer-Anzahl als Template.
    // Rate, ZuPT und die Ablehnungs-Labels werden im Snackbar nicht mehr
    // gebraucht — sie stehen im Diagnose-Panel. Die Resources bleiben
    // erhalten, weil das Panel sie weiterhin liest.
    val reportGapsTemplate = stringResource(R.string.train_set_report_gaps)
    LaunchedEffect(
        snackbarHostState,
        setSavedMessage,
        setUndoneMessage,
        undoLabel,
        logErrorMessage,
        onUndoSetLog,
    ) {
        if (snackbarHostState == null) return@LaunchedEffect
        setLogEvents.collect { event ->
            when (event) {
                is SetLogEvent.Logged -> {
                    val result =
                        snackbarHostState.showSnackbar(
                            message = setSavedMessage,
                            actionLabel = undoLabel,
                            duration = SnackbarDuration.Short,
                        )
                    if (result == SnackbarResult.ActionPerformed) onUndoSetLog()
                }

                is SetLogEvent.Undone -> {
                    snackbarHostState.showSnackbar(setUndoneMessage)
                }

                SetLogEvent.LogFailed -> {
                    snackbarHostState.showSnackbar(logErrorMessage)
                }
            }
        }
    }
    LaunchedEffect(
        snackbarHostState,
        learningRefinedTemplate,
        learningRolledBack,
        learningSkippedImplausible,
        learningSkippedUnreliable,
        learningSkippedNotReproducible,
    ) {
        if (snackbarHostState == null) return@LaunchedEffect
        learningEvent.collect { event ->
            val message =
                when (event) {
                    is ProfileLearningEvent.Refined -> {
                        String.format(Locale.ROOT, learningRefinedTemplate, event.revision)
                    }

                    ProfileLearningEvent.RolledBack -> {
                        learningRolledBack
                    }

                    ProfileLearningEvent.SkippedImplausible -> {
                        learningSkippedImplausible
                    }

                    ProfileLearningEvent.SkippedUnreliable -> {
                        learningSkippedUnreliable
                    }

                    ProfileLearningEvent.SkippedNotReproducible -> {
                        learningSkippedNotReproducible
                    }
                }
            snackbarHostState.showSnackbar(message)
        }
    }
    TrainErrorSnackbars(snackbarHostState = snackbarHostState, errorEvent = errorEvent)
    // 2026-09-27 (Befund 12.3, UI-Hebel 3): der Snackbar-Report stand
    // als "12 erkannt · Rate 51,3 Hz · 2 Aussetzer · 4 ZuPT · 3
    // abgelehnt (Beschleunigung 2, Template 1)". Fuenf Snackbar-Quellen
    // konkurrierten auf **einem** Host (Satz gespeichert + Undo,
    // Learning-Event, Train-Fehler, DropSync-Skip, Set-Report); bei einem
    // realistischen Satz-Tempo von 15 s wurde regelmaessig eine verdraengt —
    // und die realistischste ausgerechnet der **Undo**-Knopf.
    //
    // Nur **Aussetzer** sind fuer einen Trainierenden handlungsrelevant
    // (Paketverluefe = Luecken in der Messung). Rate, ZuPT und die
    // Ablehnungsmechanismen sind Diagnose und stehen unveraendert im
    // Diagnose-Panel (`SettingsScreen.kt`, `DiagnosticsLastSetSection`).
    LaunchedEffect(
        snackbarHostState,
        reportGapsTemplate,
    ) {
        if (snackbarHostState == null) return@LaunchedEffect
        setReport.collect { report ->
            val concise = SetReportText.formatConcise(report, reportGapsTemplate)
            if (concise != null) {
                snackbarHostState.showSnackbar(
                    message = concise,
                    duration = SnackbarDuration.Short,
                )
            }
        }
    }
}

/**
 * T-10/S-7: sichtbare Fehler der Train-Pfade als Snackbar. Eigene Funktion,
 * damit [TrainEventSnackbars] nicht weiter waechst (Detekt: LongMethod).
 */
@Composable
private fun TrainErrorSnackbars(
    snackbarHostState: SnackbarHostState?,
    errorEvent: Flow<TrainErrorEvent>,
) {
    val errorExerciseCreate = stringResource(R.string.train_error_exercise_create)
    val errorProfileLoad = stringResource(R.string.train_error_profile_load)
    val errorLearningSave = stringResource(R.string.train_error_learning_save)
    val errorRestPrefSave = stringResource(R.string.train_error_rest_pref_save)
    val errorUndo = stringResource(R.string.train_error_undo)
    val errorHistory = stringResource(R.string.train_error_history)
    // 2026-09-27, Befund 5.10: die drei Voraussetzungen fuer die
    // Live-Zaehlung wurden mit stillem `return` geprueft. Jetzt sieht der
    // Nutzer, **woran** es liegt, und kann handeln.
    val errorNoChip = stringResource(R.string.train_error_count_no_chip)
    val errorNotStreaming = stringResource(R.string.train_error_count_not_streaming)
    val errorNoCalibration = stringResource(R.string.train_error_count_no_calibration)
    // Befund 5.12: Satz gespeichert, aber die Pause startete nicht.
    val errorRestStart = stringResource(R.string.train_error_rest_start_failed)
    LaunchedEffect(
        snackbarHostState,
        errorExerciseCreate,
        errorProfileLoad,
        errorLearningSave,
        errorRestPrefSave,
        errorUndo,
        errorHistory,
        errorNoChip,
        errorNotStreaming,
        errorNoCalibration,
        errorRestStart,
    ) {
        if (snackbarHostState == null) return@LaunchedEffect
        errorEvent.collect { event ->
            val message =
                when (event) {
                    TrainErrorEvent.ExerciseCreationFailed -> errorExerciseCreate
                    TrainErrorEvent.ProfileLoadFailed -> errorProfileLoad
                    TrainErrorEvent.LearningSaveFailed -> errorLearningSave
                    TrainErrorEvent.RestPrefSaveFailed -> errorRestPrefSave
                    TrainErrorEvent.UndoFailed -> errorUndo
                    TrainErrorEvent.HistoryLoadFailed -> errorHistory
                    TrainErrorEvent.CountBlockedNoChip -> errorNoChip
                    TrainErrorEvent.CountBlockedNotStreaming -> errorNotStreaming
                    TrainErrorEvent.CountBlockedNoCalibration -> errorNoCalibration
                    TrainErrorEvent.RestTimerStartFailed -> errorRestStart
                }
            snackbarHostState.showSnackbar(message)
        }
    }
}

@Composable
private fun CreateExerciseDialog(
    onDismiss: () -> Unit,
    onCreate: (String) -> Unit,
) {
    var name by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.library_new_exercise)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.train_exercise_name)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onCreate(name) },
                enabled = name.isNotBlank(),
            ) {
                Text(stringResource(R.string.library_create))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.workout_cancel))
            }
        },
    )
}

/**
 * Benachrichtigungs-Karte mit Kontext (Ausbauplan B3). Erklaert den Nutzen,
 * bevor das System fragt — statt der frueheren kommentarlosen Anfrage beim
 * ersten Compose. „Spaeter" blendet fuer die Sitzung aus.
 */
@Composable
private fun NotificationRationaleCard(
    onActivate: () -> Unit,
    onLater: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FlowRepSurface(modifier = modifier) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.train_notifications_title),
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                text = stringResource(R.string.train_notifications_desc),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 4.dp),
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FlowRepPrimaryButton(
                    text = stringResource(R.string.train_notifications_activate),
                    onClick = onActivate,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onLater) {
                    Text(stringResource(R.string.train_notifications_later))
                }
            }
        }
    }
}
