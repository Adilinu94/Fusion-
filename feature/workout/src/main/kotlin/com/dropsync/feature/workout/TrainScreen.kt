package com.dropsync.feature.workout

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dropsync.core.designsystem.component.BrandButtonPrimary
import com.dropsync.domain.sensor.SensorConnectionState
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

    var showCreateDialog by remember { mutableStateOf(false) }

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
                .padding(top = contentPadding.calculateTopPadding()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Uebungs-Chips + "Neue Uebung"
        ExerciseChipRow(
            exercises = exercises,
            selectedId = selectedExercise?.id,
            onSelect = { viewModel.selectExercise(it) },
            onCreateNew = { showCreateDialog = true },
        )

        // FlowRep-Chip: Verbinden / Status / Kalibrieren (Phase 4).
        SensorStatusCard(
            connection = sensorConnection,
            deviceId = connectedDeviceId,
            sensorError = sensorError,
            selectedExerciseId = selectedExercise?.id,
            onConnect = { viewModel.connectSensor() },
            onDisconnect = { viewModel.disconnectSensor() },
            onOpenCalibration = onOpenCalibration,
            modifier = Modifier.padding(horizontal = 16.dp),
        )

        // Gewicht und Reps
        Card(
            modifier = Modifier.padding(horizontal = 16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                // Gewicht mit +/- 2.5
                WeightInput(
                    weightKg = weightInput,
                    lastWeightKg = lastSet?.let { it.weightMilliKg / 1_000_000.0 },
                    onWeightChange = { viewModel.setWeight(it) },
                    onIncrement = { viewModel.adjustWeight(2.5) },
                    onDecrement = { viewModel.adjustWeight(-2.5) },
                )

                Spacer(Modifier.height(16.dp))

                // Reps
                RepInput(
                    reps = repsInput,
                    onRepsChange = { viewModel.setReps(it) },
                )

                Spacer(Modifier.height(16.dp))

                // Satz speichern
                BrandButtonPrimary(
                    text = "Satz speichern",
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

                // Train-Pille (Phase 3 step 4): Countdown + Chips
                val status = timerState.status
                if (status == TimerStatus.PREPARING ||
                    status == TimerStatus.RUNNING ||
                    status == TimerStatus.PAUSED
                ) {
                    Spacer(Modifier.height(12.dp))
                    RestTimerPill(
                        remainingMs = timerState.remainingMs,
                        onSkip = { viewModel.skipRest() },
                        onFinish = { viewModel.finishExercise() },
                    )
                }
            }
        }

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
            Text(
                text = "Letzte Saetze",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            recentSets.take(5).forEach { set ->
                Text(
                    text = "${set.reps} x ${set.weightMilliKg / 1_000_000.0} kg",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
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
    LazyRow(
        modifier = Modifier.padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(exercises, key = { it.id }) { exercise ->
            FilterChip(
                selected = selectedId == exercise.id,
                onClick = { onSelect(exercise) },
                label = { Text(exercise.displayName) },
            )
        }
        item(key = "create_new") {
            AssistChip(
                onClick = onCreateNew,
                label = { Text("+ Neue Uebung") },
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
    Row(verticalAlignment = Alignment.CenterVertically) {
        // -2.5
        AssistChip(
            onClick = onDecrement,
            label = { Text("-2.5") },
        )
        Spacer(Modifier.width(8.dp))

        // Gewicht
        OutlinedTextField(
            value = weightKg,
            onValueChange = onWeightChange,
            label = { Text("Gewicht (kg)") },
            placeholder = { lastWeightKg?.let { Text("zuletzt $it") } },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))

        // +2.5
        AssistChip(
            onClick = onIncrement,
            label = { Text("+2.5") },
        )
    }
}

@Composable
private fun RepInput(
    reps: String,
    onRepsChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = reps,
        onValueChange = onRepsChange,
        label = { Text("Wiederholungen") },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth(),
    )
}

/**
 * Rest-timer pill inside the train card (Phase 3 step 4): countdown plus
 * Skip / Finish-exercise chips. Finish cancels the timer immediately
 * (design rule step 5).
 */
@Composable
private fun RestTimerPill(
    remainingMs: Long,
    onSkip: () -> Unit,
    onFinish: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = formatRestRemaining(remainingMs),
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Row(
                modifier = Modifier.padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AssistChip(onClick = onSkip, label = { Text("Überspringen") })
                AssistChip(onClick = onFinish, label = { Text("Übung abschließen") })
            }
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
        title = { Text("Neue Uebung") },
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
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onOpenCalibration: (exerciseId: Long, deviceId: String) -> Unit,
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
                        AssistChip(onClick = onConnect, label = { Text("Chip verbinden") })
                    }

                    SensorConnectionState.CONNECTING -> {
                        Unit
                    }

                    SensorConnectionState.CONNECTED,
                    SensorConnectionState.STREAMING,
                    -> {
                        AssistChip(onClick = onDisconnect, label = { Text("Trennen") })
                        val exId = selectedExerciseId
                        val devId = deviceId
                        if (exId != null && devId != null) {
                            AssistChip(
                                onClick = { onOpenCalibration(exId, devId) },
                                label = { Text("Kalibrieren") },
                            )
                        }
                    }
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
