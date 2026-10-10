package com.dropsync.feature.workout

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dropsync.core.designsystem.component.BrandButtonGhost
import com.dropsync.core.designsystem.component.BrandButtonPrimary
import com.dropsync.core.designsystem.component.FlowRepPrimaryButton
import com.dropsync.core.designsystem.component.FlowRepSectionHeader
import com.dropsync.core.designsystem.component.FlowRepSurface
import com.dropsync.core.designsystem.theme.isWide
import com.dropsync.core.designsystem.theme.rememberAccentTextColor
import com.dropsync.core.designsystem.theme.rememberWindowWidthSizeClass
import com.dropsync.core.model.RestMode
import com.dropsync.domain.sensor.ActiveSetPhase
import com.dropsync.domain.sensor.RepRejectionReason
import com.dropsync.domain.sensor.SensorConnectionState
import com.dropsync.domain.sensor.SensorErrorReason
import com.dropsync.domain.sensor.SetDiagnostics
import com.dropsync.domain.sensor.SignalQuality
import com.dropsync.domain.sensor.calibration.ProfileLearningEvent
import com.dropsync.domain.timer.DropLandingPlanner
import com.dropsync.domain.timer.DropSyncMode
import com.dropsync.domain.timer.DropSyncState
import com.dropsync.domain.timer.TimerMode
import com.dropsync.domain.timer.TimerStatus
import com.dropsync.domain.timer.TimingConfidence
import com.dropsync.domain.workout.ExerciseInfo
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Rest-timer pill inside the train card (Phase 3 step 4): countdown plus
 * End-rest / finish-exercise controls. Both actions cancel the timer immediately
 * (design rule step 5).
 *
 * Tap auf die Countdown-Anzeige oeffnet den standalone Resttimer
 * (`:feature:timer`): dort gibt es Pause/Weiter und den grossen Ring, die
 * Pille hier bleibt kompakt. Beide treiben dieselbe geteilte TimerEngine —
 * der Zustand bleibt konsistent, egal wo gesteuert wird.
 *
 * P1-10: Die Konsole ist der gemeinsame Hero fuer Normal-Rest und DropSync
 * (A.4): Kopf `REST`/`DROPSYNC`, grosse Zeit, darunter Track · Marker ·
 * "Ziel in mm:ss" plus Statuschips, kontextabhaengige Aktionen und nach der
 * Landung ein kurzes GO-Overlay.
 */
@Composable
internal fun RestConsole(
    remainingMs: Long,
    mode: TimerMode?,
    timerStatus: TimerStatus,
    dropSyncState: DropSyncState,
    restDuckDb: Double,
    dropAutoChecked: Boolean,
    onAddTime: () -> Unit,
    onSkip: () -> Unit,
    onCancelPlan: () -> Unit,
    onFinish: () -> Unit,
    onOpenTimer: () -> Unit,
    onSetRestDuckDb: (Double) -> Unit,
    onSetDropAuto: (Boolean) -> Unit,
    lastSetText: String? = null,
    modifier: Modifier = Modifier,
) {
    // MP-6: `+15 s` ist bei einem DropSync-Rest wirkungslos (die Dauer folgt
    // dem Marker). Der Knopf wird dort nicht angeboten, statt still nichts
    // zu tun.
    val canExtend = mode != TimerMode.DROPSYNC
    val canCancelPlan = dropSyncCanCancelPlan(dropSyncState)
    // P1-10/A5: "GO" nach der Landung — kurz sichtbar, ohne Navigation.
    // Das Zuruecksetzen liegt im finally: wechselt der DropSync-Zustand
    // waehrend der Anzeige (Replan, Abbruch, neuer Plan), wird der Effekt
    // abgebrochen — ohne finally bliebe das Overlay haengen.
    var showGoOverlay by remember { mutableStateOf(false) }
    LaunchedEffect(dropSyncState) {
        if (dropSyncState is DropSyncState.Landed) {
            showGoOverlay = true
            try {
                delay(GO_OVERLAY_MS)
            } finally {
                showGoOverlay = false
            }
        } else {
            showGoOverlay = false
        }
    }
    FlowRepSurface(modifier = modifier.fillMaxWidth()) {
        Box {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                RestConsoleHeader(
                    dropSyncState = dropSyncState,
                    remainingMs = remainingMs,
                    onOpenTimer = onOpenTimer,
                )
                // Der gerade geloggte Satz bleibt waehrend der Pause sichtbar (Gewicht x Wdh.),
                // damit man nicht zurueckblaettern muss, um das naechste Ziel zu bestimmen.
                lastSetText?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                RestConsoleActions(
                    canExtend = canExtend,
                    canCancelPlan = canCancelPlan,
                    onAddTime = onAddTime,
                    onCancelPlan = onCancelPlan,
                    onSkip = onSkip,
                    onFinish = onFinish,
                )
                // C15 (PR-3): DropSync fuer die laufende Pause einschalten
                // (plant sofort); unter einer Minute nicht angeboten (PR-4).
                if (timerStatus == TimerStatus.RUNNING && mode == TimerMode.REST) {
                    RestDropAutoRow(
                        checked = dropAutoChecked,
                        blocked = remainingMs < DropLandingPlanner.MIN_DROP_AUTO_REST_MS,
                        onCheckedChange = onSetDropAuto,
                    )
                }
                // C3 (P-3/MP-9): Ducking der Pausenmusik am Ort — dieselbe
                // DSP-Quelle wie die Einstellungen (kein zweiter Wert).
                //
                // 2026-09-27 (Befund 6.11 / 12.3): **nur vor** der Pause.
                // Mitten in der Pause standen sieben Chips mit hörbarem
                // Effekt direkt unter dem Timer — die größte
                // Fehlbedienungsgefahr auf dem Screen. Wer in der Pause
                // den Ducking-Wert ändert, hört die Musik sofort anders,
                // mitten in der Erholung. Und die Einstellung selbst hat
                // dort nichts verloren: sie gehört zum Training, nicht zur
                // laufenden Pause.
                if (timerStatus != TimerStatus.RUNNING) {
                    RestDuckRow(restDuckDb = restDuckDb, onSetRestDuckDb = onSetRestDuckDb)
                }
            }
            if (showGoOverlay) {
                GoOverlay(
                    onDismiss = { showGoOverlay = false },
                    modifier = Modifier.matchParentSize(),
                )
            }
        }
    }
}

/**
 * P1-10: Kopf des Heros — Label (`REST`/`DROPSYNC`), `Track · Marker ·
 * Ziel in mm:ss`, Statuschips und die grosse Restzeit (Tap oeffnet den
 * Standalone-Timer).
 */
@Composable
private fun RestConsoleHeader(
    dropSyncState: DropSyncState,
    remainingMs: Long,
    onOpenTimer: () -> Unit,
) {
    // P1-10: Der Plan-Zustand des Koordinators faerbt Kopfzeile und Chips;
    // die Restzeit bleibt die grosse Zahl (eine Primaerinformation).
    val dropSyncActive = dropSyncState is DropSyncState.Planned || dropSyncState is DropSyncState.Armed
    val dropSyncLabel =
        when (dropSyncState) {
            is DropSyncState.Planned, is DropSyncState.Armed -> dropSyncState.headline()
            is DropSyncState.BestEffort -> stringResource(R.string.train_rest_dropsync_best_effort)
            is DropSyncState.Overridden -> stringResource(R.string.train_rest_dropsync_overridden)
            is DropSyncState.Failed -> stringResource(R.string.train_rest_dropsync_unavailable)
            else -> null
        }
    val targetMs = dropTargetRemainingMs(dropSyncState, remainingMs)
    Text(
        text =
            if (dropSyncActive) {
                stringResource(R.string.train_rest_label_dropsync)
            } else {
                stringResource(R.string.train_rest_label)
            },
        style = MaterialTheme.typography.labelMedium,
        color = rememberAccentTextColor(),
    )
    dropSyncLabel?.let { label ->
        val line =
            targetMs?.let {
                "$label · ${stringResource(R.string.train_rest_dropsync_target, formatRestRemaining(it))}"
            } ?: label
        // C5 (T-8): Die Zeile wird als ganzer Satz vorgelesen, nicht als
        // Zahlenkolonne ("DropSync: Track. Ziel in 1:27.").
        val a11yLine =
            targetMs?.let {
                stringResource(R.string.train_rest_dropsync_a11y, label, formatRestRemaining(it))
            } ?: label
        Text(
            text = line,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier =
                Modifier
                    .padding(top = 2.dp)
                    .semantics(mergeDescendants = true) { contentDescription = a11yLine },
        )
    }
    DropSyncStatusChips(dropSyncState)
    // C5 (T-8): Die grosse Restzeit bekommt eine gesprochene Zustandszeile
    // ("Pause laeuft, noch 1 Minute 27 Sekunden"), damit TalkBack nicht nur
    // "1:27" vorliest.
    val a11yRest = stringResource(R.string.train_rest_a11y_running, spokenRestDuration(remainingMs))
    Text(
        text = formatRestRemaining(remainingMs),
        style = MaterialTheme.typography.displayLarge,
        fontWeight = FontWeight.Bold,
        textAlign = TextAlign.Center,
        // onClickLabel statt eigener contentDescription: TalkBack liest
        // weiter die Restzeit vor und nennt zusaetzlich die Aktion. Eine
        // contentDescription wuerde die Zeit ersetzen.
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(vertical = 16.dp)
                .semantics { stateDescription = a11yRest }
                .clickable(
                    role = Role.Button,
                    onClickLabel = stringResource(R.string.train_rest_open_timer),
                ) { onOpenTimer() },
    )
}

/** C5: Restzeit als gesprochener Satz ("1 Minute 27 Sekunden"). */
@Composable
private fun spokenRestDuration(ms: Long): String {
    val totalSeconds = ms.coerceAtLeast(0L) / 1000
    val minutes = (totalSeconds / 60).toInt()
    val seconds = (totalSeconds % 60).toInt()
    return if (minutes > 0) {
        val minutePart = pluralStringResource(R.plurals.train_rest_spoken_minutes, minutes, minutes)
        val secondPart = pluralStringResource(R.plurals.train_rest_spoken_seconds, seconds, seconds)
        "$minutePart $secondPart"
    } else {
        pluralStringResource(R.plurals.train_rest_spoken_seconds, seconds, seconds)
    }
}

/**
 * P1-10: Aktionen des Heros — kontextabhaengig: `+15 s` nur bei REST,
 * `Plan abbrechen` nur bei einer Landung am Pausenende; `Pause beenden`
 * und `Uebung beenden` immer.
 */
@Composable
private fun RestConsoleActions(
    canExtend: Boolean,
    canCancelPlan: Boolean,
    onAddTime: () -> Unit,
    onCancelPlan: () -> Unit,
    onSkip: () -> Unit,
    onFinish: () -> Unit,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        if (maxWidth < 480.dp) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (canExtend) {
                    BrandButtonGhost(
                        text = stringResource(R.string.train_rest_add_long),
                        onClick = onAddTime,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (canCancelPlan) {
                    BrandButtonGhost(
                        text = stringResource(R.string.train_rest_cancel_plan),
                        onClick = onCancelPlan,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                FlowRepPrimaryButton(text = stringResource(R.string.train_rest_end), onClick = onSkip)
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (canExtend) {
                    BrandButtonGhost(
                        text = stringResource(R.string.train_rest_add_short),
                        onClick = onAddTime,
                        modifier = Modifier.weight(1f),
                    )
                }
                if (canCancelPlan) {
                    BrandButtonGhost(
                        text = stringResource(R.string.train_rest_cancel_plan),
                        onClick = onCancelPlan,
                        modifier = Modifier.weight(1f),
                    )
                }
                FlowRepPrimaryButton(
                    text = stringResource(R.string.train_rest_end),
                    onClick = onSkip,
                    modifier = Modifier.weight(if (canExtend || canCancelPlan) 1.5f else 1f),
                )
            }
        }
    }
    Spacer(Modifier.height(20.dp))
    BrandButtonGhost(
        text = stringResource(R.string.train_exercise_end),
        onClick = onFinish,
        modifier = Modifier.fillMaxWidth(),
    )
}

/**
 * C15 (PR-3): DropSync-Schalter der laufenden Pause. Unter einer Minute
 * ausgegraut mit Grund (PR-4); Ausschalten bleibt immer moeglich.
 */
@Composable
private fun RestDropAutoRow(
    checked: Boolean,
    blocked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.train_rest_drop_switch),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                enabled = checked || !blocked,
            )
        }
        if (blocked && !checked) {
            Text(
                text = stringResource(R.string.train_rest_drop_switch_blocked),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

/**
 * C3 (P-3/MP-9): Ducking der Pausenmusik direkt in der Rest-Konsole.
 * Liest und schreibt `DspConfig.restDuckDb` — dieselbe Quelle wie die
 * Einstellungen, damit beide Orte nie auseinanderlaufen.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RestDuckRow(
    restDuckDb: Double,
    onSetRestDuckDb: (Double) -> Unit,
) {
    val steps = listOf(0.0, -2.0, -4.0, -6.0, -8.0, -10.0, -12.0)
    Column(modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
        Text(
            text = stringResource(R.string.train_rest_duck_title, restDuckDb.roundToInt()),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(top = 4.dp),
        ) {
            steps.forEach { value ->
                FilterChip(
                    selected = restDuckDb == value,
                    onClick = { onSetRestDuckDb(value) },
                    label = { Text("${value.roundToInt()} dB") },
                    // A5: 48-dp-Mindesthoehe fuers Touch-Ziel.
                    modifier = Modifier.heightIn(min = 48.dp),
                )
            }
        }
    }
}

/**
 * C3 (5.3): Pausendauer (60/90/120/180) und Musikmodus (Normal/DropSync)
 * je Uebung. Der Bereitschaftsgrund steht direkt am DropSync-Chip, wenn
 * Drop-Auto nichts bewirken kann.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun RestPrefDialog(
    restSeconds: Int,
    restMode: RestMode,
    readiness: DropAutoReadiness,
    onDismiss: () -> Unit,
    onConfirm: (Int, RestMode) -> Unit,
) {
    var seconds by remember(restSeconds) { mutableStateOf(restSeconds) }
    var mode by remember(restMode) { mutableStateOf(restMode) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.train_rest_pref_title)) },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.train_rest_pref_duration),
                    style = MaterialTheme.typography.labelMedium,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(top = 4.dp),
                ) {
                    listOf(60, 90, 120, 180).forEach { value ->
                        FilterChip(
                            selected = seconds == value,
                            onClick = { seconds = value },
                            label = { Text(stringResource(R.string.train_rest_pref_seconds, value)) },
                            modifier = Modifier.heightIn(min = 48.dp),
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))
                Text(
                    text = stringResource(R.string.train_rest_pref_mode),
                    style = MaterialTheme.typography.labelMedium,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(top = 4.dp),
                ) {
                    FilterChip(
                        selected = mode == RestMode.NORMAL,
                        onClick = { mode = RestMode.NORMAL },
                        label = { Text(stringResource(R.string.train_rest_pref_mode_normal)) },
                        modifier = Modifier.heightIn(min = 48.dp),
                    )
                    FilterChip(
                        selected = mode == RestMode.DROPSYNC,
                        onClick = { mode = RestMode.DROPSYNC },
                        label = { Text(stringResource(R.string.train_rest_pref_mode_dropsync)) },
                        modifier = Modifier.heightIn(min = 48.dp),
                    )
                }
                if (mode == RestMode.DROPSYNC && readiness is DropAutoReadiness.Blocked) {
                    Text(
                        text =
                            stringResource(
                                when (readiness.reason) {
                                    DropAutoBlockReason.NO_REST_PLAYLIST -> {
                                        R.string.train_rest_pref_blocked_no_rest_playlist
                                    }

                                    DropAutoBlockReason.NO_WORK_PLAYLIST -> {
                                        R.string.train_rest_pref_blocked_no_work_playlist
                                    }
                                },
                            ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(seconds, mode) }) {
                Text(stringResource(R.string.train_rest_pref_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.train_rest_pref_cancel))
            }
        },
    )
}

/** P1-10/A5: kurzes GO-Overlay nach der Landung (A.4); Tap schliesst es. */
@Composable
internal fun GoOverlay(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier =
            modifier
                .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.94f))
                // A5: der Tap gehoert dem Overlay — ohne clickable liefen
                // Beruehrungen auf die Knoepfe darunter durch (Touch-Leak).
                .clickable(onClick = onDismiss)
                // A5: TalkBack sagt das GO an, statt es nur zu zeigen.
                .semantics { liveRegion = LiveRegionMode.Polite },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = stringResource(R.string.train_rest_dropsync_go),
                style = MaterialTheme.typography.displayMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Text(
                text = stringResource(R.string.train_rest_dropsync_landed),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }
}

/**
 * P1-10: "Plan abbrechen" ist nur bei einer Landung am Pausenende eine
 * eigene Aktion (Pause laeuft weiter); beim manuellen DropRest IST der Plan
 * die Pause — dort bleibt nur "Pause beenden".
 */
internal fun dropSyncCanCancelPlan(state: DropSyncState): Boolean =
    when (state) {
        is DropSyncState.Planned -> state.mode == DropSyncMode.LANDING_AT_REST_END
        is DropSyncState.Armed -> state.plan.mode == DropSyncMode.LANDING_AT_REST_END
        else -> false
    }

/**
 * P1-10: Statuschips der DropSync-Konsole (A.4) — nur Zustaende, nie
 * Technik. `Audio vorbereitet` nur bei echter Armierung (nicht beim
 * Deadline-Fallback), `Timing stabil` nur bei EXACT-Konfidenz.
 */
@Composable
private fun DropSyncStatusChips(dropSyncState: DropSyncState) {
    val audioReady = stringResource(R.string.train_rest_dropsync_audio_ready)
    val timingStable = stringResource(R.string.train_rest_dropsync_timing_stable)
    val bestEffort = stringResource(R.string.train_rest_dropsync_best_effort)
    val chips =
        when (dropSyncState) {
            is DropSyncState.Armed -> {
                buildList {
                    if (dropSyncState.audioPrepared) add(audioReady)
                    if (dropSyncState.plan.confidence == TimingConfidence.EXACT) add(timingStable)
                }
            }

            is DropSyncState.Planned -> {
                buildList {
                    if (dropSyncState.confidence == TimingConfidence.EXACT) add(timingStable)
                }
            }

            is DropSyncState.BestEffort -> {
                listOf(bestEffort)
            }

            else -> {
                emptyList()
            }
        }
    if (chips.isEmpty()) return
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.padding(top = 6.dp),
    ) {
        chips.forEach { label ->
            // Umrandete Pille in der Primaerfarbe (Lime) statt secondaryContainer: das Theme
            // faerbt secondaryContainer lavendel, der einzige Ton ausserhalb der Palette
            // (Design.txt: Lime auf Schwarz).
            Surface(
                shape = MaterialTheme.shapes.small,
                color = Color.Transparent,
                contentColor = MaterialTheme.colorScheme.primary,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary),
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        }
    }
}

/**
 * P1-10: Restzeit bis zur geplanten Landung ("Ziel in mm:ss").
 *
 * Bei einer Landung am Pausenende ist das Ziel das Pausenende — dort tickt
 * die grosse Timer-Restzeit bereits. Beim manuellen DropRest liefert der
 * Monitor die projektierte Zeit bis zum Marker. null, wenn kein Plan aktiv
 * ist.
 */
internal fun dropTargetRemainingMs(
    state: DropSyncState,
    restRemainingMs: Long,
): Long? =
    when (state) {
        is DropSyncState.Planned -> {
            if (state.mode == DropSyncMode.LANDING_AT_REST_END) restRemainingMs else state.remainingMs
        }

        is DropSyncState.Armed -> {
            if (state.plan.mode == DropSyncMode.LANDING_AT_REST_END) restRemainingMs else state.plan.remainingMs
        }

        else -> {
            null
        }
    }

private fun formatRestRemaining(remainingMs: Long): String {
    val totalSeconds = (remainingMs + 999) / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return String.format(Locale.ROOT, "%d:%02d", minutes, seconds)
}

/** P1-10: Sichtdauer des GO-Overlays nach der Landung. */
private const val GO_OVERLAY_MS = 4_000L

/**
 * P1-10: Kopfzeile des aktiven DropSync-Plans — "Track · Marker · Ziel in
 * mm:ss". Nur Zustaende, nie Technik (A.4). C16: Bei einer geplanten
 * Ueberleitungskette steht die Kette ("A -> B -> Drop") statt des einen
 * Zieltitels.
 */
private fun DropSyncState.headline(): String =
    when (this) {
        is DropSyncState.Planned -> {
            if (chain.size > 1) {
                chain.joinToString(CHAIN_ARROW)
            } else if (markerLabel.isBlank()) {
                songTitle
            } else {
                "$songTitle · $markerLabel"
            }
        }

        is DropSyncState.Armed -> {
            if (plan.chain.size > 1) {
                plan.chain.joinToString(CHAIN_ARROW)
            } else if (plan.markerLabel.isBlank()) {
                plan.songTitle
            } else {
                "${plan.songTitle} · ${plan.markerLabel}"
            }
        }

        else -> {
            ""
        }
    }

/** C16: Trenner der Kettenzeile (Konsolen- und Badge-Sprache). */
internal const val CHAIN_ARROW = " → "
