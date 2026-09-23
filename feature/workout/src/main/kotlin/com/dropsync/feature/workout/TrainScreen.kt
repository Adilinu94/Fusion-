package com.dropsync.feature.workout

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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
                    countedZero = countedZero,
                    maxVolumeKg = maxVolumeKg,
                    restSeconds = restSeconds,
                    restMode = restMode,
                    canLog = viewModel.canLog,
                    onWeightChange = { viewModel.setWeight(it) },
                    onIncrement = { viewModel.adjustWeight(2.5) },
                    onDecrement = { viewModel.adjustWeight(-2.5) },
                    onRepsChange = { viewModel.setReps(it) },
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

@Composable
private fun WeightInput(
    weightKg: String,
    lastWeightKg: Double?,
    onWeightChange: (String) -> Unit,
    onIncrement: () -> Unit,
    onDecrement: () -> Unit,
) {
    val decrementDescription = stringResource(R.string.a11y_weight_decrement)
    val incrementDescription = stringResource(R.string.a11y_weight_increment)
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = stringResource(R.string.train_weight_label),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = weightKg,
            onValueChange = onWeightChange,
            placeholder = {
                lastWeightKg?.let {
                    Text(stringResource(R.string.train_weight_last, it), maxLines = 1)
                }
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            singleLine = true,
            textStyle = MaterialTheme.typography.displaySmall.copy(textAlign = TextAlign.Center),
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 72.dp),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            BrandButtonGhost(
                text = stringResource(R.string.train_weight_decrement),
                onClick = onDecrement,
                modifier =
                    Modifier
                        .weight(1f)
                        .semantics {
                            contentDescription = decrementDescription
                        },
            )
            BrandButtonGhost(
                text = stringResource(R.string.train_weight_increment),
                onClick = onIncrement,
                modifier =
                    Modifier
                        .weight(1f)
                        .semantics {
                            contentDescription = incrementDescription
                        },
            )
        }
    }
}

/**
 * Zweitmeinungs-Hinweis (Umbauplan 2026-09-04 Phase 7).
 *
 * Formuliert absichtlich als Frage und nicht als Korrektur: die
 * Autokorrelation kann die Zahl nicht exakt bestimmen (Randeffekte,
 * Tempowechsel innerhalb des Satzes), sie erkennt aber gut, ob im Signal
 * ueberhaupt eine passende Periodik steckt. Die Entscheidung bleibt beim
 * Nutzer — auch weil nur eine aktive Korrektur als unabhaengige Wahrheit
 * zaehlt (D3-Regel, ADR-0014).
 *
 * `liveRegion` ist Assertive und nicht Polite: der Hinweis ist nur bis zum
 * Loggen gueltig, eine hoefliche Ansage koennte bis dahin unterdrueckt
 * bleiben.
 */
@Composable
private fun PlausibilityHintRow(hint: PlausibilityHint) {
    val text =
        stringResource(
            R.string.plausibility_hint,
            hint.countedReps,
            hint.estimatedReps,
        )
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.tertiary,
        modifier =
            Modifier.semantics {
                liveRegion = LiveRegionMode.Assertive
                contentDescription = text
            },
    )
}

@Composable
private fun RepInput(
    reps: String,
    onRepsChange: (String) -> Unit,
) {
    val decrementDescription = stringResource(R.string.a11y_reps_decrement)
    val incrementDescription = stringResource(R.string.a11y_reps_increment)
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = stringResource(R.string.train_reps_label),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            BrandButtonGhost(
                text = "−",
                onClick = {
                    val next = ((reps.toIntOrNull() ?: 0) - 1).coerceAtLeast(0)
                    onRepsChange(next.toString())
                },
                modifier =
                    Modifier
                        .size(64.dp)
                        .semantics { contentDescription = decrementDescription },
            )
            OutlinedTextField(
                value = reps,
                onValueChange = onRepsChange,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                textStyle = MaterialTheme.typography.displayMedium.copy(textAlign = TextAlign.Center),
                modifier =
                    Modifier
                        .weight(1f)
                        .heightIn(min = 80.dp),
            )
            BrandButtonGhost(
                text = "+",
                onClick = {
                    val next = (reps.toIntOrNull() ?: 0) + 1
                    onRepsChange(next.toString())
                },
                modifier =
                    Modifier
                        .size(64.dp)
                        .semantics { contentDescription = incrementDescription },
            )
        }
        Text(
            text = stringResource(R.string.train_reps_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Rest-timer pill inside the train card (Phase 3 step 4): countdown plus
 * End-rest / finish-exercise controls. Both actions cancel the timer immediately
 * (design rule step 5).
 *
 * Tap auf die Countdown-Anzeige oeffnet den standalone Resttimer
 * (`:feature:timer`): dort gibt es Pause/Weiter und den grossen Ring, die
 * Pille hier bleibt kompakt. Beide treiben dieselbe geteilte TimerEngine —
 * der Zustand bleibt konsistent, egal wo gesteuert wird.
 *
 * P1-10: Die Konsole ist der gemeinsame Hero fuer Normal-Rest und DropSync
 * (A.4): Kopf `REST`/`DROPSYNC`, grosse Zeit, darunter Track · Marker ·
 * "Ziel in mm:ss" plus Statuschips, kontextabhaengige Aktionen und nach der
 * Landung ein kurzes GO-Overlay.
 */
@Composable
internal fun RestConsole(
    remainingMs: Long,
    mode: TimerMode?,
    timerStatus: TimerStatus,
    dropSyncState: DropSyncState,
    restDuckDb: Double,
    dropAutoChecked: Boolean,
    onAddTime: () -> Unit,
    onSkip: () -> Unit,
    onCancelPlan: () -> Unit,
    onFinish: () -> Unit,
    onOpenTimer: () -> Unit,
    onSetRestDuckDb: (Double) -> Unit,
    onSetDropAuto: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    // MP-6: `+15 s` ist bei einem DropSync-Rest wirkungslos (die Dauer folgt
    // dem Marker). Der Knopf wird dort nicht angeboten, statt still nichts
    // zu tun.
    val canExtend = mode != TimerMode.DROPSYNC
    val canCancelPlan = dropSyncCanCancelPlan(dropSyncState)
    // P1-10/A5: "GO" nach der Landung — kurz sichtbar, ohne Navigation.
    // Das Zuruecksetzen liegt im finally: wechselt der DropSync-Zustand
    // waehrend der Anzeige (Replan, Abbruch, neuer Plan), wird der Effekt
    // abgebrochen — ohne finally bliebe das Overlay haengen.
    var showGoOverlay by remember { mutableStateOf(false) }
    LaunchedEffect(dropSyncState) {
        if (dropSyncState is DropSyncState.Landed) {
            showGoOverlay = true
            try {
                delay(GO_OVERLAY_MS)
            } finally {
                showGoOverlay = false
            }
        } else {
            showGoOverlay = false
        }
    }
    FlowRepSurface(modifier = modifier.fillMaxWidth()) {
        Box {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                RestConsoleHeader(
                    dropSyncState = dropSyncState,
                    remainingMs = remainingMs,
                    onOpenTimer = onOpenTimer,
                )
                RestConsoleActions(
                    canExtend = canExtend,
                    canCancelPlan = canCancelPlan,
                    onAddTime = onAddTime,
                    onCancelPlan = onCancelPlan,
                    onSkip = onSkip,
                    onFinish = onFinish,
                )
                // C15 (PR-3): DropSync fuer die laufende Pause einschalten
                // (plant sofort); unter einer Minute nicht angeboten (PR-4).
                if (timerStatus == TimerStatus.RUNNING && mode == TimerMode.REST) {
                    RestDropAutoRow(
                        checked = dropAutoChecked,
                        blocked = remainingMs < DropLandingPlanner.MIN_DROP_AUTO_REST_MS,
                        onCheckedChange = onSetDropAuto,
                    )
                }
                // C3 (P-3/MP-9): Ducking der Pausenmusik am Ort — dieselbe
                // DSP-Quelle wie die Einstellungen (kein zweiter Wert).
                RestDuckRow(restDuckDb = restDuckDb, onSetRestDuckDb = onSetRestDuckDb)
            }
            if (showGoOverlay) {
                GoOverlay(
                    onDismiss = { showGoOverlay = false },
                    modifier = Modifier.matchParentSize(),
                )
            }
        }
    }
}

/**
 * P1-10: Kopf des Heros — Label (`REST`/`DROPSYNC`), `Track · Marker ·
 * Ziel in mm:ss`, Statuschips und die grosse Restzeit (Tap oeffnet den
 * Standalone-Timer).
 */
@Composable
private fun RestConsoleHeader(
    dropSyncState: DropSyncState,
    remainingMs: Long,
    onOpenTimer: () -> Unit,
) {
    // P1-10: Der Plan-Zustand des Koordinators faerbt Kopfzeile und Chips;
    // die Restzeit bleibt die grosse Zahl (eine Primaerinformation).
    val dropSyncActive = dropSyncState is DropSyncState.Planned || dropSyncState is DropSyncState.Armed
    val dropSyncLabel =
        when (dropSyncState) {
            is DropSyncState.Planned, is DropSyncState.Armed -> dropSyncState.headline()
            is DropSyncState.BestEffort -> stringResource(R.string.train_rest_dropsync_best_effort)
            is DropSyncState.Overridden -> stringResource(R.string.train_rest_dropsync_overridden)
            is DropSyncState.Failed -> stringResource(R.string.train_rest_dropsync_unavailable)
            else -> null
        }
    val targetMs = dropTargetRemainingMs(dropSyncState, remainingMs)
    Text(
        text =
            if (dropSyncActive) {
                stringResource(R.string.train_rest_label_dropsync)
            } else {
                stringResource(R.string.train_rest_label)
            },
        style = MaterialTheme.typography.labelMedium,
        color = rememberAccentTextColor(),
    )
    dropSyncLabel?.let { label ->
        val line =
            targetMs?.let {
                "$label · ${stringResource(R.string.train_rest_dropsync_target, formatRestRemaining(it))}"
            } ?: label
        // C5 (T-8): Die Zeile wird als ganzer Satz vorgelesen, nicht als
        // Zahlenkolonne ("DropSync: Track. Ziel in 1:27.").
        val a11yLine =
            targetMs?.let {
                stringResource(R.string.train_rest_dropsync_a11y, label, formatRestRemaining(it))
            } ?: label
        Text(
            text = line,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier =
                Modifier
                    .padding(top = 2.dp)
                    .semantics(mergeDescendants = true) { contentDescription = a11yLine },
        )
    }
    DropSyncStatusChips(dropSyncState)
    // C5 (T-8): Die grosse Restzeit bekommt eine gesprochene Zustandszeile
    // ("Pause laeuft, noch 1 Minute 27 Sekunden"), damit TalkBack nicht nur
    // "1:27" vorliest.
    val a11yRest = stringResource(R.string.train_rest_a11y_running, spokenRestDuration(remainingMs))
    Text(
        text = formatRestRemaining(remainingMs),
        style = MaterialTheme.typography.displayLarge,
        fontWeight = FontWeight.Bold,
        textAlign = TextAlign.Center,
        // onClickLabel statt eigener contentDescription: TalkBack liest
        // weiter die Restzeit vor und nennt zusaetzlich die Aktion. Eine
        // contentDescription wuerde die Zeit ersetzen.
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(vertical = 16.dp)
                .semantics { stateDescription = a11yRest }
                .clickable(
                    role = Role.Button,
                    onClickLabel = stringResource(R.string.train_rest_open_timer),
                ) { onOpenTimer() },
    )
}

/** C5: Restzeit als gesprochener Satz ("1 Minute 27 Sekunden"). */
@Composable
private fun spokenRestDuration(ms: Long): String {
    val totalSeconds = ms.coerceAtLeast(0L) / 1000
    val minutes = (totalSeconds / 60).toInt()
    val seconds = (totalSeconds % 60).toInt()
    return if (minutes > 0) {
        val minutePart = pluralStringResource(R.plurals.train_rest_spoken_minutes, minutes, minutes)
        val secondPart = pluralStringResource(R.plurals.train_rest_spoken_seconds, seconds, seconds)
        "$minutePart $secondPart"
    } else {
        pluralStringResource(R.plurals.train_rest_spoken_seconds, seconds, seconds)
    }
}

/**
 * P1-10: Aktionen des Heros — kontextabhaengig: `+15 s` nur bei REST,
 * `Plan abbrechen` nur bei einer Landung am Pausenende; `Pause beenden`
 * und `Uebung beenden` immer.
 */
@Composable
private fun RestConsoleActions(
    canExtend: Boolean,
    canCancelPlan: Boolean,
    onAddTime: () -> Unit,
    onCancelPlan: () -> Unit,
    onSkip: () -> Unit,
    onFinish: () -> Unit,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        if (maxWidth < 480.dp) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (canExtend) {
                    BrandButtonGhost(
                        text = stringResource(R.string.train_rest_add_long),
                        onClick = onAddTime,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (canCancelPlan) {
                    BrandButtonGhost(
                        text = stringResource(R.string.train_rest_cancel_plan),
                        onClick = onCancelPlan,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                FlowRepPrimaryButton(text = stringResource(R.string.train_rest_end), onClick = onSkip)
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (canExtend) {
                    BrandButtonGhost(
                        text = stringResource(R.string.train_rest_add_short),
                        onClick = onAddTime,
                        modifier = Modifier.weight(1f),
                    )
                }
                if (canCancelPlan) {
                    BrandButtonGhost(
                        text = stringResource(R.string.train_rest_cancel_plan),
                        onClick = onCancelPlan,
                        modifier = Modifier.weight(1f),
                    )
                }
                FlowRepPrimaryButton(
                    text = stringResource(R.string.train_rest_end),
                    onClick = onSkip,
                    modifier = Modifier.weight(if (canExtend || canCancelPlan) 1.5f else 1f),
                )
            }
        }
    }
    Spacer(Modifier.height(20.dp))
    BrandButtonGhost(
        text = stringResource(R.string.train_exercise_end),
        onClick = onFinish,
        modifier = Modifier.fillMaxWidth(),
    )
}

/**
 * C15 (PR-3): DropSync-Schalter der laufenden Pause. Unter einer Minute
 * ausgegraut mit Grund (PR-4); Ausschalten bleibt immer moeglich.
 */
@Composable
private fun RestDropAutoRow(
    checked: Boolean,
    blocked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.train_rest_drop_switch),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                enabled = checked || !blocked,
            )
        }
        if (blocked && !checked) {
            Text(
                text = stringResource(R.string.train_rest_drop_switch_blocked),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

/**
 * C3 (P-3/MP-9): Ducking der Pausenmusik direkt in der Rest-Konsole.
 * Liest und schreibt `DspConfig.restDuckDb` — dieselbe Quelle wie die
 * Einstellungen, damit beide Orte nie auseinanderlaufen.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RestDuckRow(
    restDuckDb: Double,
    onSetRestDuckDb: (Double) -> Unit,
) {
    val steps = listOf(0.0, -2.0, -4.0, -6.0, -8.0, -10.0, -12.0)
    Column(modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
        Text(
            text = stringResource(R.string.train_rest_duck_title, restDuckDb.roundToInt()),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(top = 4.dp),
        ) {
            steps.forEach { value ->
                FilterChip(
                    selected = restDuckDb == value,
                    onClick = { onSetRestDuckDb(value) },
                    label = { Text("${value.roundToInt()} dB") },
                    // A5: 48-dp-Mindesthoehe fuers Touch-Ziel.
                    modifier = Modifier.heightIn(min = 48.dp),
                )
            }
        }
    }
}

/**
 * C3 (5.3): Pausendauer (60/90/120/180) und Musikmodus (Normal/DropSync)
 * je Uebung. Der Bereitschaftsgrund steht direkt am DropSync-Chip, wenn
 * Drop-Auto nichts bewirken kann.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RestPrefDialog(
    restSeconds: Int,
    restMode: RestMode,
    readiness: DropAutoReadiness,
    onDismiss: () -> Unit,
    onConfirm: (Int, RestMode) -> Unit,
) {
    var seconds by remember(restSeconds) { mutableStateOf(restSeconds) }
    var mode by remember(restMode) { mutableStateOf(restMode) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.train_rest_pref_title)) },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.train_rest_pref_duration),
                    style = MaterialTheme.typography.labelMedium,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(top = 4.dp),
                ) {
                    listOf(60, 90, 120, 180).forEach { value ->
                        FilterChip(
                            selected = seconds == value,
                            onClick = { seconds = value },
                            label = { Text(stringResource(R.string.train_rest_pref_seconds, value)) },
                            modifier = Modifier.heightIn(min = 48.dp),
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))
                Text(
                    text = stringResource(R.string.train_rest_pref_mode),
                    style = MaterialTheme.typography.labelMedium,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(top = 4.dp),
                ) {
                    FilterChip(
                        selected = mode == RestMode.NORMAL,
                        onClick = { mode = RestMode.NORMAL },
                        label = { Text(stringResource(R.string.train_rest_pref_mode_normal)) },
                        modifier = Modifier.heightIn(min = 48.dp),
                    )
                    FilterChip(
                        selected = mode == RestMode.DROPSYNC,
                        onClick = { mode = RestMode.DROPSYNC },
                        label = { Text(stringResource(R.string.train_rest_pref_mode_dropsync)) },
                        modifier = Modifier.heightIn(min = 48.dp),
                    )
                }
                if (mode == RestMode.DROPSYNC && readiness is DropAutoReadiness.Blocked) {
                    Text(
                        text =
                            stringResource(
                                when (readiness.reason) {
                                    DropAutoBlockReason.NO_REST_PLAYLIST -> {
                                        R.string.train_rest_pref_blocked_no_rest_playlist
                                    }

                                    DropAutoBlockReason.NO_WORK_PLAYLIST -> {
                                        R.string.train_rest_pref_blocked_no_work_playlist
                                    }
                                },
                            ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(seconds, mode) }) {
                Text(stringResource(R.string.train_rest_pref_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.train_rest_pref_cancel))
            }
        },
    )
}

/** P1-10/A5: kurzes GO-Overlay nach der Landung (A.4); Tap schliesst es. */
@Composable
internal fun GoOverlay(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier =
            modifier
                .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.94f))
                // A5: der Tap gehoert dem Overlay — ohne clickable liefen
                // Beruehrungen auf die Knoepfe darunter durch (Touch-Leak).
                .clickable(onClick = onDismiss)
                // A5: TalkBack sagt das GO an, statt es nur zu zeigen.
                .semantics { liveRegion = LiveRegionMode.Polite },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = stringResource(R.string.train_rest_dropsync_go),
                style = MaterialTheme.typography.displayMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Text(
                text = stringResource(R.string.train_rest_dropsync_landed),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }
}

/**
 * P1-10: "Plan abbrechen" ist nur bei einer Landung am Pausenende eine
 * eigene Aktion (Pause laeuft weiter); beim manuellen DropRest IST der Plan
 * die Pause — dort bleibt nur "Pause beenden".
 */
internal fun dropSyncCanCancelPlan(state: DropSyncState): Boolean =
    when (state) {
        is DropSyncState.Planned -> state.mode == DropSyncMode.LANDING_AT_REST_END
        is DropSyncState.Armed -> state.plan.mode == DropSyncMode.LANDING_AT_REST_END
        else -> false
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
    val reportCoreTemplate = stringResource(R.string.train_set_report)
    val reportRejectedTemplate = stringResource(R.string.train_set_report_rejected)
    val rejectionAccel = stringResource(R.string.train_rejection_accel)
    val rejectionTemplate = stringResource(R.string.train_rejection_template)
    val rejectionPhase = stringResource(R.string.train_rejection_phase)
    val rejectionQuality = stringResource(R.string.train_rejection_quality)
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
    LaunchedEffect(
        snackbarHostState,
        reportCoreTemplate,
        reportRejectedTemplate,
        rejectionAccel,
        rejectionTemplate,
        rejectionPhase,
        rejectionQuality,
    ) {
        if (snackbarHostState == null) return@LaunchedEffect
        val labels =
            mapOf(
                RepRejectionReason.ACCEL_VOTING to rejectionAccel,
                RepRejectionReason.TEMPLATE_MATCH to rejectionTemplate,
                RepRejectionReason.PHASE_VALIDATION to rejectionPhase,
                RepRejectionReason.QUALITY to rejectionQuality,
            )
        setReport.collect { report ->
            snackbarHostState.showSnackbar(
                message =
                    SetReportText.format(report, reportCoreTemplate, reportRejectedTemplate) { reason ->
                        labels.getValue(reason)
                    },
                duration = SnackbarDuration.Long,
            )
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
    LaunchedEffect(
        snackbarHostState,
        errorExerciseCreate,
        errorProfileLoad,
        errorLearningSave,
        errorRestPrefSave,
        errorUndo,
        errorHistory,
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
                }
            snackbarHostState.showSnackbar(message)
        }
    }
}

/**
 * P1-10: Statuschips der DropSync-Konsole (A.4) — nur Zustaende, nie
 * Technik. `Audio vorbereitet` nur bei echter Armierung (nicht beim
 * Deadline-Fallback), `Timing stabil` nur bei EXACT-Konfidenz.
 */
@Composable
private fun DropSyncStatusChips(dropSyncState: DropSyncState) {
    val audioReady = stringResource(R.string.train_rest_dropsync_audio_ready)
    val timingStable = stringResource(R.string.train_rest_dropsync_timing_stable)
    val bestEffort = stringResource(R.string.train_rest_dropsync_best_effort)
    val chips =
        when (dropSyncState) {
            is DropSyncState.Armed -> {
                buildList {
                    if (dropSyncState.audioPrepared) add(audioReady)
                    if (dropSyncState.plan.confidence == TimingConfidence.EXACT) add(timingStable)
                }
            }

            is DropSyncState.Planned -> {
                buildList {
                    if (dropSyncState.confidence == TimingConfidence.EXACT) add(timingStable)
                }
            }

            is DropSyncState.BestEffort -> {
                listOf(bestEffort)
            }

            else -> {
                emptyList()
            }
        }
    if (chips.isEmpty()) return
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.padding(top = 6.dp),
    ) {
        chips.forEach { label ->
            Surface(
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        }
    }
}

/**
 * P1-10: Restzeit bis zur geplanten Landung ("Ziel in mm:ss").
 *
 * Bei einer Landung am Pausenende ist das Ziel das Pausenende — dort tickt
 * die grosse Timer-Restzeit bereits. Beim manuellen DropRest liefert der
 * Monitor die projektierte Zeit bis zum Marker. null, wenn kein Plan aktiv
 * ist.
 */
internal fun dropTargetRemainingMs(
    state: DropSyncState,
    restRemainingMs: Long,
): Long? =
    when (state) {
        is DropSyncState.Planned -> {
            if (state.mode == DropSyncMode.LANDING_AT_REST_END) restRemainingMs else state.remainingMs
        }

        is DropSyncState.Armed -> {
            if (state.plan.mode == DropSyncMode.LANDING_AT_REST_END) restRemainingMs else state.plan.remainingMs
        }

        else -> {
            null
        }
    }

private fun formatRestRemaining(remainingMs: Long): String {
    val totalSeconds = (remainingMs + 999) / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return String.format(Locale.ROOT, "%d:%02d", minutes, seconds)
}

/** P1-10: Sichtdauer des GO-Overlays nach der Landung. */
private const val GO_OVERLAY_MS = 4_000L

/**
 * P1-10: Kopfzeile des aktiven DropSync-Plans — "Track · Marker · Ziel in
 * mm:ss". Nur Zustaende, nie Technik (A.4). C16: Bei einer geplanten
 * Ueberleitungskette steht die Kette ("A -> B -> Drop") statt des einen
 * Zieltitels.
 */
private fun DropSyncState.headline(): String =
    when (this) {
        is DropSyncState.Planned -> {
            if (chain.size > 1) {
                chain.joinToString(CHAIN_ARROW)
            } else if (markerLabel.isBlank()) {
                songTitle
            } else {
                "$songTitle · $markerLabel"
            }
        }

        is DropSyncState.Armed -> {
            if (plan.chain.size > 1) {
                plan.chain.joinToString(CHAIN_ARROW)
            } else if (plan.markerLabel.isBlank()) {
                plan.songTitle
            } else {
                "${plan.songTitle} · ${plan.markerLabel}"
            }
        }

        else -> {
            ""
        }
    }

/** C16: Trenner der Kettenzeile (Konsolen- und Badge-Sprache). */
internal const val CHAIN_ARROW = " → "

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
 * RC-4: Hinweis nach 0 erkannten Reps, solange das Rep-Feld leer ist.
 * Ausgelagert, damit [TrainScreen] unter der Detekt-Komplexitaetsgrenze
 * bleibt.
 */
@Composable
private fun CountedZeroHint(
    countedZero: Boolean,
    repsInput: String,
) {
    if (!countedZero || repsInput.isNotEmpty()) return
    Spacer(Modifier.height(8.dp))
    Text(
        text = stringResource(R.string.train_counted_zero_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.tertiary,
    )
}

/**
 * A.4/RC-5: Sensor-Kopfzeile (Chip + Qualitaet + Puls) statt einer Karte, die
 * mit der Satz-Eingabe um Aufmerksamkeit konkurriert. Fehler stehen direkt
 * unter dem Chip (Design 8.1); der Kalibrier-Einstieg liegt an der
 * Uebungszeile (RC-8), nicht mehr als zweiter Knopf hier.
 */
@Composable
private fun SensorHeaderRow(
    connection: SensorConnectionState,
    sensorError: SensorErrorReason?,
    signalQuality: SignalQuality,
    heartRateAvailability: com.dropsync.domain.health.HeartRateAvailability,
    heartRateBpm: Int?,
    onRequestHeartRatePermission: () -> Unit,
    onHeartRateResume: () -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            when (connection) {
                SensorConnectionState.DISCONNECTED -> {
                    AssistChip(
                        onClick = onConnect,
                        label = { Text(stringResource(R.string.sensor_connect)) },
                        modifier = Modifier.heightIn(min = 48.dp),
                    )
                }

                SensorConnectionState.CONNECTING -> {
                    Text(
                        text = stringResource(R.string.sensor_status_connecting),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                SensorConnectionState.CONNECTED,
                SensorConnectionState.STREAMING,
                -> {
                    Text(
                        text =
                            stringResource(
                                if (connection == SensorConnectionState.STREAMING) {
                                    R.string.sensor_status_streaming
                                } else {
                                    R.string.sensor_status_connected
                                },
                            ),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    TextButton(onClick = onDisconnect, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.sensor_disconnect))
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            // Herzfrequenz-Badge (Herzfrequenz-Plan Phase 2): Health-Connect
            // als Quelle, Permission-Flow und Pulsanzeige.
            HeartRateBadge(
                availability = heartRateAvailability,
                bpm = heartRateBpm,
                onRequestPermission = onRequestHeartRatePermission,
                onResume = onHeartRateResume,
            )
        }
        if (connection == SensorConnectionState.STREAMING) {
            Text(
                text =
                    stringResource(
                        when (signalQuality) {
                            SignalQuality.GOOD -> R.string.sensor_quality_good
                            SignalQuality.DEGRADED -> R.string.sensor_quality_degraded
                            SignalQuality.UNRELIABLE -> R.string.sensor_quality_unreliable
                        },
                    ),
                style = MaterialTheme.typography.labelSmall,
                color =
                    if (signalQuality == SignalQuality.GOOD) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.error
                    },
            )
        }
        sensorError?.let {
            Text(
                text = stringResource(it.messageRes()),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

/**
 * A.4/RC-5: IDLE-Konsole — keine Uebung gewaehlt, also auch keine
 * Primaeraktion. Die Uebungszeile darueber fuehrt zum ersten Satz.
 */
@Composable
internal fun EmptyConsoleHero(modifier: Modifier = Modifier) {
    FlowRepSurface(modifier = modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.train_console_label),
            style = MaterialTheme.typography.labelMedium,
            color = rememberAccentTextColor(),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.train_pick_exercise_placeholder),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = stringResource(R.string.train_console_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

/**
 * RC-5/A.4: Die Satz-Eingabe ist der Hero. Die grosse Rep-Zahl traegt die
 * Quelle (UI-Handbuch 7.4), der Live-Zaehler ist aus der Sensor-Karte in den
 * Hero gezogen, die Waveform steht direkt unter der Zahl, und es gibt genau
 * eine Primaeraktion: "Satz fertig" — waehrend der Zaehlung "Stopp".
 */
@Composable
internal fun SetEntryHero(
    exerciseName: String?,
    weightKg: String,
    lastWeightKg: Double?,
    reps: String,
    repsSource: RepsSource,
    setPhase: ActiveSetPhase,
    countdownSeconds: Int,
    liveCountedReps: Int,
    streaming: Boolean,
    signalQuality: SignalQuality,
    hasCalibration: Boolean,
    waveform: FloatArray,
    lastPeakMs: Long,
    plausibilityHint: PlausibilityHint?,
    countedZero: Boolean,
    maxVolumeKg: Double?,
    restSeconds: Int,
    restMode: RestMode,
    canLog: Boolean,
    onWeightChange: (String) -> Unit,
    onIncrement: () -> Unit,
    onDecrement: () -> Unit,
    onRepsChange: (String) -> Unit,
    onStartSet: () -> Unit,
    onStopSet: () -> Unit,
    onLogSet: () -> Unit,
    onOpenRestPref: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FlowRepSurface(modifier = modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.train_console_label),
            style = MaterialTheme.typography.labelMedium,
            color = rememberAccentTextColor(),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = exerciseName ?: stringResource(R.string.train_pick_exercise_placeholder),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = stringResource(R.string.train_console_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
        Spacer(Modifier.height(28.dp))
        WeightInput(
            weightKg = weightKg,
            lastWeightKg = lastWeightKg,
            onWeightChange = onWeightChange,
            onIncrement = onIncrement,
            onDecrement = onDecrement,
        )
        Spacer(Modifier.height(28.dp))
        RepHeroSection(
            reps = reps,
            repsSource = repsSource,
            setPhase = setPhase,
            countdownSeconds = countdownSeconds,
            liveCountedReps = liveCountedReps,
            streaming = streaming,
            signalQuality = signalQuality,
            hasCalibration = hasCalibration,
            onRepsChange = onRepsChange,
            onStartSet = onStartSet,
            onStopSet = onStopSet,
        )
        // Design 8.1: die Waveform sitzt direkt unter der Rep-Zahl — der
        // Zaehler und sein Signal gehoeren zusammen.
        if (streaming && waveform.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            SensorWaveform(samples = waveform, lastPeakMs = lastPeakMs)
        }
        // Umbauplan 2026-09-04 Phase 7: unabhaengige Zweitmeinung aus der
        // Signalperiodik, direkt unter dem Feld, das sie in Frage stellt.
        // Bewusst KEIN Gegenstueck fuer "Pruefung bestanden": die Pruefung ist
        // bei kurzen oder unregelmaessigen Saetzen stumm, und ein fehlender
        // Hinweis darf nie als Bestaetigung gelesen werden.
        plausibilityHint?.let { hint ->
            Spacer(Modifier.height(8.dp))
            PlausibilityHintRow(hint)
        }
        // RC-4: 0 erkannte Reps sind kein stummes Nichts — der Nutzer bekommt
        // Grund und Handlung (manuell eintragen oder Signal pruefen), solange
        // das Feld leer ist.
        CountedZeroHint(countedZero = countedZero, repsInput = reps)
        Spacer(Modifier.height(28.dp))
        // A.4: genau eine Primaeraktion je Zustand — waehrend der Zaehlung ist
        // "Stopp" die Primaeraktion im Rep-Hero, sonst "Satz fertig".
        if (setPhase != ActiveSetPhase.COUNTING) {
            FlowRepPrimaryButton(
                text = stringResource(R.string.train_set_done),
                onClick = onLogSet,
                enabled = canLog,
            )
        }
        // C3 (5.3): Kompakte Zeile statt Schalter — Dauer und Musikmodus
        // gelten je Uebung und werden im Dialog gesetzt (der globale
        // Drop-Auto-Schalter lebt in den Einstellungen).
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(top = 8.dp),
        ) {
            Text(
                text =
                    stringResource(
                        R.string.train_rest_pref_row,
                        restSeconds,
                        stringResource(
                            if (restMode == RestMode.DROPSYNC) {
                                R.string.train_rest_pref_mode_dropsync
                            } else {
                                R.string.train_rest_pref_mode_normal
                            },
                        ),
                    ),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onOpenRestPref) {
                Text(stringResource(R.string.train_rest_pref_change))
            }
        }
        // PR-Volumen der gewaehlten Uebung
        maxVolumeKg?.let { volume ->
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.train_pr_volume, volume),
                style = MaterialTheme.typography.bodySmall,
                color = rememberAccentTextColor(),
            )
        }
    }
}

/**
 * RC-5: Der Rep-Hero. Die grosse Zahl ist der gemeinsame Wert von
 * Live-Zaehler und Eingabe; darunter steht die Quelle (AUTO / MANUELL
 * KORRIGIERT / MANUELL / SENSOR GETRENNT, UI-Handbuch 7.4). `+/-` bleibt der
 * schnelle Korrekturpfad (7.2), der Live-Zaehler eine sekundaere Aktion.
 */
@Composable
private fun RepHeroSection(
    reps: String,
    repsSource: RepsSource,
    setPhase: ActiveSetPhase,
    countdownSeconds: Int,
    liveCountedReps: Int,
    streaming: Boolean,
    signalQuality: SignalQuality,
    hasCalibration: Boolean,
    onRepsChange: (String) -> Unit,
    onStartSet: () -> Unit,
    onStopSet: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = stringResource(R.string.train_reps_label),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        when (setPhase) {
            ActiveSetPhase.COUNTING -> {
                LiveCountHero(liveCountedReps = liveCountedReps, onStopSet = onStopSet)
                // Waehrend der Zaehlung ist die grosse Zahl live — die Quelle
                // ist dann immer der Sensor.
                RepSourceLine(source = RepsSource.Sensor, streaming = streaming)
            }

            ActiveSetPhase.COUNTDOWN -> {
                Text(
                    text = stringResource(R.string.live_count_countdown, countdownSeconds),
                    style = MaterialTheme.typography.headlineMedium,
                    color = rememberAccentTextColor(),
                )
            }

            ActiveSetPhase.IDLE -> {
                RepInput(reps = reps, onRepsChange = onRepsChange)
                // Die Quelle erklaert die *aktuelle* Zahl; ohne Zahl und ohne
                // Abriss gibt es nichts zu erklaeren.
                if (reps.isNotEmpty() || repsSource == RepsSource.SensorDisconnected) {
                    RepSourceLine(source = repsSource, streaming = streaming)
                }
                LiveCountStartRow(
                    streaming = streaming,
                    hasCalibration = hasCalibration,
                    signalQuality = signalQuality,
                    onStartSet = onStartSet,
                )
            }
        }
    }
}

/**
 * RC-5: Der Live-Zaehler als grosse Zahl im Hero plus "Stopp" als
 * Primaeraktion dieses Zustands. TalkBack (Plan 6.2): Zaehlerstand als
 * polite liveRegion, jede neue Rep wird angesagt ohne den Fokus zu klauen.
 */
@Composable
private fun LiveCountHero(
    liveCountedReps: Int,
    onStopSet: () -> Unit,
) {
    val repCountDescription = stringResource(R.string.a11y_reps_counted, liveCountedReps)
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "$liveCountedReps",
            style = MaterialTheme.typography.displayMedium,
            color = rememberAccentTextColor(),
            modifier =
                Modifier
                    .weight(1f)
                    .semantics {
                        liveRegion = LiveRegionMode.Polite
                        contentDescription = repCountDescription
                    },
        )
        BrandButtonPrimary(text = stringResource(R.string.live_count_stop), onClick = onStopSet)
    }
}

/**
 * RC-5: Der Live-Zaehler ist eine sekundaere Aktion im Hero — die
 * Primaeraktion bleibt "Satz fertig". Fehlt die Kalibrierung, steht hier nur
 * der Grund; der Einstieg in den Wizard liegt direkt darueber an der
 * Uebungszeile (RC-8).
 */
@Composable
private fun LiveCountStartRow(
    streaming: Boolean,
    hasCalibration: Boolean,
    signalQuality: SignalQuality,
    onStartSet: () -> Unit,
) {
    if (!streaming) return
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (hasCalibration) {
            BrandButtonGhost(
                text = stringResource(R.string.live_count_start),
                onClick = onStartSet,
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            Text(
                text = stringResource(R.string.live_count_reason_no_profile),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (signalQuality == SignalQuality.DEGRADED) {
            Text(
                text = stringResource(R.string.live_count_weak_signal),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

/**
 * RC-5 / UI-Handbuch 7.4: Quelle der Rep-Zahl im Hero. Genau eine Zahl, genau
 * eine Quelle — die Zeile nennt zusaetzlich die Handlung, wenn der Chip fehlt
 * ("SENSOR GETRENNT — Reps per +/- weiter", Design 8.1).
 */
@Composable
private fun RepSourceLine(
    source: RepsSource,
    streaming: Boolean,
) {
    val labelRes =
        when (source) {
            RepsSource.Sensor -> R.string.reps_source_auto
            is RepsSource.SensorWithManualCorrection -> R.string.reps_source_corrected
            RepsSource.Manual -> R.string.reps_source_manual
            RepsSource.SensorDisconnected -> R.string.reps_source_disconnected
        }
    val hint =
        when (source) {
            RepsSource.Sensor -> {
                stringResource(R.string.reps_source_auto_hint)
            }

            is RepsSource.SensorWithManualCorrection -> {
                stringResource(R.string.reps_source_corrected_hint, source.counted)
            }

            RepsSource.Manual -> {
                stringResource(
                    if (streaming) {
                        R.string.reps_source_manual_hint
                    } else {
                        R.string.reps_source_manual_disconnected_hint
                    },
                )
            }

            RepsSource.SensorDisconnected -> {
                stringResource(R.string.reps_source_disconnected_hint)
            }
        }
    val warn = source == RepsSource.SensorDisconnected
    val color =
        if (warn) {
            MaterialTheme.colorScheme.tertiary
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(text = stringResource(labelRes), style = MaterialTheme.typography.labelSmall, color = color)
        Text(text = hint, style = MaterialTheme.typography.bodySmall, color = color)
    }
}

/**
 * Herzfrequenz-Badge (Herzfrequenz-Plan Phase 2): zeigt den letzten Puls
 * aus Health Connect; ohne Berechtigung ein Chip zum Nachfragen, ohne
 * Provider ein stiller Hinweis. Daten kommen nur im Foreground (Plan 3.4).
 */
@Composable
private fun HeartRateBadge(
    availability: com.dropsync.domain.health.HeartRateAvailability,
    bpm: Int?,
    onRequestPermission: () -> Unit,
    onResume: () -> Unit,
) {
    LaunchedEffect(availability) {
        if (availability == com.dropsync.domain.health.HeartRateAvailability.PERMISSION_REQUIRED ||
            availability == com.dropsync.domain.health.HeartRateAvailability.NO_RECENT_DATA ||
            availability == com.dropsync.domain.health.HeartRateAvailability.READY
        ) {
            onResume()
        }
    }
    when (availability) {
        com.dropsync.domain.health.HeartRateAvailability.HEALTH_CONNECT_NOT_AVAILABLE,
        com.dropsync.domain.health.HeartRateAvailability.UPDATE_REQUIRED,
        -> {
            // Kein Provider oder Update noetig: bewusst kein UI-Element,
            // der Nutzer hat die Funktion nicht aktiviert.
        }

        com.dropsync.domain.health.HeartRateAvailability.PERMISSION_REQUIRED -> {
            AssistChip(
                onClick = onRequestPermission,
                label = { Text(stringResource(R.string.heart_rate_allow)) },
                modifier = Modifier.heightIn(min = 48.dp),
            )
        }

        com.dropsync.domain.health.HeartRateAvailability.NO_RECENT_DATA -> {
            Text(
                text = stringResource(R.string.heart_rate_none),
                style = MaterialTheme.typography.bodySmall,
            )
        }

        com.dropsync.domain.health.HeartRateAvailability.READY -> {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text =
                        stringResource(
                            R.string.heart_rate_value,
                            bpm?.toString() ?: stringResource(R.string.heart_rate_unknown),
                        ),
                    style = MaterialTheme.typography.titleMedium,
                    color = rememberAccentTextColor(),
                )
            }
        }
    }
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
