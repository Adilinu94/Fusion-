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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dropsync.core.designsystem.component.BrandButtonPrimary
import com.dropsync.core.designsystem.component.FlowRepIconButton
import com.dropsync.domain.sensor.SensorConnectionState
import com.dropsync.domain.sensor.calibration.CalibrationController
import com.dropsync.domain.sensor.calibration.CalibrationFailure

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
    val qualityScore by viewModel.qualityScore.collectAsStateWithLifecycle()
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
        // P3-Fix #28: sichtbare Zurueck-Affordance statt eines alleinstehenden
        // Textbuttons am Seitenende. AutoMirrored funktioniert auch in RTL.
        Row(verticalAlignment = Alignment.CenterVertically) {
            FlowRepIconButton(
                icon = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = stringResource(R.string.a11y_close_calibration),
                onClick = onFinished,
            )
            Text(
                text = stringResource(R.string.calibration_title),
                style = MaterialTheme.typography.headlineMedium,
            )
        }

        if (connection != SensorConnectionState.STREAMING &&
            connection != SensorConnectionState.CONNECTED
        ) {
            Text(
                stringResource(R.string.calibration_no_chip),
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
                        stringResource(
                            R.string.calibration_rest_progress,
                            gate.seconds,
                            stringResource(
                                if (gate.ready) R.string.calibration_rest_ready else R.string.calibration_rest_hold,
                            ),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                    )
                } else if (stage == CalibrationController.Stage.REVIEW && qualityScore != null) {
                    val q = qualityScore!!
                    val label =
                        when {
                            q >= 0.7 -> stringResource(R.string.calibration_quality_good)
                            q >= 0.4 -> stringResource(R.string.calibration_quality_medium)
                            else -> stringResource(R.string.calibration_quality_weak)
                        }
                    Text(
                        stringResource(
                            R.string.calibration_quality,
                            label,
                            q * 100,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else if (stage != CalibrationController.Stage.REVIEW &&
                    stage != CalibrationController.Stage.DONE
                ) {
                    Text(
                        stringResource(R.string.calibration_samples, buffered),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        error?.let {
            Text(
                calibrationErrorText(it),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }

        Spacer(Modifier.weight(1f))

        when (stage) {
            CalibrationController.Stage.REVIEW -> {
                BrandButtonPrimary(
                    text = stringResource(R.string.calibration_save_profile),
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
                    text = stringResource(R.string.calibration_next),
                    onClick = { viewModel.finishStage() },
                    enabled = stage != CalibrationController.Stage.REST || restGate?.ready == true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        TextButton(onClick = onFinished, modifier = Modifier.align(Alignment.CenterHorizontally)) {
            Text(stringResource(R.string.workout_cancel))
        }
    }
}

@Composable
private fun stageTitle(stage: CalibrationController.Stage): String =
    when (stage) {
        CalibrationController.Stage.REST -> stringResource(R.string.calibration_stage_rest)
        CalibrationController.Stage.SINGLE_REP -> stringResource(R.string.calibration_stage_single_rep)
        CalibrationController.Stage.KNOWN_SET -> stringResource(R.string.calibration_stage_known_set)
        CalibrationController.Stage.SLOW_SET -> stringResource(R.string.calibration_stage_slow_set)
        CalibrationController.Stage.REVIEW -> stringResource(R.string.calibration_stage_review)
        CalibrationController.Stage.DONE -> stringResource(R.string.calibration_stage_done)
        CalibrationController.Stage.FAILED -> stringResource(R.string.calibration_stage_failed)
    }

@Composable
private fun stageInstruction(stage: CalibrationController.Stage): String =
    when (stage) {
        CalibrationController.Stage.REST -> stringResource(R.string.calibration_instruction_rest)
        CalibrationController.Stage.SINGLE_REP -> stringResource(R.string.calibration_instruction_single_rep)
        CalibrationController.Stage.KNOWN_SET -> stringResource(R.string.calibration_instruction_known_set)
        CalibrationController.Stage.SLOW_SET -> stringResource(R.string.calibration_instruction_slow_set)
        CalibrationController.Stage.REVIEW -> stringResource(R.string.calibration_instruction_review)
        CalibrationController.Stage.DONE -> stringResource(R.string.calibration_instruction_done)
        CalibrationController.Stage.FAILED -> stringResource(R.string.calibration_instruction_failed)
    }

/** P3-Fix #27: Locale-abhaengiger Text fuer den typisierten Fehler. */
@Composable
private fun calibrationErrorText(error: CalibrationUiError): String =
    when (error) {
        CalibrationUiError.Incomplete -> {
            stringResource(R.string.calibration_error_incomplete)
        }

        CalibrationUiError.SaveFailed -> {
            stringResource(R.string.calibration_error_save_failed)
        }

        is CalibrationUiError.GateFailure -> {
            when (val failure = error.failure) {
                is CalibrationFailure.RestTooShort -> {
                    stringResource(
                        R.string.calibration_error_rest_too_short,
                        failure.measuredSeconds,
                        failure.requiredSeconds.toInt(),
                    )
                }

                is CalibrationFailure.RestGateFailed -> {
                    val reasons =
                        buildList {
                            failure.gyroMagMean?.let {
                                add(
                                    stringResource(
                                        R.string.calibration_error_rest_gate_gyro,
                                        it,
                                    ),
                                )
                            }
                            failure.accelSigma?.let {
                                add(
                                    stringResource(
                                        R.string.calibration_error_rest_gate_accel,
                                        it,
                                    ),
                                )
                            }
                        }
                    stringResource(R.string.calibration_error_rest_gate, reasons.joinToString("; "))
                }

                CalibrationFailure.NoMotionWindow -> {
                    stringResource(R.string.calibration_error_no_motion_window)
                }
            }
        }
    }
