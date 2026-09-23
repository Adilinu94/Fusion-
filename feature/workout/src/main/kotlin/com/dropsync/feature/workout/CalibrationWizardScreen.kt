package com.dropsync.feature.workout

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dropsync.core.designsystem.component.BrandButtonPrimary
import com.dropsync.core.designsystem.component.FlowRepTopBar
import com.dropsync.core.designsystem.theme.rememberAccentTextColor
import com.dropsync.domain.sensor.SensorConnectionState
import com.dropsync.domain.sensor.calibration.CalibrationController
import com.dropsync.domain.sensor.calibration.CalibrationFailure
import com.dropsync.domain.sensor.calibration.CalibrationReview

/**
 * Calibration wizard 2.0 (RC-8, Fusion Phase 4 step 3): Guided Calibration
 * for one exercise on the connected FlowRep chip. Stages: rest -> 1 rep ->
 * 5 reps -> 3 slow reps -> review. On success the profile is persisted and
 * the wizard closes via [onFinished].
 *
 * RC-8 additions over the text-list wizard:
 * - Stepper with the five stages, done/current/pending.
 * - Live signal (shared [SensorWaveform]) and "Reps detected: n of target"
 *   during the collecting stages; "Next" only becomes tappable once the
 *   stage contains usable signal.
 * - Failure reason plus an explicit "Repeat this stage" action; the review
 *   can send the user back to the 5-rep or slow set without redoing rest
 *   and single rep.
 * - The review explains the quality in facts (reps recovered, spacing,
 *   threshold over noise, motion channel, expected duration) instead of a
 *   bare percentage.
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
    val error by viewModel.error.collectAsStateWithLifecycle()
    val saved by viewModel.saved.collectAsStateWithLifecycle()
    val qualityScore by viewModel.qualityScore.collectAsStateWithLifecycle()
    val review by viewModel.review.collectAsStateWithLifecycle()
    val liveRepEstimate by viewModel.liveRepEstimate.collectAsStateWithLifecycle()
    val stageTarget by viewModel.stageTarget.collectAsStateWithLifecycle()
    val advanceEnabled by viewModel.advanceEnabled.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val connection by viewModel.connectionState.collectAsStateWithLifecycle()
    val waveform by viewModel.waveform.collectAsStateWithLifecycle()

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
                .padding(horizontal = 24.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // 7.2/9: einheitliches App-Bar-Muster statt eigener ArrowBack-Reihe.
        FlowRepTopBar(
            title = stringResource(R.string.calibration_title),
            onBack = onFinished,
            backContentDescription = stringResource(R.string.a11y_close_calibration),
        )

        if (connection != SensorConnectionState.STREAMING &&
            connection != SensorConnectionState.CONNECTED
        ) {
            Text(
                stringResource(R.string.calibration_no_chip),
                color = MaterialTheme.colorScheme.error,
            )
            return@Column
        }

        // Inhalt scrollt, Bedienleiste bleibt unten stehen: das Review mit
        // seinen Fakten passt sonst auf kleinen Geraeten nicht mehr.
        Column(
            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            WizardStepper(stage = stage)

            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stageTitle(stage), style = MaterialTheme.typography.titleMedium)
                    Text(stageInstruction(stage), style = MaterialTheme.typography.bodyMedium)

                    when (stage) {
                        CalibrationController.Stage.REST -> {
                            RestStageBody(restGate = restGate)
                        }

                        CalibrationController.Stage.SINGLE_REP,
                        CalibrationController.Stage.KNOWN_SET,
                        CalibrationController.Stage.SLOW_SET,
                        -> {
                            RepStageBody(
                                estimate = liveRepEstimate ?: 0,
                                target = stageTarget,
                            )
                        }

                        CalibrationController.Stage.REVIEW -> {
                            ReviewStageBody(
                                qualityScore = qualityScore,
                                review = review,
                            )
                        }

                        CalibrationController.Stage.DONE,
                        CalibrationController.Stage.FAILED,
                        -> {
                            Unit
                        }
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

            // RC-8: Live-Signal waehrend der Sammel-Stufen. Im Review gibt es
            // nichts mehr zu beobachten — dort zaehlen die Fakten.
            if (showLiveSignal(connection, stage)) {
                SensorWaveform(samples = waveform)
            }
        }

        WizardControls(
            stage = stage,
            busy = busy,
            advanceEnabled = advanceEnabled,
            review = review,
            onNext = { viewModel.finishStage() },
            onRepeatStage = { viewModel.repeatStage() },
            onRedoStage = { viewModel.redoStage(it) },
            onSave = { viewModel.confirmAndSave() },
            onCancel = onFinished,
        )
    }
}

/**
 * RC-8: Live-Signal nur, solange gesammelt wird (Ruhe bis Langsam-Satz) und
 * der Chip streamt. Ausgelagert, damit die Bedingung im Screen lesbar bleibt.
 */
private fun showLiveSignal(
    connection: SensorConnectionState,
    stage: CalibrationController.Stage,
): Boolean {
    if (connection != SensorConnectionState.STREAMING) return false
    return stage in COLLECTING_STAGES
}

/** Stufen, in denen Samples gesammelt werden (Live-Signal sichtbar). */
private val COLLECTING_STAGES =
    listOf(
        CalibrationController.Stage.REST,
        CalibrationController.Stage.SINGLE_REP,
        CalibrationController.Stage.KNOWN_SET,
        CalibrationController.Stage.SLOW_SET,
    )

/**
 * RC-8: Stepper ueber die fuenf Stufen. Abgeschlossene Stufen zeigen ein
 * Haekchen, die aktuelle ist hervorgehoben. Jeder Schritt traegt eine
 * Beschreibung fuer TalkBack ("Schritt 3 von 5: Fuenf Wiederholungen").
 */
@Composable
private fun WizardStepper(
    stage: CalibrationController.Stage,
    modifier: Modifier = Modifier,
) {
    val currentIndex =
        when (stage) {
            CalibrationController.Stage.REST -> 0

            CalibrationController.Stage.SINGLE_REP -> 1

            CalibrationController.Stage.KNOWN_SET -> 2

            CalibrationController.Stage.SLOW_SET -> 3

            CalibrationController.Stage.REVIEW,
            CalibrationController.Stage.DONE,
            -> 4

            CalibrationController.Stage.FAILED -> 0
        }
    val labels =
        listOf(
            stringResource(R.string.calibration_step_rest),
            stringResource(R.string.calibration_step_single_rep),
            stringResource(R.string.calibration_step_known_set),
            stringResource(R.string.calibration_step_slow_set),
            stringResource(R.string.calibration_step_review),
        )
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        labels.forEachIndexed { index, label ->
            val done = index < currentIndex
            val current = index == currentIndex
            val stepDescription =
                stringResource(
                    R.string.calibration_step_a11y,
                    index + 1,
                    labels.size,
                    label,
                    stringResource(
                        if (current) R.string.calibration_step_current else R.string.calibration_step_state,
                    ),
                )
            Column(
                modifier = Modifier.weight(1f).semantics { contentDescription = stepDescription },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                StepDot(
                    number = index + 1,
                    done = done,
                    current = current,
                )
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color =
                        if (current) {
                            rememberAccentTextColor()
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    fontWeight = if (current) FontWeight.Bold else FontWeight.Normal,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun StepDot(
    number: Int,
    done: Boolean,
    current: Boolean,
) {
    val filled = done || current
    val fillColor = if (filled) MaterialTheme.colorScheme.primary else Color.Transparent
    val contentColor = if (filled) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        modifier =
            Modifier
                .size(28.dp)
                .background(color = fillColor, shape = CircleShape)
                .border(
                    width = 1.dp,
                    color = if (filled) Color.Transparent else MaterialTheme.colorScheme.outline,
                    shape = CircleShape,
                ),
        contentAlignment = Alignment.Center,
    ) {
        if (done) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.size(16.dp),
            )
        } else {
            Text(
                text = number.toString(),
                style = MaterialTheme.typography.labelMedium,
                color = contentColor,
            )
        }
    }
}

@Composable
private fun RestStageBody(restGate: com.dropsync.domain.sensor.calibration.RestGateSnapshot?) {
    val gate = restGate ?: return
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
}

/** RC-8: Live-Rueckmeldung der Rep-Stufen ("Reps erkannt: n von Ziel"). */
@Composable
private fun RepStageBody(
    estimate: Int,
    target: Int?,
) {
    val targetText = target?.toString() ?: "–"
    Text(
        text = stringResource(R.string.calibration_reps_detected, estimate, targetText),
        style = MaterialTheme.typography.titleMedium,
        color =
            if (target != null && estimate >= target) {
                rememberAccentTextColor()
            } else {
                MaterialTheme.colorScheme.onSurface
            },
        // C5/T-9: Die Rep-Stufe ist eine Live-Anzeige — TalkBack bekommt jede
        // Aenderung als Ansage statt eines stillen Zahlenwechsels.
        modifier =
            Modifier.semantics {
                liveRegion = LiveRegionMode.Polite
            },
    )
    Text(
        text = stringResource(R.string.calibration_reps_estimate_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** RC-8: Review erklaert die Qualitaet in nachrechenbaren Fakten. */
@Composable
private fun ReviewStageBody(
    qualityScore: Double?,
    review: CalibrationReview?,
) {
    val q = qualityScore
    if (q != null) {
        val label =
            when {
                q >= 0.7 -> stringResource(R.string.calibration_quality_good)
                q >= 0.4 -> stringResource(R.string.calibration_quality_medium)
                else -> stringResource(R.string.calibration_quality_weak)
            }
        Text(
            stringResource(R.string.calibration_quality, label, q * 100),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (review == null) {
        Text(
            stringResource(R.string.calibration_review_no_result),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
        return
    }
    ReviewFact(
        text =
            stringResource(
                R.string.calibration_review_reps_found,
                review.detectedRepsKnownSet,
                review.expectedRepsKnownSet,
            ),
        warn = review.detectedRepsKnownSet != review.expectedRepsKnownSet,
    )
    // Lokale Kopien: die Review-Felder kommen aus einem anderen Modul und
    // sind fuer den Compiler nicht smart-castbar.
    val slowDetected = review.detectedRepsSlowSet
    val slowExpected = review.expectedRepsSlowSet
    if (slowDetected != null && slowExpected != null) {
        ReviewFact(
            text =
                stringResource(
                    R.string.calibration_review_reps_found_slow,
                    slowDetected,
                    slowExpected,
                ),
            warn = slowDetected != slowExpected,
        )
    }
    review.intervalCv?.let { cv ->
        ReviewFact(
            text =
                stringResource(
                    if (cv < IRREGULAR_CV) {
                        R.string.calibration_review_regular
                    } else {
                        R.string.calibration_review_irregular
                    },
                    cv * 100,
                ),
            warn = cv >= IRREGULAR_CV,
        )
    }
    ReviewFact(
        text =
            stringResource(
                if (review.thresholdOverNoise < LOW_THRESHOLD_RATIO) {
                    R.string.calibration_review_threshold_low
                } else {
                    R.string.calibration_review_threshold
                },
                review.thresholdOverNoise,
            ),
        warn = review.thresholdOverNoise < LOW_THRESHOLD_RATIO,
    )
    ReviewFact(
        text =
            if (review.accelVotingEnabled) {
                stringResource(R.string.calibration_review_accel_on)
            } else {
                stringResource(R.string.calibration_review_accel_off)
            },
        warn = false,
    )
    ReviewFact(
        text = stringResource(R.string.calibration_review_duration, review.expectedRepSeconds),
        warn = false,
    )
}

@Composable
private fun ReviewFact(
    text: String,
    warn: Boolean,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = if (warn) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** Streuung der Rep-Abstaende, ab der das Review warnt. */
private const val IRREGULAR_CV = 0.3

/** Verhaeltnis Schwelle/Ruherauschen, unter dem das Review warnt. */
private const val LOW_THRESHOLD_RATIO = 3.0

/**
 * RC-8: Bedienleiste. Sammel-Stufen: "Weiter" (nur bei erfuellter Stufe) plus
 * "Stufe wiederholen". Review: speichern, und bei Bedarf zurueck in den
 * 5er- oder Langsam-Satz — ohne Ruhe und Einzel-Rep zu wiederholen.
 */
@Composable
private fun WizardControls(
    stage: CalibrationController.Stage,
    busy: Boolean,
    advanceEnabled: Boolean,
    review: CalibrationReview?,
    onNext: () -> Unit,
    onRepeatStage: () -> Unit,
    onRedoStage: (CalibrationController.Stage) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        when (stage) {
            CalibrationController.Stage.REVIEW -> {
                if (review == null) {
                    BrandButtonPrimary(
                        text = stringResource(R.string.calibration_repeat_known_set),
                        onClick = { onRedoStage(CalibrationController.Stage.KNOWN_SET) },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    BrandButtonPrimary(
                        text = stringResource(R.string.calibration_save_profile),
                        onClick = onSave,
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (review.detectedRepsKnownSet != review.expectedRepsKnownSet) {
                        TextButton(
                            onClick = { onRedoStage(CalibrationController.Stage.KNOWN_SET) },
                            enabled = !busy,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(R.string.calibration_repeat_known_set))
                        }
                    }
                    if (review.detectedRepsSlowSet != null &&
                        review.detectedRepsSlowSet != review.expectedRepsSlowSet
                    ) {
                        TextButton(
                            onClick = { onRedoStage(CalibrationController.Stage.SLOW_SET) },
                            enabled = !busy,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(R.string.calibration_repeat_slow_set))
                        }
                    }
                }
            }

            CalibrationController.Stage.DONE,
            CalibrationController.Stage.FAILED,
            -> {
                Unit
            }

            else -> {
                BrandButtonPrimary(
                    text = stringResource(R.string.calibration_next),
                    onClick = onNext,
                    enabled = advanceEnabled,
                    modifier = Modifier.fillMaxWidth(),
                )
                TextButton(
                    onClick = onRepeatStage,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.calibration_repeat_stage))
                }
            }
        }

        TextButton(onClick = onCancel, modifier = Modifier.align(Alignment.CenterHorizontally)) {
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
