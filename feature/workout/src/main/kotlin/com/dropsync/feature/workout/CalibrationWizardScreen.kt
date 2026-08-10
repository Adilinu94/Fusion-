package com.dropsync.feature.workout

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dropsync.core.designsystem.component.BrandButtonPrimary
import com.dropsync.domain.sensor.SensorConnectionState
import com.dropsync.domain.sensor.calibration.CalibrationController

/**
 * Calibration wizard (Fusion Phase 4 step 3): Guided Calibration 2.0 for one
 * exercise on the connected FlowRep chip. Stages: rest -> 1 rep -> 5 reps ->
 * 3 slow reps -> review. On success the profile is persisted and the wizard
 * closes via [onFinished].
 */
@Composable
fun CalibrationWizardScreen(
    exerciseId: Long,
    deviceId: String,
    contentPadding: PaddingValues,
    onFinished: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: CalibrationViewModel = hiltViewModel(),
) {
    val stage by viewModel.stage.collectAsStateWithLifecycle()
    val restGate by viewModel.restGate.collectAsStateWithLifecycle()
    val buffered by viewModel.bufferedSamples.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val saved by viewModel.saved.collectAsStateWithLifecycle()
    val connection by viewModel.connectionState.collectAsStateWithLifecycle()

    LaunchedEffect(exerciseId, deviceId) {
        viewModel.start(exerciseId, deviceId)
    }
    LaunchedEffect(saved) {
        if (saved) onFinished()
    }

    Column(
        modifier =
            modifier
                .fillMaxSize()
                .padding(top = contentPadding.calculateTopPadding())
                .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Kalibrierung", style = MaterialTheme.typography.headlineMedium)

        if (connection != SensorConnectionState.STREAMING &&
            connection != SensorConnectionState.CONNECTED
        ) {
            Text(
                "Kein Chip verbunden. Bitte zuerst den FlowRep-Chip verbinden.",
                color = MaterialTheme.colorScheme.error,
            )
            return@Column
        }

        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stageTitle(stage), style = MaterialTheme.typography.titleMedium)
                Text(stageInstruction(stage), style = MaterialTheme.typography.bodyMedium)

                if (stage == CalibrationController.Stage.REST && restGate != null) {
                    val gate = restGate!!
                    Spacer(Modifier.height(4.dp))
                    LinearProgressIndicator(
                        progress = { (gate.seconds / 2.0).toFloat().coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "Ruhe: ${"%.1f".format(gate.seconds)} s — " +
                            (if (gate.ready) "bereit" else "Arm still halten…"),
                        style = MaterialTheme.typography.bodySmall,
                    )
                } else if (stage != CalibrationController.Stage.REVIEW &&
                    stage != CalibrationController.Stage.DONE
                ) {
                    Text(
                        "$buffered Samples",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }

        Spacer(Modifier.weight(1f))

        when (stage) {
            CalibrationController.Stage.REVIEW -> {
                BrandButtonPrimary(
                    text = "Profil speichern",
                    onClick = { viewModel.confirmAndSave() },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            CalibrationController.Stage.DONE,
            CalibrationController.Stage.FAILED,
            -> {
                Unit
            }

            else -> {
                BrandButtonPrimary(
                    text = "Weiter",
                    onClick = { viewModel.finishStage() },
                    enabled = stage != CalibrationController.Stage.REST || restGate?.ready == true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        TextButton(onClick = onFinished, modifier = Modifier.align(Alignment.CenterHorizontally)) {
            Text("Abbrechen")
        }
    }
}

private fun stageTitle(stage: CalibrationController.Stage): String =
    when (stage) {
        CalibrationController.Stage.REST -> "1/4 Ruhe"
        CalibrationController.Stage.SINGLE_REP -> "2/4 Eine Wiederholung"
        CalibrationController.Stage.KNOWN_SET -> "3/4 Fünf Wiederholungen"
        CalibrationController.Stage.SLOW_SET -> "4/4 Drei langsame Wiederholungen"
        CalibrationController.Stage.REVIEW -> "Fertig"
        CalibrationController.Stage.DONE -> "Gespeichert"
        CalibrationController.Stage.FAILED -> "Fehlgeschlagen"
    }

private fun stageInstruction(stage: CalibrationController.Stage): String =
    when (stage) {
        CalibrationController.Stage.REST -> {
            "Arm in Startposition still halten (mind. 2 s), dann Weiter."
        }

        CalibrationController.Stage.SINGLE_REP -> {
            "Genau 1 deutliche Wiederholung ausführen, dann Weiter."
        }

        CalibrationController.Stage.KNOWN_SET -> {
            "5 Wiederholungen in normalem Tempo (20–30 s), dann Weiter."
        }

        CalibrationController.Stage.SLOW_SET -> {
            "3 bewusst langsame Wiederholungen, dann Weiter."
        }

        CalibrationController.Stage.REVIEW -> {
            "Kalibrierung berechnet. Profil speichern?"
        }

        CalibrationController.Stage.DONE -> {
            "Profil gespeichert."
        }

        CalibrationController.Stage.FAILED -> {
            "Bitte erneut versuchen."
        }
    }
