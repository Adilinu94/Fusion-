package com.dropsync.feature.workout

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dropsync.core.designsystem.icon.BrandIcons
import com.dropsync.core.model.RestMode
import com.dropsync.domain.workout.ExerciseInfo
import com.dropsync.domain.workout.RestPref
import com.dropsync.domain.workout.SessionExerciseInfo
import com.dropsync.domain.workout.SwapStrategy
import java.text.DateFormat
import java.util.Date

/**
 * Sessionansicht des Trainings-Tabs (Schritt 12.3, erweitert um Schritt 9):
 * Prefill aus dem letzten Satz, gemerkter Rest-Timer pro Uebung (Normal/
 * DropSync), Uebungstausch KEEP/MOVE/DISCARD, "Als Routine speichern",
 * "Letzte Session wiederholen" und der zuletzt gespielte Track (11.1).
 * Satzabschluss zeigt 10 s lang Rueckgaengig (12.5).
 */
@Composable
fun SessionScreen(
    contentPadding: PaddingValues,
    onOpenLibrary: () -> Unit,
    onOpenRoutines: () -> Unit,
    onOpenProgress: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: WorkoutViewModel = hiltViewModel(),
) {
    val session by viewModel.activeSession.collectAsStateWithLifecycle()
    val exercises by viewModel.exercises.collectAsStateWithLifecycle()
    val sessionExercises by viewModel.sessionExercises.collectAsStateWithLifecycle()
    val lastCompleted by viewModel.lastCompleted.collectAsStateWithLifecycle()
    val prefills by viewModel.prefills.collectAsStateWithLifecycle()
    val restPrefs by viewModel.restPrefs.collectAsStateWithLifecycle()
    val restPresets by viewModel.restPresetsSeconds.collectAsStateWithLifecycle()
    val lastPlayedTrack by viewModel.lastPlayedTrack.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }
    val undoLabel = stringResource(R.string.workout_undo)
    val completedText = stringResource(R.string.workout_set_saved)

    LaunchedEffect(lastCompleted) {
        val completed = lastCompleted ?: return@LaunchedEffect
        // Nach dem Satz den ggf. erfassten Track nachladen (11.1).
        viewModel.refreshSessionMusic()
        val result =
            snackbarHostState.showSnackbar(
                message = "$completedText ${completed.summary}",
                actionLabel = undoLabel,
                duration = androidx.compose.material3.SnackbarDuration.Long,
            )
        if (result == SnackbarResult.ActionPerformed) {
            viewModel.undoLastCompleted()
        } else {
            viewModel.clearLastCompleted()
        }
    }
    LaunchedEffect(sessionExercises) {
        sessionExercises.forEach(viewModel::loadExerciseExtras)
    }

    Column(modifier = modifier.fillMaxSize()) {
        val active = session
        if (active == null) {
            EmptySessionContent(
                contentPadding = contentPadding,
                onStart = viewModel::startSession,
                onRepeatLast = viewModel::repeatLastSession,
                onOpenLibrary = onOpenLibrary,
                onOpenRoutines = onOpenRoutines,
                onOpenProgress = onOpenProgress,
                modifier = Modifier.weight(1f),
            )
        } else {
            ActiveSessionContent(
                startedAtEpochMs = active.startedAtEpochMs,
                exercises = exercises,
                sessionExercises = sessionExercises,
                prefills = prefills,
                restPrefs = restPrefs,
                restPresets = restPresets,
                lastPlayedTrackLabel =
                    lastPlayedTrack?.let { track ->
                        listOfNotNull(track.artist, track.title).joinToString(" - ")
                    },
                contentPadding = contentPadding,
                onAddExercise = viewModel::addExercise,
                onCompleteSet = viewModel::completeSet,
                onCompleteSession = viewModel::completeSession,
                onDiscardSession = viewModel::discardSession,
                onStartRest = viewModel::startRest,
                onSetRestPref = viewModel::setRestPref,
                onSwapExercise = viewModel::swapExercise,
                onSaveAsRoutine = viewModel::saveSessionAsRoutine,
                onOpenLibrary = onOpenLibrary,
                onOpenProgress = onOpenProgress,
                modifier = Modifier.weight(1f),
            )
        }
        SnackbarHost(hostState = snackbarHostState)
    }
}

@Composable
private fun EmptySessionContent(
    contentPadding: PaddingValues,
    onStart: () -> Unit,
    onRepeatLast: () -> Unit,
    onOpenLibrary: () -> Unit,
    onOpenRoutines: () -> Unit,
    onOpenProgress: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .padding(contentPadding)
                .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.workout_no_session),
            style = MaterialTheme.typography.titleLarge,
        )
        Spacer(Modifier.height(16.dp))
        Button(onClick = onStart, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(stringResource(R.string.workout_start_session))
        }
        Spacer(Modifier.height(8.dp))
        // Letzte Session mit einem Tap wiederholen (9.6).
        OutlinedButton(onClick = onRepeatLast, modifier = Modifier.heightIn(min = 48.dp)) {
            Icon(
                painterResource(BrandIcons.RepeatSession),
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.workout_repeat_last))
        }
        Spacer(Modifier.height(24.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onOpenLibrary) {
                Icon(
                    painterResource(BrandIcons.ExerciseLibrary),
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.workout_open_library))
            }
            TextButton(onClick = onOpenRoutines) {
                Icon(
                    painterResource(BrandIcons.Routines),
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.workout_open_routines))
            }
            TextButton(onClick = onOpenProgress) {
                Icon(
                    painterResource(BrandIcons.Progress),
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.workout_open_progress))
            }
        }
    }
}

@Composable
private fun ActiveSessionContent(
    startedAtEpochMs: Long,
    exercises: List<ExerciseInfo>,
    sessionExercises: List<SessionExerciseInfo>,
    prefills: Map<Long, PrefillUi>,
    restPrefs: Map<Long, RestPref>,
    restPresets: List<Int>,
    lastPlayedTrackLabel: String?,
    contentPadding: PaddingValues,
    onAddExercise: (Long) -> Unit,
    onCompleteSet: (Long, String, String, Boolean, String) -> Unit,
    onCompleteSession: () -> Unit,
    onDiscardSession: () -> Unit,
    onStartRest: (Long) -> Unit,
    onSetRestPref: (Long, Int, RestMode) -> Unit,
    onSwapExercise: (Long, Long, SwapStrategy) -> Unit,
    onSaveAsRoutine: (String) -> Unit,
    onOpenLibrary: () -> Unit,
    onOpenProgress: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showSaveRoutineDialog by rememberSaveable { mutableStateOf(false) }

    if (showSaveRoutineDialog) {
        SaveRoutineDialog(
            onConfirm = { name ->
                onSaveAsRoutine(name)
                showSaveRoutineDialog = false
            },
            onDismiss = { showSaveRoutineDialog = false },
        )
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = contentPadding,
    ) {
        item {
            Column(Modifier.padding(16.dp)) {
                Text(
                    text = stringResource(R.string.workout_active_session),
                    style = MaterialTheme.typography.titleLarge,
                )
                // Datum folgt Locale-Format; intern bleibt UTC-Epoch (12.7).
                Text(
                    text =
                        stringResource(
                            R.string.workout_started_at,
                            DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(startedAtEpochMs)),
                        ),
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (lastPlayedTrackLabel != null) {
                    // Zur Session erfasster Track (11.1); rein informativ.
                    AssistChip(
                        onClick = {},
                        label = {
                            Text(stringResource(R.string.workout_now_playing, lastPlayedTrackLabel))
                        },
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onOpenLibrary) {
                        Text(stringResource(R.string.workout_open_library))
                    }
                    TextButton(onClick = onOpenProgress) {
                        Text(stringResource(R.string.workout_open_progress))
                    }
                    TextButton(onClick = { showSaveRoutineDialog = true }) {
                        Text(stringResource(R.string.workout_save_as_routine))
                    }
                }
            }
        }
        item {
            ExercisePicker(
                exercises = exercises,
                onAddExercise = onAddExercise,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
        items(sessionExercises, key = { it.id }) { sessionExercise ->
            SetEntryCard(
                sessionExercise = sessionExercise,
                prefill = prefills[sessionExercise.id],
                restPref = restPrefs[sessionExercise.exerciseId],
                restPresets = restPresets,
                exercises = exercises,
                onCompleteSet = onCompleteSet,
                onStartRest = onStartRest,
                onSetRestPref = onSetRestPref,
                onSwapExercise = onSwapExercise,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        item {
            Row(
                modifier = Modifier.padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                OutlinedButton(
                    onClick = onDiscardSession,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text(stringResource(R.string.workout_discard_session))
                }
                Button(
                    onClick = onCompleteSession,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text(stringResource(R.string.workout_complete_session))
                }
            }
        }
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
internal fun ExercisePicker(
    exercises: List<ExerciseInfo>,
    onAddExercise: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<ExerciseInfo?>(null) }

    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = it },
            modifier = Modifier.weight(1f),
        ) {
            OutlinedTextField(
                value = selected?.displayName ?: "",
                onValueChange = {},
                readOnly = true,
                label = { Text(stringResource(R.string.workout_pick_exercise)) },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                modifier =
                    Modifier
                        .menuAnchor(
                            androidx.compose.material3.MenuAnchorType.PrimaryNotEditable,
                        ).fillMaxWidth(),
            )
            ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                exercises.forEach { exercise ->
                    DropdownMenuItem(
                        text = { Text(exercise.displayName) },
                        onClick = {
                            selected = exercise
                            expanded = false
                        },
                    )
                }
            }
        }
        Button(
            onClick = { selected?.let { onAddExercise(it.id) } },
            enabled = selected != null,
            modifier =
                Modifier
                    .padding(start = 8.dp)
                    .heightIn(min = 48.dp),
        ) {
            Text(stringResource(R.string.workout_add_exercise))
        }
    }
}

@Composable
private fun SetEntryCard(
    sessionExercise: SessionExerciseInfo,
    prefill: PrefillUi?,
    restPref: RestPref?,
    restPresets: List<Int>,
    exercises: List<ExerciseInfo>,
    onCompleteSet: (Long, String, String, Boolean, String) -> Unit,
    onStartRest: (Long) -> Unit,
    onSetRestPref: (Long, Int, RestMode) -> Unit,
    onSwapExercise: (Long, Long, SwapStrategy) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Prefill fuellt die Felder erst, sobald die Werte geladen sind (9.4).
    var weight by rememberSaveable(sessionExercise.id, prefill) {
        mutableStateOf(prefill?.weight ?: "")
    }
    var reps by rememberSaveable(sessionExercise.id, prefill) {
        mutableStateOf(prefill?.reps ?: "")
    }
    var perHand by rememberSaveable(sessionExercise.id, prefill) {
        mutableStateOf(prefill?.perHand ?: false)
    }
    var showRestDialog by rememberSaveable(sessionExercise.id) { mutableStateOf(false) }
    var showSwapDialog by rememberSaveable(sessionExercise.id) { mutableStateOf(false) }

    if (showRestDialog) {
        RestPrefDialog(
            initial = restPref,
            presets = restPresets,
            onConfirm = { seconds, mode ->
                onSetRestPref(sessionExercise.exerciseId, seconds, mode)
                showRestDialog = false
            },
            onDismiss = { showRestDialog = false },
        )
    }
    if (showSwapDialog) {
        SwapExerciseDialog(
            exercises = exercises.filter { it.id != sessionExercise.exerciseId },
            onConfirm = { newExerciseId, strategy ->
                onSwapExercise(sessionExercise.id, newExerciseId, strategy)
                showSwapDialog = false
            },
            onDismiss = { showSwapDialog = false },
        )
    }

    Card(modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = sessionExercise.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { showSwapDialog = true }) {
                    Text(stringResource(R.string.workout_swap))
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = weight,
                    onValueChange = { weight = it },
                    label = { Text(stringResource(R.string.workout_weight_kg)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = reps,
                    onValueChange = { reps = it },
                    label = { Text(stringResource(R.string.workout_reps)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = perHand, onCheckedChange = { perHand = it })
                // Keine Funktion nur ueber Farbe; expliziter Text (12.1).
                Text(stringResource(R.string.workout_per_hand))
            }
            // Rest-Timer pro Uebung: gemerkte Dauer + Modus (Abschnitt 8).
            Row(verticalAlignment = Alignment.CenterVertically) {
                val restLabel =
                    if (restPref?.restMode == RestMode.DROPSYNC) {
                        stringResource(R.string.workout_rest_mode_dropsync)
                    } else {
                        stringResource(R.string.workout_rest_seconds_label, restPref?.restSeconds ?: 90)
                    }
                Text(text = restLabel, modifier = Modifier.weight(1f))
                TextButton(onClick = { showRestDialog = true }) {
                    Text(stringResource(R.string.workout_rest_edit))
                }
                OutlinedButton(onClick = { onStartRest(sessionExercise.exerciseId) }) {
                    Text(stringResource(R.string.workout_rest_start))
                }
            }
            val summary = "${sessionExercise.displayName}: $weight kg x $reps"
            Button(
                onClick = {
                    onCompleteSet(sessionExercise.id, weight, reps, perHand, summary)
                    weight = ""
                    reps = ""
                },
                enabled = weight.isNotBlank() && reps.isNotBlank(),
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp),
            ) {
                Text(stringResource(R.string.workout_complete_set))
            }
        }
    }
}

/** Restdauer und Rest-Modus (Normal/DropSync) je Uebung aendern. */
@Composable
private fun RestPrefDialog(
    initial: RestPref?,
    presets: List<Int>,
    onConfirm: (Int, RestMode) -> Unit,
    onDismiss: () -> Unit,
) {
    var secondsText by rememberSaveable { mutableStateOf((initial?.restSeconds ?: 90).toString()) }
    var mode by rememberSaveable { mutableStateOf(initial?.restMode ?: RestMode.NORMAL) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.workout_rest_dialog_title)) },
        text = {
            Column {
                OutlinedTextField(
                    value = secondsText,
                    onValueChange = { secondsText = it },
                    label = { Text(stringResource(R.string.workout_rest_seconds)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                )
                // Bearbeitbare Schnellwahl (B8): Tippen fuellt das Sekundenfeld.
                if (presets.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.workout_rest_presets_label),
                        style = MaterialTheme.typography.labelMedium,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        val current = secondsText.trim().toIntOrNull()
                        presets.forEach { preset ->
                            FilterChip(
                                selected = current == preset,
                                onClick = { secondsText = preset.toString() },
                                label = {
                                    Text(stringResource(R.string.workout_rest_seconds_label, preset))
                                },
                            )
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = mode == RestMode.NORMAL,
                        onClick = { mode = RestMode.NORMAL },
                        label = { Text(stringResource(R.string.workout_rest_mode_normal)) },
                    )
                    // DropSync: naechster Satz startet auf dem naechsten Drop (8a).
                    FilterChip(
                        selected = mode == RestMode.DROPSYNC,
                        onClick = { mode = RestMode.DROPSYNC },
                        label = { Text(stringResource(R.string.workout_rest_mode_dropsync)) },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val seconds = secondsText.trim().toIntOrNull() ?: return@TextButton
                    onConfirm(seconds, mode)
                },
            ) {
                Text(stringResource(R.string.workout_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.workout_cancel))
            }
        },
    )
}

/** Uebungstausch mit expliziter Strategie (9.5); MOVE ist Nutzerentscheidung. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun SwapExerciseDialog(
    exercises: List<ExerciseInfo>,
    onConfirm: (Long, SwapStrategy) -> Unit,
    onDismiss: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<ExerciseInfo?>(null) }
    var strategy by rememberSaveable { mutableStateOf(SwapStrategy.KEEP) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.workout_swap_title)) },
        text = {
            Column {
                ExposedDropdownMenuBox(
                    expanded = expanded,
                    onExpandedChange = { expanded = it },
                ) {
                    OutlinedTextField(
                        value = selected?.displayName ?: "",
                        onValueChange = {},
                        readOnly = true,
                        label = { Text(stringResource(R.string.workout_pick_exercise)) },
                        trailingIcon = {
                            ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
                        },
                        modifier =
                            Modifier
                                .menuAnchor(
                                    androidx.compose.material3.MenuAnchorType.PrimaryNotEditable,
                                ).fillMaxWidth(),
                    )
                    ExposedDropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false },
                    ) {
                        exercises.forEach { exercise ->
                            DropdownMenuItem(
                                text = { Text(exercise.displayName) },
                                onClick = {
                                    selected = exercise
                                    expanded = false
                                },
                            )
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                SwapStrategyOption(
                    label = stringResource(R.string.workout_swap_keep),
                    selected = strategy == SwapStrategy.KEEP,
                    onSelect = { strategy = SwapStrategy.KEEP },
                )
                SwapStrategyOption(
                    label = stringResource(R.string.workout_swap_move),
                    selected = strategy == SwapStrategy.MOVE,
                    onSelect = { strategy = SwapStrategy.MOVE },
                )
                SwapStrategyOption(
                    label = stringResource(R.string.workout_swap_discard),
                    selected = strategy == SwapStrategy.DISCARD,
                    onSelect = { strategy = SwapStrategy.DISCARD },
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = selected != null,
                onClick = { selected?.let { onConfirm(it.id, strategy) } },
            ) {
                Text(stringResource(R.string.workout_swap))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.workout_cancel))
            }
        },
    )
}

@Composable
private fun SwapStrategyOption(
    label: String,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = onSelect)
        Text(label)
    }
}

/** Name fuer die aus der Session erzeugte Routine (9.7). */
@Composable
private fun SaveRoutineDialog(
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by rememberSaveable { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.workout_save_as_routine)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.workout_routine_name)) },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank(),
                onClick = { onConfirm(name) },
            ) {
                Text(stringResource(R.string.workout_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.workout_cancel))
            }
        },
    )
}
