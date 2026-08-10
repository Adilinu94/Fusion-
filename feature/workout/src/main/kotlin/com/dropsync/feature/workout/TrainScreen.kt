package com.dropsync.feature.workout

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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dropsync.core.designsystem.component.BrandButtonPrimary
import com.dropsync.domain.workout.ExerciseInfo

/**
 * Train-Tab (FlowRep-Design Phase 2): flaches Satz-Log ohne Session.
 * Uebungs-Chip, Gewicht +/-2.5, Rep-Eingabe, Satz speichern.
 */
@Composable
fun TrainScreen(
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
    viewModel: TrainViewModel = hiltViewModel(),
) {
    val exercises by viewModel.exercises.collectAsStateWithLifecycle()
    val selectedExercise by viewModel.selectedExercise.collectAsStateWithLifecycle()
    val lastSet by viewModel.lastSet.collectAsStateWithLifecycle()
    val recentSets by viewModel.recentSets.collectAsStateWithLifecycle()
    val weightInput by viewModel.weightInput.collectAsStateWithLifecycle()
    val repsInput by viewModel.repsInput.collectAsStateWithLifecycle()

    Column(
        modifier =
            modifier
                .fillMaxSize()
                .padding(top = contentPadding.calculateTopPadding()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Uebungs-Chip
        ExerciseChipRow(
            exercises = exercises,
            selectedId = selectedExercise?.id,
            onSelect = { viewModel.selectExercise(it) },
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
                    lastWeightKg = lastSet?.let { it.weightMilliKg / 1000.0 },
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
            }
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
                    text = "${set.reps} x ${set.weightMilliKg / 1000.0} kg",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
        }
    }
}

@Composable
private fun ExerciseChipRow(
    exercises: List<ExerciseInfo>,
    selectedId: Long?,
    onSelect: (ExerciseInfo) -> Unit,
) {
    Row(
        modifier = Modifier.padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        exercises.take(5).forEach { exercise ->
            FilterChip(
                selected = selectedId == exercise.id,
                onClick = { onSelect(exercise) },
                label = { Text(exercise.displayName) },
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
