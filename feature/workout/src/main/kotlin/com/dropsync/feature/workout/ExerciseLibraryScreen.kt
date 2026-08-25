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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
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
import com.dropsync.core.model.Equipment
import com.dropsync.core.model.ExerciseKind
import com.dropsync.core.model.MuscleGroup
import com.dropsync.domain.workout.ExerciseLibraryItem
import com.dropsync.domain.workout.ExerciseTarget
import com.dropsync.domain.workout.MuscleContribution
import java.util.Locale

/**
 * Uebungsbibliothek (Schritt 9.1/9.2): Liste mit Equipment und
 * Primaermuskel-Badge, Suche, Neuanlage eigener Uebungen und Ziel-Pflege
 * (Entscheidung 13 — Ziele leben hier, der TrainScreen bleibt frei).
 */
@Composable
fun ExerciseLibraryScreen(
    contentPadding: PaddingValues,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ExerciseLibraryViewModel = hiltViewModel(),
) {
    val items by viewModel.items.collectAsStateWithLifecycle()
    val archivedItems by viewModel.archivedItems.collectAsStateWithLifecycle()
    val targets by viewModel.targets.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    var showCreateDialog by rememberSaveable { mutableStateOf(false) }
    // Ziel-Dialog haelt nur die ID; Name und aktuelles Ziel kommen aus dem
    // Zustand, damit eine Aenderung von aussen nicht im Dialog einfriert.
    var goalDialogExerciseId by rememberSaveable { mutableStateOf<Long?>(null) }

    if (showCreateDialog) {
        CreateExerciseDialog(
            onConfirm = { nameDe, nameEn, kind, equipment, muscles ->
                viewModel.createExercise(nameDe, nameEn, kind, equipment, muscles)
                showCreateDialog = false
            },
            onDismiss = { showCreateDialog = false },
        )
    }

    goalDialogExerciseId?.let { exerciseId ->
        val item = items.firstOrNull { it.id == exerciseId }
        if (item == null) {
            // Uebung ist waehrend des offenen Dialogs verschwunden
            // (archiviert, Suche): Dialog schliessen statt leer zeigen.
            goalDialogExerciseId = null
        } else {
            GoalDialog(
                exerciseName = item.displayName,
                current = targets[exerciseId],
                onConfirm = { weight, reps ->
                    viewModel.setTarget(exerciseId, weight, reps)
                    goalDialogExerciseId = null
                },
                onClear = {
                    viewModel.clearTarget(exerciseId)
                    goalDialogExerciseId = null
                },
                onDismiss = { goalDialogExerciseId = null },
            )
        }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = contentPadding,
    ) {
        item {
            LibraryHeader(
                query = query,
                onQueryChange = viewModel::setQuery,
                onNewExercise = { showCreateDialog = true },
                onBack = onBack,
            )
        }
        items(items, key = { it.id }) { item ->
            ExerciseCard(
                item = item,
                target = targets[item.id],
                onOpenGoal = { goalDialogExerciseId = item.id },
                onArchive = { viewModel.archiveExercise(item.id) },
            )
        }
        if (archivedItems.isNotEmpty()) {
            item {
                Text(
                    text = stringResource(R.string.library_archived_section),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 4.dp),
                )
            }
            items(archivedItems, key = { "archived_${it.id}" }) { item ->
                Card(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            text = item.displayName,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        TextButton(onClick = { viewModel.restoreExercise(item.id) }) {
                            Text(stringResource(R.string.library_restore))
                        }
                    }
                }
            }
        }
    }
}

/** Kopf der Bibliothek: Titel, Suche, Neuanlage. */
@Composable
private fun LibraryHeader(
    query: String,
    onQueryChange: (String) -> Unit,
    onNewExercise: () -> Unit,
    onBack: () -> Unit,
) {
    Column(Modifier.padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.library_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onBack) {
                Text(stringResource(R.string.workout_back))
            }
        }
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            label = { Text(stringResource(R.string.library_search)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        Button(onClick = onNewExercise) {
            Text(stringResource(R.string.library_new_exercise))
        }
    }
}

/**
 * Eine Uebungskarte: Name, Equipment, gesetztes Ziel und die beiden
 * Aktionen. Der Tap auf die Karte oeffnet den Ziel-Dialog (R5b) — ein Tap
 * statt vier. Ohne das sieht man „10 kg fehlen", denkt „das Ziel ist zu
 * niedrig", und aendert es nie, weil der Weg zu lang ist.
 */
@Composable
private fun ExerciseCard(
    item: ExerciseLibraryItem,
    target: ExerciseTarget?,
    onOpenGoal: () -> Unit,
    onArchive: () -> Unit,
) {
    Card(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp)
                .clickable(onClick = onOpenGoal),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                text = item.displayName,
                style = MaterialTheme.typography.titleMedium,
            )
            val suffix =
                if (item.isCustom) {
                    " - " + stringResource(R.string.library_custom_badge)
                } else {
                    ""
                }
            Text(
                text = item.equipment.name + suffix,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // Gesetzte Ziele zeigen ihren Wert, damit man nicht den Dialog
            // oeffnen muss, um ihn zu sehen. Violett, weil Ziel (R1).
            if (target != null) {
                Text(
                    text =
                        stringResource(
                            R.string.library_goal_current,
                            formatGoalWeight(target.targetWeightMilliKg),
                            target.targetReps,
                        ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.secondary,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onOpenGoal) {
                    Text(
                        stringResource(
                            if (target == null) R.string.library_goal_set else R.string.library_goal_edit,
                        ),
                    )
                }
                // Schritt 7: Archivieren ist die Loesch-Alternative mit
                // Umkehrweg — die Historie der Saetze bleibt unangetastet.
                TextButton(onClick = onArchive) {
                    Text(stringResource(R.string.library_archive))
                }
            }
        }
    }
}

/** Zeile des Muskel-Mappings im Anlage-Dialog (Gruppe + Prozent 1..100). */
private data class MuscleRowState(
    val group: MuscleGroup,
    val percentText: String,
)

/**
 * Ziel-Dialog (Entscheidung 13/14): Zielgewicht und Ziel-Wiederholungen.
 * Beide Felder sind Pflicht — ein Ziel mit nur einer Dimension waere kein
 * Ziel im Sinne von E4, wo ein einzelner Satz beides schaffen muss.
 */
@Composable
private fun GoalDialog(
    exerciseName: String,
    current: ExerciseTarget?,
    onConfirm: (String, String) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
) {
    // Vorbelegung aus dem bestehenden Ziel: Ein Ziel wird eher angepasst als
    // neu getippt.
    var weightText by rememberSaveable(exerciseName) {
        mutableStateOf(current?.let { formatGoalWeight(it.targetWeightMilliKg) } ?: "")
    }
    var repsText by rememberSaveable(exerciseName) {
        mutableStateOf(current?.targetReps?.toString() ?: "")
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.library_goal_dialog_title, exerciseName)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = weightText,
                    onValueChange = { weightText = it },
                    label = { Text(stringResource(R.string.library_goal_weight)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = repsText,
                    onValueChange = { repsText = it },
                    label = { Text(stringResource(R.string.library_goal_reps)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                )
                Text(
                    text = stringResource(R.string.library_goal_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = isValidGoalInput(weightText, repsText),
                onClick = { onConfirm(weightText, repsText) },
            ) {
                Text(stringResource(R.string.library_goal_save))
            }
        },
        dismissButton = {
            Row {
                if (current != null) {
                    TextButton(onClick = onClear) {
                        Text(stringResource(R.string.library_goal_clear))
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.workout_cancel))
                }
            }
        },
    )
}

/**
 * Zielgewicht fuer Anzeige und Vorbelegung: ganzzahlig ohne Dezimalstelle,
 * krumme Werte mit einer (E4c/R2b). `95,0 kg` ist verboten.
 */
private fun formatGoalWeight(milliKg: Long): String =
    if (milliKg % 1000 == 0L) {
        (milliKg / 1000).toString()
    } else {
        String.format(Locale.getDefault(), "%.1f", milliKg / 1000.0)
    }

/**
 * Speichern erst, wenn beide Felder tragfaehig sind. Ein Ziel von 0 kg oder
 * 0 Reps waere sofort erfuellt und die Statuszeile saehe erreicht aus, ohne
 * dass trainiert wurde.
 */
private fun isValidGoalInput(
    weightText: String,
    repsText: String,
): Boolean {
    val weight = weightText.trim().replace(',', '.').toDoubleOrNull() ?: return false
    val reps = repsText.trim().toIntOrNull() ?: return false
    return weight > 0.0 && reps > 0
}

/**
 * Neuanlage einer eigenen Uebung (9.2): Name (de/en), Art, Equipment und
 * Muskelbeitraege in Prozent.
 */
@Composable
private fun CreateExerciseDialog(
    onConfirm: (String, String, ExerciseKind, Equipment, List<MuscleContribution>) -> Unit,
    onDismiss: () -> Unit,
) {
    var nameDe by rememberSaveable { mutableStateOf("") }
    var nameEn by rememberSaveable { mutableStateOf("") }
    var kind by rememberSaveable { mutableStateOf(ExerciseKind.STRENGTH) }
    var equipment by rememberSaveable { mutableStateOf(Equipment.BARBELL) }
    val muscleRows =
        remember {
            mutableStateListOf(MuscleRowState(MuscleGroup.CHEST, "100"))
        }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.library_new_exercise)) },
        text = {
            LazyColumn {
                item {
                    OutlinedTextField(
                        value = nameDe,
                        onValueChange = { nameDe = it },
                        label = { Text(stringResource(R.string.library_name_de)) },
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = nameEn,
                        onValueChange = { nameEn = it },
                        label = { Text(stringResource(R.string.library_name_en)) },
                        singleLine = true,
                    )
                    EnumDropdown(
                        label = stringResource(R.string.library_kind),
                        options = ExerciseKind.entries.map { it.name },
                        selected = kind.name,
                        onSelect = { kind = ExerciseKind.valueOf(it) },
                    )
                    EnumDropdown(
                        label = stringResource(R.string.library_equipment),
                        options = Equipment.entries.map { it.name },
                        selected = equipment.name,
                        onSelect = { equipment = Equipment.valueOf(it) },
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.library_muscles),
                        style = MaterialTheme.typography.titleSmall,
                    )
                }
                items(muscleRows.size) { index ->
                    val row = muscleRows[index]
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        EnumDropdown(
                            label = stringResource(R.string.library_muscle),
                            options = MuscleGroup.entries.map { it.name },
                            selected = row.group.name,
                            onSelect = {
                                muscleRows[index] = row.copy(group = MuscleGroup.valueOf(it))
                            },
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedTextField(
                            value = row.percentText,
                            onValueChange = { muscleRows[index] = row.copy(percentText = it) },
                            label = { Text(stringResource(R.string.library_percent)) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            modifier = Modifier.weight(0.6f),
                        )
                    }
                }
                item {
                    TextButton(onClick = { muscleRows.add(MuscleRowState(MuscleGroup.OTHER, "50")) }) {
                        Text(stringResource(R.string.library_add_muscle))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = nameDe.isNotBlank() && nameEn.isNotBlank(),
                onClick = {
                    val muscles =
                        muscleRows.mapNotNull { row ->
                            val percent = row.percentText.trim().toIntOrNull() ?: return@mapNotNull null
                            if (percent in 1..100) MuscleContribution(row.group, percent) else null
                        }
                    onConfirm(nameDe, nameEn, kind, equipment, muscles)
                },
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

/** Generischer Dropdown fuer stabile Enum-Schluessel (9.2). */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
internal fun EnumDropdown(
    label: String,
    options: List<String>,
    selected: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = modifier,
    ) {
        OutlinedTextField(
            value = selected,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier =
                Modifier
                    .menuAnchor(
                        androidx.compose.material3.MenuAnchorType.PrimaryNotEditable,
                    ).fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option) },
                    onClick = {
                        onSelect(option)
                        expanded = false
                    },
                )
            }
        }
    }
}
