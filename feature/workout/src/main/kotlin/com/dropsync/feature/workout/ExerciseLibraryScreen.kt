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
import com.dropsync.domain.workout.MuscleContribution

/**
 * Uebungsbibliothek (Schritt 9.1/9.2): Liste mit Equipment und
 * Primaermuskel-Badge, Suche und Neuanlage eigener Uebungen.
 */
@Composable
fun ExerciseLibraryScreen(
    contentPadding: PaddingValues,
    onOpenExercise: (Long) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ExerciseLibraryViewModel = hiltViewModel(),
) {
    val items by viewModel.items.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    var showCreateDialog by rememberSaveable { mutableStateOf(false) }

    if (showCreateDialog) {
        CreateExerciseDialog(
            onConfirm = { nameDe, nameEn, kind, equipment, muscles ->
                viewModel.createExercise(nameDe, nameEn, kind, equipment, muscles)
                showCreateDialog = false
            },
            onDismiss = { showCreateDialog = false },
        )
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = contentPadding,
    ) {
        item {
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
                    onValueChange = viewModel::setQuery,
                    label = { Text(stringResource(R.string.library_search)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Button(onClick = { showCreateDialog = true }) {
                    Text(stringResource(R.string.library_new_exercise))
                }
            }
        }
        items(items, key = { it.id }) { item ->
            Card(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                        .clickable { onOpenExercise(item.id) },
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
