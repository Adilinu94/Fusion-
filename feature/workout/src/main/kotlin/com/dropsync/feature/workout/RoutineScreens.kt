package com.dropsync.feature.workout

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dropsync.domain.workout.ExerciseInfo

/** Routinenliste (Schritt 9.7): oeffnen, daraus starten, neu anlegen. */
@Composable
fun RoutinesScreen(
    contentPadding: PaddingValues,
    onOpenRoutine: (Long) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: RoutinesViewModel = hiltViewModel(),
) {
    val routines by viewModel.routines.collectAsStateWithLifecycle()

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = contentPadding,
    ) {
        item {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(R.string.routines_title),
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onBack) {
                        Text(stringResource(R.string.workout_back))
                    }
                }
                // routineId 0 = Neuanlage im Editor.
                Button(onClick = { onOpenRoutine(0L) }) {
                    Text(stringResource(R.string.routines_new))
                }
            }
        }
        if (routines.isEmpty()) {
            item {
                Text(
                    text = stringResource(R.string.routines_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
        }
        items(routines, key = { it.id }) { routine ->
            Card(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                        .clickable { onOpenRoutine(routine.id) },
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = routine.name,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedButton(onClick = { viewModel.startFromRoutine(routine.id) }) {
                        Text(stringResource(R.string.routines_start))
                    }
                }
            }
        }
    }
}

/**
 * Routinendetail bzw. Editor (Schritt 9.7): routineId == 0 stellt eine
 * neue Routine aus Uebungen zusammen; sonst Ansicht mit Session-Start.
 */
@Composable
fun RoutineEditScreen(
    contentPadding: PaddingValues,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: RoutineEditViewModel = hiltViewModel(),
) {
    val detail by viewModel.detail.collectAsStateWithLifecycle()
    val exercises by viewModel.exercises.collectAsStateWithLifecycle()
    val draftEntries by viewModel.draftEntries.collectAsStateWithLifecycle()
    val saved by viewModel.saved.collectAsStateWithLifecycle()

    LaunchedEffect(saved) {
        if (saved) onBack()
    }

    if (viewModel.routineId > 0) {
        RoutineDetailContent(
            contentPadding = contentPadding,
            name = detail?.name ?: "",
            exerciseLines =
                detail?.exercises.orEmpty().map { entry ->
                    val sets = entry.targetSets?.let { " - $it x" } ?: ""
                    val rest = entry.restSeconds?.let { " - $it s" } ?: ""
                    entry.displayName + sets + rest
                },
            onStart = viewModel::startSessionFromRoutine,
            onBack = onBack,
            modifier = modifier,
        )
    } else {
        RoutineCreateContent(
            contentPadding = contentPadding,
            exercises = exercises,
            draftEntries = draftEntries,
            onAddEntry = viewModel::addDraftEntry,
            onRemoveEntry = viewModel::removeDraftEntry,
            onSave = viewModel::saveRoutine,
            onBack = onBack,
            modifier = modifier,
        )
    }
}

@Composable
private fun RoutineDetailContent(
    contentPadding: PaddingValues,
    name: String,
    exerciseLines: List<String>,
    onStart: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = contentPadding,
    ) {
        item {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = name,
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onBack) {
                        Text(stringResource(R.string.workout_back))
                    }
                }
                Button(onClick = onStart, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.routines_start))
                }
            }
        }
        items(exerciseLines) { line ->
            Card(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
            ) {
                Text(
                    text = line,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
    }
}

@Composable
private fun RoutineCreateContent(
    contentPadding: PaddingValues,
    exercises: List<ExerciseInfo>,
    draftEntries: List<RoutineDraftEntry>,
    onAddEntry: (ExerciseInfo, Int, Int?) -> Unit,
    onRemoveEntry: (Int) -> Unit,
    onSave: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var name by rememberSaveable { mutableStateOf("") }
    var selected by remember { mutableStateOf<ExerciseInfo?>(null) }
    var setsText by rememberSaveable { mutableStateOf("3") }
    var restText by rememberSaveable { mutableStateOf("90") }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = contentPadding,
    ) {
        item {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(R.string.routines_new),
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onBack) {
                        Text(stringResource(R.string.workout_back))
                    }
                }
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.workout_routine_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                EnumDropdown(
                    label = stringResource(R.string.workout_pick_exercise),
                    options = exercises.map { it.displayName },
                    selected = selected?.displayName ?: "",
                    onSelect = { pickedName ->
                        selected = exercises.firstOrNull { it.displayName == pickedName }
                    },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = setsText,
                        onValueChange = { setsText = it },
                        label = { Text(stringResource(R.string.routines_target_sets)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = restText,
                        onValueChange = { restText = it },
                        label = { Text(stringResource(R.string.workout_rest_seconds)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                }
                TextButton(
                    enabled = selected != null,
                    onClick = {
                        val exercise = selected ?: return@TextButton
                        val sets = setsText.trim().toIntOrNull() ?: return@TextButton
                        onAddEntry(exercise, sets, restText.trim().toIntOrNull())
                    },
                ) {
                    Text(stringResource(R.string.routines_add_exercise))
                }
            }
        }
        itemsIndexed(draftEntries) { index, entry ->
            Card(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text =
                            "${entry.displayName} - ${entry.targetSets} x" +
                                (entry.restSeconds?.let { " - $it s" } ?: ""),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { onRemoveEntry(index) }) {
                        Text(stringResource(R.string.routines_remove))
                    }
                }
            }
        }
        item {
            Button(
                enabled = name.isNotBlank() && draftEntries.isNotEmpty(),
                onClick = { onSave(name) },
                modifier =
                    Modifier
                        .padding(16.dp)
                        .heightIn(min = 48.dp),
            ) {
                Text(stringResource(R.string.workout_save))
            }
        }
    }
}
