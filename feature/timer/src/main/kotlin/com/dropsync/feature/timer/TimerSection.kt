package com.dropsync.feature.timer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dropsync.core.designsystem.component.BrandButtonGhost
import com.dropsync.core.designsystem.component.FlowRepPrimaryButton
import com.dropsync.core.designsystem.component.FlowRepSurface
import com.dropsync.core.designsystem.component.ProgressRing
import com.dropsync.core.designsystem.icon.BrandIcons
import com.dropsync.domain.timer.TimerStatus
import java.util.Locale

/**
 * Resttimer-Karte im Trainingskontext (Schritt 12.3). Der Timerstatus
 * nutzt `stateDescription`; der sekuendliche Countdown erzeugt keine
 * ununterbrochenen TalkBack-Ansagen (12.4), weil nur der Status, nicht
 * der Zahlenwert als Zustandsbeschreibung gemeldet wird.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TimerSection(
    modifier: Modifier = Modifier,
    viewModel: TimerViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var selectedSeconds by remember { mutableIntStateOf(90) }

    val statusText =
        when (state.status) {
            TimerStatus.IDLE -> {
                stringResource(R.string.timer_state_idle)
            }

            TimerStatus.PREPARING -> {
                stringResource(R.string.timer_state_preparing)
            }

            TimerStatus.RUNNING -> {
                stringResource(R.string.timer_state_running)
            }

            TimerStatus.PAUSED -> {
                stringResource(R.string.timer_state_paused)
            }

            TimerStatus.COMPLETED -> {
                stringResource(R.string.timer_state_completed)
            }

            TimerStatus.CANCELLED, TimerStatus.FAILED -> {
                stringResource(R.string.timer_state_cancelled)
            }
        }

    FlowRepSurface(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(16.dp)
                .semantics { stateDescription = statusText },
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            when (state.status) {
                TimerStatus.IDLE -> {
                    Text(
                        text = stringResource(R.string.timer_rest_title),
                        style = MaterialTheme.typography.headlineMedium,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    TimerWheel(
                        seconds = selectedSeconds,
                        onSecondsChange = { selectedSeconds = it },
                        modifier = Modifier.padding(top = 32.dp),
                    )
                    FlowRow(
                        modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        REST_PRESETS_SECONDS.forEach { seconds ->
                            BrandButtonGhost(
                                text = stringResource(R.string.timer_preset_seconds, seconds),
                                onClick = { selectedSeconds = seconds },
                            )
                        }
                    }
                    FlowRepPrimaryButton(
                        text = "TIMER STARTEN",
                        onClick = { viewModel.startRest(selectedSeconds * 1_000L) },
                        modifier = Modifier.padding(top = 24.dp),
                    )
                }

                TimerStatus.RUNNING, TimerStatus.PAUSED, TimerStatus.PREPARING -> {
                    // Lime-Ring um die grosse Restzeit (Design.txt
                    // "Progress Ring"); der Ring leert sich mit der Restzeit.
                    val totalMs = state.session?.durationMs ?: 0L
                    val ringProgress =
                        if (totalMs > 0) {
                            (state.remainingMs.toFloat() / totalMs).coerceIn(0f, 1f)
                        } else {
                            0f
                        }
                    ProgressRing(
                        progress = ringProgress,
                        ringSize = 200.dp,
                        strokeWidth = 12.dp,
                    ) {
                        Text(
                            text = formatRemaining(state.remainingMs),
                            style = MaterialTheme.typography.displayMedium,
                        )
                    }
                    Column(modifier = Modifier.padding(top = 24.dp)) {
                        if (state.status == TimerStatus.RUNNING) {
                            FlowRepPrimaryButton(
                                text = stringResource(R.string.timer_pause),
                                onClick = viewModel::pause,
                            )
                        } else if (state.status == TimerStatus.PAUSED) {
                            FlowRepPrimaryButton(
                                text = stringResource(R.string.timer_resume),
                                onClick = viewModel::resume,
                            )
                        }
                        BrandButtonGhost(
                            text = stringResource(R.string.timer_cancel),
                            onClick = viewModel::cancel,
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        )
                    }
                }

                TimerStatus.COMPLETED, TimerStatus.CANCELLED, TimerStatus.FAILED -> {
                    // Peak-End: der volle Lime-Ring mit Haken belohnt das
                    // durchgestandene Satzende (nur bei echtem Abschluss).
                    if (state.status == TimerStatus.COMPLETED) {
                        ProgressRing(
                            progress = 1f,
                            ringSize = 96.dp,
                            strokeWidth = 8.dp,
                        ) {
                            Icon(
                                painterResource(BrandIcons.SetComplete),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(40.dp),
                            )
                        }
                    }
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    FlowRepPrimaryButton(
                        text = stringResource(R.string.timer_ok),
                        onClick = viewModel::acknowledgeFinished,
                        modifier = Modifier.padding(top = 16.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun TimerWheel(
    seconds: Int,
    onSecondsChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val minutes = seconds / 60
    val remainder = seconds % 60
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TimerWheelColumn(
            label = "STD",
            previousValue = "23",
            value = "00",
            nextValue = "01",
            onPrevious = {},
            onNext = {},
            enabled = false,
        )
        TimerWheelColumn(
            label = "MIN",
            previousValue = "%02d".format((minutes - 1).coerceAtLeast(0)),
            value = "%02d".format(minutes),
            nextValue = "%02d".format((minutes + 1).coerceAtMost(59)),
            onPrevious = { onSecondsChange((seconds - 60).coerceAtLeast(0)) },
            onNext = { onSecondsChange((seconds + 60).coerceAtMost(3_599)) },
        )
        TimerWheelColumn(
            label = "SEK",
            previousValue = "%02d".format((remainder - 15).coerceAtLeast(0)),
            value = "%02d".format(remainder),
            nextValue = "%02d".format((remainder + 15).coerceAtMost(59)),
            onPrevious = { onSecondsChange((seconds - 15).coerceAtLeast(0)) },
            onNext = { onSecondsChange((seconds + 15).coerceAtMost(3_599)) },
        )
    }
}

@Composable
private fun TimerWheelColumn(
    label: String,
    previousValue: String,
    value: String,
    nextValue: String,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    enabled: Boolean = true,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        TextButton(onClick = onPrevious, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(
                text = previousValue,
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
            )
        }
        Text(
            text = value,
            style = MaterialTheme.typography.displayMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(vertical = 8.dp),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = onNext, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(
                text = nextValue,
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
            )
        }
    }
}

/** Feste Rest-Presets in Sekunden (TimerPreset nur NORMAL/REST). */
private val REST_PRESETS_SECONDS = listOf(60, 90, 120, 180)

internal fun formatRemaining(remainingMs: Long): String {
    val totalSeconds = (remainingMs + 999) / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return String.format(Locale.ROOT, "%d:%02d", minutes, seconds)
}
