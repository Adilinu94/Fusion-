package com.dropsync.feature.timer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
@Composable
fun TimerSection(
    modifier: Modifier = Modifier,
    viewModel: TimerViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

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

    Card(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(16.dp)
                .semantics { stateDescription = statusText },
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            when (state.status) {
                TimerStatus.IDLE -> {
                    Text(
                        text = stringResource(R.string.timer_rest_title),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    // Start als Lime-Pill: die Presets sind die Primaeraktion
                    // des Timers (Design.txt: Lime nur fuer die Primaeraktion).
                    Row(
                        modifier = Modifier.padding(top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        REST_PRESETS_SECONDS.forEach { seconds ->
                            Button(
                                onClick = { viewModel.startRest(seconds * 1_000L) },
                                modifier = Modifier.heightIn(min = 48.dp),
                            ) {
                                Text(stringResource(R.string.timer_preset_seconds, seconds))
                            }
                        }
                    }
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
                    Row(
                        modifier = Modifier.padding(top = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        if (state.status == TimerStatus.RUNNING) {
                            OutlinedButton(
                                onClick = viewModel::pause,
                                modifier = Modifier.heightIn(min = 48.dp),
                            ) {
                                Text(stringResource(R.string.timer_pause))
                            }
                        } else if (state.status == TimerStatus.PAUSED) {
                            Button(
                                onClick = viewModel::resume,
                                modifier = Modifier.heightIn(min = 48.dp),
                            ) {
                                Text(stringResource(R.string.timer_resume))
                            }
                        }
                        OutlinedButton(
                            onClick = viewModel::cancel,
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) {
                            Text(stringResource(R.string.timer_cancel))
                        }
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
                    Button(
                        onClick = viewModel::acknowledgeFinished,
                        modifier =
                            Modifier
                                .padding(top = 8.dp)
                                .heightIn(min = 48.dp),
                    ) {
                        Text(stringResource(R.string.timer_ok))
                    }
                }
            }
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
