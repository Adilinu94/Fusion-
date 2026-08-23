package com.dropsync.feature.workout

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dropsync.core.designsystem.component.BrandButtonGhost
import com.dropsync.core.designsystem.component.FlowRepPrimaryButton
import com.dropsync.core.designsystem.component.FlowRepSectionHeader
import com.dropsync.core.designsystem.component.FlowRepSurface
import com.dropsync.domain.sensor.ActiveSetPhase
import com.dropsync.domain.sensor.SensorConnectionState
import com.dropsync.domain.sensor.SignalQuality
import com.dropsync.domain.timer.TimerStatus
import com.dropsync.domain.workout.ExerciseInfo
import java.util.Locale

/**
 * Train-Tab (FlowRep-Design Phase 2): flaches Satz-Log ohne Session.
 * Uebungs-Chip, Gewicht +/-2.5, Rep-Eingabe, Satz speichern, PR-Volumen.
 */
@Composable
fun TrainScreen(
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
    onOpenCalibration: (exerciseId: Long, deviceId: String) -> Unit = { _, _ -> },
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
    val dropAutoEnabled by viewModel.dropAutoEnabled.collectAsStateWithLifecycle()
    val sensorConnection by viewModel.sensorConnection.collectAsStateWithLifecycle()
    val connectedDeviceId by viewModel.connectedDeviceId.collectAsStateWithLifecycle()
    val sensorError by viewModel.sensorError.collectAsStateWithLifecycle()
    val waveform by viewModel.waveform.collectAsStateWithLifecycle()
    val lastPeakMs by viewModel.lastPeakMs.collectAsStateWithLifecycle()
    val setPhase by viewModel.setPhase.collectAsStateWithLifecycle()
    val countdownSeconds by viewModel.countdownSeconds.collectAsStateWithLifecycle()
    val liveCountedReps by viewModel.liveCountedReps.collectAsStateWithLifecycle()
    val hasCalibration by viewModel.hasCalibration.collectAsStateWithLifecycle()
    val signalQuality by viewModel.signalQuality.collectAsStateWithLifecycle()
    // Herzfrequenz-Badge (Herzfrequenz-Plan Phase 2): Health-Connect-Zustand.
    val heartRateAvailability by viewModel.heartRateAvailability.collectAsStateWithLifecycle()
    val heartRateSample by viewModel.heartRateSample.collectAsStateWithLifecycle()

    var showCreateDialog by remember { mutableStateOf(false) }

    // Health-Connect-Berechtigung (Plan 3.2): der generische Contract kommt
    // injiziert aus :data:health (Hilt-Qualifier); das Feature kennt kein
    // SDK-Typ.
    val healthPermissionLauncher =
        rememberLauncherForActivityResult(viewModel.healthPermissionContract) {
            viewModel.refreshHeartRate()
        }

    // POST_NOTIFICATIONS runtime request (Phase 3 step 3). Denied -> the
    // foreground service keeps running and cues still fire (Xiaomi fallback).
    val context = LocalContext.current
    var notificationsAllowed by remember {
        mutableStateOf(
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val notificationPermissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            notificationsAllowed = granted
        }
    LaunchedEffect(Unit) {
        if (!notificationsAllowed) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    Column(
        modifier =
            modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(
                    top = contentPadding.calculateTopPadding() + 8.dp,
                    bottom = contentPadding.calculateBottomPadding() + 24.dp,
                ),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Übungs-Chips + "Neue Übung"
        ExerciseChipRow(
            exercises = exercises,
            selectedId = selectedExercise?.id,
            onSelect = { viewModel.selectExercise(it) },
            onCreateNew = { showCreateDialog = true },
        )

        val restActive =
            timerState.status == TimerStatus.PREPARING ||
                timerState.status == TimerStatus.RUNNING || timerState.status == TimerStatus.PAUSED
        if (restActive) {
            RestConsole(
                remainingMs = timerState.remainingMs,
                onAddTime = { viewModel.addRestTime() },
                onSkip = { viewModel.skipRest() },
                onFinish = { viewModel.finishExercise() },
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        } else {
            FlowRepSurface(modifier = Modifier.padding(horizontal = 16.dp)) {
                Text(
                    text = "SATZ EINGEBEN",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = selectedExercise?.displayName ?: "Übung wählen",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = "Gewicht und Wiederholungen bestätigen",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
                Spacer(Modifier.height(28.dp))
                // Gewicht mit +/- 2.5
                WeightInput(
                    weightKg = weightInput,
                    lastWeightKg = lastSet?.let { it.weightMilliKg / 1_000_000.0 },
                    onWeightChange = { viewModel.setWeight(it) },
                    onIncrement = { viewModel.adjustWeight(2.5) },
                    onDecrement = { viewModel.adjustWeight(-2.5) },
                )

                Spacer(Modifier.height(28.dp))

                // Reps
                RepInput(
                    reps = repsInput,
                    onRepsChange = { viewModel.setReps(it) },
                )

                Spacer(Modifier.height(28.dp))

                // The console exposes a single primary completion action.
                FlowRepPrimaryButton(
                    text = "SATZ FERTIG",
                    onClick = { viewModel.logSet() },
                    enabled = selectedExercise != null && viewModel.canLog,
                )

                // Drop-Auto-Schalter pro Pause (Phase 3 step 4)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 8.dp),
                ) {
                    Text(
                        text = "Drop-Auto",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(
                        checked = dropAutoEnabled,
                        onCheckedChange = { viewModel.setDropAutoEnabled(it) },
                    )
                }

                // PR-Volumen der gewaehlten Uebung
                maxVolumeKg?.let { volume ->
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "PR-Volumen: ${"%.1f".format(volume)} kg",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }

        // Sensor remains supplemental to logging and never competes with the
        // set-completion action.
        SensorStatusCard(
            connection = sensorConnection,
            deviceId = connectedDeviceId,
            sensorError = sensorError,
            selectedExerciseId = selectedExercise?.id,
            setPhase = setPhase,
            countdownSeconds = countdownSeconds,
            liveCountedReps = liveCountedReps,
            hasCalibration = hasCalibration,
            signalQuality = signalQuality,
            heartRateAvailability = heartRateAvailability,
            heartRateBpm = heartRateSample,
            onRequestHeartRatePermission = {
                healthPermissionLauncher.launch(viewModel.heartRatePermissions)
            },
            onHeartRateResume = { viewModel.refreshHeartRate() },
            onConnect = { viewModel.connectSensor() },
            onDisconnect = { viewModel.disconnectSensor() },
            onOpenCalibration = onOpenCalibration,
            onStartSet = { viewModel.startCountedSet() },
            onStopSet = { viewModel.stopCountedSet() },
            modifier = Modifier.padding(horizontal = 16.dp),
        )

        // Live-Sensor-Waveform (Phase 4 step 5): sichtbar sobald der Chip
        // streamt; Blitz-Overlay bei erkanntem Wiederholungs-Peak.
        if (sensorConnection == SensorConnectionState.STREAMING && waveform.isNotEmpty()) {
            SensorWaveform(
                samples = waveform,
                lastPeakMs = lastPeakMs,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }

        // Mini-Verlauf (letzte 5 Saetze)
        if (recentSets.isNotEmpty()) {
            FlowRepSurface(
                modifier = Modifier.padding(horizontal = 16.dp),
                contentPadding = PaddingValues(20.dp),
            ) {
                FlowRepSectionHeader(title = "Letzte Sätze")
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
                            text = "${(index + 1).toString().padStart(2, '0')}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.width(40.dp),
                        )
                        Text(
                            text = "${set.reps} REPS",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = "${set.weightMilliKg / 1_000_000.0} kg",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary,
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
}

@Composable
private fun ExerciseChipRow(
    exercises: List<ExerciseInfo>,
    selectedId: Long?,
    onSelect: (ExerciseInfo) -> Unit,
    onCreateNew: () -> Unit,
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
                label = { Text("+ Neue Übung") },
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
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = "GEWICHT · KG",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = weightKg,
            onValueChange = onWeightChange,
            placeholder = { lastWeightKg?.let { Text("Zuletzt $it kg", maxLines = 1) } },
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
                text = "− 2,5",
                onClick = onDecrement,
                modifier = Modifier.weight(1f),
            )
            BrandButtonGhost(
                text = "+ 2,5",
                onClick = onIncrement,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun RepInput(
    reps: String,
    onRepsChange: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = "WIEDERHOLUNGEN",
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
                modifier = Modifier.size(64.dp),
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
                modifier = Modifier.size(64.dp),
            )
        }
        Text(
            text = "Wert antippen, um ihn direkt einzugeben",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Rest-timer pill inside the train card (Phase 3 step 4): countdown plus
 * End-rest / finish-exercise controls. Both actions cancel the timer immediately
 * (design rule step 5).
 */
@Composable
private fun RestConsole(
    remainingMs: Long,
    onAddTime: () -> Unit,
    onSkip: () -> Unit,
    onFinish: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FlowRepSurface(modifier = modifier.fillMaxWidth()) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "PAUSE",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = formatRestRemaining(remainingMs),
                style = MaterialTheme.typography.displayLarge,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 16.dp),
            )
            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                if (maxWidth < 480.dp) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        BrandButtonGhost(
                            text = "+15 SEKUNDEN",
                            onClick = onAddTime,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        FlowRepPrimaryButton(text = "PAUSE BEENDEN", onClick = onSkip)
                    }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        BrandButtonGhost(
                            text = "+15 S",
                            onClick = onAddTime,
                            modifier = Modifier.weight(1f),
                        )
                        FlowRepPrimaryButton(
                            text = "PAUSE BEENDEN",
                            onClick = onSkip,
                            modifier = Modifier.weight(1.5f),
                        )
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
            BrandButtonGhost(
                text = "ÜBUNG BEENDEN",
                onClick = onFinish,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

private fun formatRestRemaining(remainingMs: Long): String {
    val totalSeconds = (remainingMs + 999) / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return String.format(Locale.ROOT, "%d:%02d", minutes, seconds)
}

@Composable
private fun CreateExerciseDialog(
    onDismiss: () -> Unit,
    onCreate: (String) -> Unit,
) {
    var name by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Neue Übung") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onCreate(name) },
                enabled = name.isNotBlank(),
            ) {
                Text("Anlegen")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Abbrechen")
            }
        },
    )
}

/**
 * FlowRep chip status card (Phase 4): connect/disconnect plus the entry into
 * the calibration wizard once a chip streams and an exercise is selected.
 */
@Composable
private fun SensorStatusCard(
    connection: SensorConnectionState,
    deviceId: String?,
    sensorError: String?,
    selectedExerciseId: Long?,
    setPhase: ActiveSetPhase,
    countdownSeconds: Int,
    liveCountedReps: Int,
    hasCalibration: Boolean,
    signalQuality: SignalQuality,
    heartRateAvailability: com.dropsync.domain.health.HeartRateAvailability,
    heartRateBpm: Int?,
    onRequestHeartRatePermission: () -> Unit,
    onHeartRateResume: () -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onOpenCalibration: (exerciseId: Long, deviceId: String) -> Unit,
    onStartSet: () -> Unit,
    onStopSet: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val statusText =
                when (connection) {
                    SensorConnectionState.DISCONNECTED -> "Kein Chip verbunden"
                    SensorConnectionState.CONNECTING -> "Verbinde..."
                    SensorConnectionState.CONNECTED -> "Chip verbunden"
                    SensorConnectionState.STREAMING -> "Chip streamt"
                }
            Text(text = statusText, style = MaterialTheme.typography.titleSmall)

            sensorError?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when (connection) {
                    SensorConnectionState.DISCONNECTED -> {
                        AssistChip(
                            onClick = onConnect,
                            label = { Text("Chip verbinden") },
                            modifier = Modifier.heightIn(min = 48.dp),
                        )
                    }

                    SensorConnectionState.CONNECTING -> {}

                    SensorConnectionState.CONNECTED,
                    SensorConnectionState.STREAMING,
                    -> {
                        AssistChip(
                            onClick = onDisconnect,
                            label = { Text("Trennen") },
                            modifier = Modifier.heightIn(min = 48.dp),
                        )
                        val exId = selectedExerciseId
                        val devId = deviceId
                        if (exId != null && devId != null) {
                            AssistChip(
                                onClick = { onOpenCalibration(exId, devId) },
                                label = { Text("Kalibrieren") },
                                modifier = Modifier.heightIn(min = 48.dp),
                            )
                        }
                    }
                }
            }

            // Live rep counting (Phase 4): start -> countdown -> count -> stop.
            // Visible while the chip streams; needs a calibration profile.
            if (connection == SensorConnectionState.STREAMING) {
                LiveCountPanel(
                    setPhase = setPhase,
                    countdownSeconds = countdownSeconds,
                    liveCountedReps = liveCountedReps,
                    hasCalibration = hasCalibration,
                    signalQuality = signalQuality,
                    onStartSet = onStartSet,
                    onStopSet = onStopSet,
                )
            }

            // Herzfrequenz-Badge (Herzfrequenz-Plan Phase 2): Health-Connect
            // als Quelle, Permission-Flow und Pulsanzeige.
            HeartRateBadge(
                availability = heartRateAvailability,
                bpm = heartRateBpm,
                onRequestPermission = onRequestHeartRatePermission,
                onResume = onHeartRateResume,
            )
        }
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
                label = { Text("Puls erlauben") },
                modifier = Modifier.heightIn(min = 48.dp),
            )
        }

        com.dropsync.domain.health.HeartRateAvailability.NO_RECENT_DATA -> {
            Text(
                text = "Kein aktueller Puls",
                style = MaterialTheme.typography.bodySmall,
            )
        }

        com.dropsync.domain.health.HeartRateAvailability.READY -> {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "♥ ${bpm ?: "--"} bpm",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

/**
 * Live-count set control (Fusion Phase 4): a start button begins a short
 * countdown, then the pipeline counts reps until the user stops. The counted
 * number is copied into the reps input on stop and can be corrected before
 * logging. Umbauplan Phase 9: DEGRADED-Signal zeigt einen Hinweis, damit der
 * Nutzer die Zahl vor dem Loggen prueft.
 */
@Composable
private fun LiveCountPanel(
    setPhase: ActiveSetPhase,
    countdownSeconds: Int,
    liveCountedReps: Int,
    hasCalibration: Boolean,
    signalQuality: SignalQuality,
    onStartSet: () -> Unit,
    onStopSet: () -> Unit,
) {
    when (setPhase) {
        ActiveSetPhase.IDLE -> {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onStartSet,
                    enabled = hasCalibration,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (hasCalibration) "Satz starten (zählen)" else "Zuerst kalibrieren")
                }
                if (signalQuality == SignalQuality.DEGRADED) {
                    Text(
                        text = "Signal schwach - Zahl bitte prüfen",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }

        ActiveSetPhase.COUNTDOWN -> {
            Text(
                text = "Start in $countdownSeconds …",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        ActiveSetPhase.COUNTING -> {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    // TalkBack (Plan 6.2): Zaehlerstand als polite liveRegion,
                    // jede neue Rep wird angesagt ohne den Fokus zu klauen.
                    Text(
                        text = "$liveCountedReps",
                        style = MaterialTheme.typography.displayMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier =
                            Modifier
                                .weight(1f)
                                .semantics {
                                    liveRegion = LiveRegionMode.Polite
                                    contentDescription = "$liveCountedReps Wiederholungen"
                                },
                    )
                    Button(onClick = onStopSet) {
                        Text("Stopp")
                    }
                }
                if (signalQuality == SignalQuality.DEGRADED) {
                    Text(
                        text = "Signal schwach - Zahl bitte prüfen",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier =
                            Modifier.semantics {
                                liveRegion = LiveRegionMode.Polite
                            },
                    )
                }
            }
        }
    }
}

/**
 * Live sensor waveform (Fusion Phase 4 step 5): rolling line plot of the
 * acceleration magnitude stream plus a brief flash overlay when the
 * TrainViewModel reports a rep peak. Purely visual — never counts live
 * (shadow-pipeline rule, design doc section 11b).
 */
@Composable
private fun SensorWaveform(
    samples: List<Float>,
    lastPeakMs: Long,
    modifier: Modifier = Modifier,
) {
    val lineColor = MaterialTheme.colorScheme.primary
    val flashColor = MaterialTheme.colorScheme.tertiary

    // Peak flash: visible for ~400 ms after the last detected peak.
    var flashVisible by remember { mutableStateOf(false) }
    LaunchedEffect(lastPeakMs) {
        if (lastPeakMs > 0) {
            flashVisible = true
            kotlinx.coroutines.delay(400)
            flashVisible = false
        }
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        androidx.compose.foundation.layout.Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(72.dp)
                    .padding(8.dp),
        ) {
            androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
                if (samples.size < 2) return@Canvas
                val minV = samples.min()
                val maxV = samples.max()
                val range = (maxV - minV).coerceAtLeast(0.001f)
                val stepX = size.width / (samples.size - 1)
                val path =
                    androidx.compose.ui.graphics
                        .Path()
                samples.forEachIndexed { i, v ->
                    val x = i * stepX
                    val y = size.height - ((v - minV) / range) * size.height
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                drawPath(
                    path,
                    color = lineColor,
                    style =
                        androidx.compose.ui.graphics.drawscope.Stroke(
                            width = 2.dp.toPx(),
                        ),
                )
            }
            if (flashVisible) {
                androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
                    drawRect(color = flashColor.copy(alpha = 0.25f))
                }
            }
        }
    }
}
