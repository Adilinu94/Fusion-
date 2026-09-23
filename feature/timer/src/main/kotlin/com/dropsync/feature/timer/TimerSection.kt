package com.dropsync.feature.timer

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dropsync.core.designsystem.component.BrandButtonGhost
import com.dropsync.core.designsystem.component.FlowRepPrimaryButton
import com.dropsync.core.designsystem.component.FlowRepSurface
import com.dropsync.core.designsystem.component.ProgressRing
import com.dropsync.core.designsystem.icon.BrandIcons
import com.dropsync.core.designsystem.theme.rememberAccentTextColor
import com.dropsync.domain.timer.DropLandingPlanner
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
    // B8/Befund 3.4: Presets aus den Einstellungen statt hartkodiert.
    val presets by viewModel.restPresetsSeconds.collectAsStateWithLifecycle()
    // C15 (PR-3): globaler Drop-Auto-Schalter (auch im Timer sichtbar).
    val dropAutoEnabled by viewModel.dropAutoEnabled.collectAsStateWithLifecycle()
    var selectedSeconds by remember { mutableIntStateOf(90) }

    // Befund 3.4: POST_NOTIFICATIONS auch in der Timer-Route anfragen.
    // Der Train-Tab fragt mit Kontextkarte an; hier reicht die direkte
    // Anfrage beim ersten Start, damit der Countdown beim dunklen
    // Bildschirm sichtbar bleibt und nicht still fehlt.
    val context = LocalContext.current
    val notificationPermissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val ensureNotificationPermission: () -> Unit = {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

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
                        presets.forEach { seconds ->
                            BrandButtonGhost(
                                text = stringResource(R.string.timer_preset_seconds, seconds),
                                onClick = { selectedSeconds = seconds },
                            )
                        }
                    }
                    FlowRepPrimaryButton(
                        text = stringResource(R.string.timer_start),
                        onClick = {
                            ensureNotificationPermission()
                            viewModel.startRest(selectedSeconds * 1_000L)
                        },
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
                        // C15 (PR-3): DropSync fuer die laufende Pause
                        // einschalten (plant sofort); unter einer Minute
                        // nicht angeboten (PR-4).
                        if (state.status == TimerStatus.RUNNING) {
                            TimerDropAutoRow(
                                checked = dropAutoEnabled,
                                blocked = state.remainingMs < DropLandingPlanner.MIN_DROP_AUTO_REST_MS,
                                onCheckedChange = viewModel::setDropAutoEnabled,
                            )
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
                                tint = rememberAccentTextColor(),
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

/**
 * C15 (PR-3): DropSync-Schalter der laufenden Pause im Standalone-Timer.
 * Unter einer Minute ausgegraut mit Grund (PR-4); Ausschalten bleibt frei.
 */
@Composable
private fun TimerDropAutoRow(
    checked: Boolean,
    blocked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.timer_drop_auto),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                enabled = checked || !blocked,
            )
        }
        Text(
            text =
                if (blocked && !checked) {
                    stringResource(R.string.timer_drop_auto_blocked)
                } else {
                    stringResource(R.string.timer_drop_auto_hint)
                },
            style = MaterialTheme.typography.bodySmall,
            color =
                if (blocked && !checked) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
        )
    }
}

@Composable
internal fun TimerWheel(
    seconds: Int,
    onSecondsChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val (hours, minutes, remainder) = splitTimerSeconds(seconds)
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // B2: Die Stunden-Spalte war deaktiviert (00/01-Placeholder) — jetzt
        // echte Stunden 0..23 in Stunden-Schritten, Maximum 23:59:59.
        TimerWheelColumn(
            label = stringResource(R.string.timer_wheel_hours),
            previousValue = "%02d".format((hours - 1).coerceAtLeast(0)),
            value = "%02d".format(hours),
            nextValue = "%02d".format((hours + 1).coerceAtMost(23)),
            onPrevious = { onSecondsChange(shiftTimerSeconds(seconds, -3_600)) },
            onNext = { onSecondsChange(shiftTimerSeconds(seconds, 3_600)) },
        )
        TimerWheelColumn(
            label = stringResource(R.string.timer_wheel_minutes),
            previousValue = "%02d".format((minutes - 1).coerceAtLeast(0)),
            value = "%02d".format(minutes),
            nextValue = "%02d".format((minutes + 1).coerceAtMost(59)),
            onPrevious = { onSecondsChange(shiftTimerSeconds(seconds, -60)) },
            onNext = { onSecondsChange(shiftTimerSeconds(seconds, 60)) },
        )
        TimerWheelColumn(
            label = stringResource(R.string.timer_wheel_seconds),
            previousValue = "%02d".format((remainder - 15).coerceAtLeast(0)),
            value = "%02d".format(remainder),
            nextValue = "%02d".format((remainder + 15).coerceAtMost(59)),
            onPrevious = { onSecondsChange(shiftTimerSeconds(seconds, -15)) },
            onNext = { onSecondsChange(shiftTimerSeconds(seconds, 15)) },
        )
    }
}

/** Obergrenze des Stellrads: 23:59:59 (B2). */
internal const val MAX_TIMER_SECONDS: Int = 86_399

/** Zerlegt Gesamtsekunden in Stunden/Minuten/Restsekunden (B2, testbar). */
internal fun splitTimerSeconds(totalSeconds: Int): Triple<Int, Int, Int> {
    val clamped = totalSeconds.coerceIn(0, MAX_TIMER_SECONDS)
    return Triple(clamped / 3_600, (clamped % 3_600) / 60, clamped % 60)
}

/** Verschiebt die Stellrad-Zeit um [deltaSeconds], geklemmt (B2, testbar). */
internal fun shiftTimerSeconds(
    currentSeconds: Int,
    deltaSeconds: Int,
): Int = (currentSeconds + deltaSeconds).coerceIn(0, MAX_TIMER_SECONDS)

@Composable
private fun TimerWheelColumn(
    label: String,
    previousValue: String,
    value: String,
    nextValue: String,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
) {
    // C5 (U-8): Die Stellrad-Knoepfe nennen Richtung, Wert UND Einheit —
    // vorher las TalkBack nur eine nackte Zahl vor.
    val decreaseText = stringResource(R.string.timer_wheel_decrease, previousValue, label)
    val increaseText = stringResource(R.string.timer_wheel_increase, nextValue, label)
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        TextButton(
            onClick = onPrevious,
            modifier =
                Modifier
                    .heightIn(min = 48.dp)
                    .semantics { contentDescription = decreaseText },
        ) {
            Text(
                text = previousValue,
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
            )
        }
        Text(
            text = value,
            style = MaterialTheme.typography.displayMedium,
            color = rememberAccentTextColor(),
            modifier = Modifier.padding(vertical = 8.dp),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(
            onClick = onNext,
            modifier =
                Modifier
                    .heightIn(min = 48.dp)
                    .semantics { contentDescription = increaseText },
        ) {
            Text(
                text = nextValue,
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
            )
        }
    }
}

internal fun formatRemaining(remainingMs: Long): String {
    val totalSeconds = (remainingMs + 999) / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return String.format(Locale.ROOT, "%d:%02d", minutes, seconds)
}
