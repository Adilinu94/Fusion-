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

@Composable
private fun WeightInput(
    weightKg: String,
    lastWeightKg: Double?,
    onWeightChange: (String) -> Unit,
    onIncrement: () -> Unit,
    onDecrement: () -> Unit,
) {
    val decrementDescription = stringResource(R.string.a11y_weight_decrement)
    val incrementDescription = stringResource(R.string.a11y_weight_increment)
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = stringResource(R.string.train_weight_label),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = weightKg,
            onValueChange = onWeightChange,
            placeholder = {
                lastWeightKg?.let {
                    Text(stringResource(R.string.train_weight_last, it), maxLines = 1)
                }
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            singleLine = true,
            textStyle = MaterialTheme.typography.displaySmall.copy(textAlign = TextAlign.Center),
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 72.dp),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            BrandButtonGhost(
                text = stringResource(R.string.train_weight_decrement),
                onClick = onDecrement,
                modifier =
                    Modifier
                        .weight(1f)
                        .semantics {
                            contentDescription = decrementDescription
                        },
            )
            BrandButtonGhost(
                text = stringResource(R.string.train_weight_increment),
                onClick = onIncrement,
                modifier =
                    Modifier
                        .weight(1f)
                        .semantics {
                            contentDescription = incrementDescription
                        },
            )
        }
    }
}

/**
 * Zweitmeinungs-Hinweis (Umbauplan 2026-09-04 Phase 7).
 *
 * Formuliert absichtlich als Frage und nicht als Korrektur: die
 * Autokorrelation kann die Zahl nicht exakt bestimmen (Randeffekte,
 * Tempowechsel innerhalb des Satzes), sie erkennt aber gut, ob im Signal
 * ueberhaupt eine passende Periodik steckt. Die Entscheidung bleibt beim
 * Nutzer — auch weil nur eine aktive Korrektur als unabhaengige Wahrheit
 * zaehlt (D3-Regel, ADR-0014).
 *
 * `liveRegion` ist Assertive und nicht Polite: der Hinweis ist nur bis zum
 * Loggen gueltig, eine hoefliche Ansage koennte bis dahin unterdrueckt
 * bleiben.
 */
@Composable
private fun PlausibilityHintRow(hint: PlausibilityHint) {
    val text =
        stringResource(
            R.string.plausibility_hint,
            hint.countedReps,
            hint.estimatedReps,
        )
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.tertiary,
        modifier =
            Modifier.semantics {
                liveRegion = LiveRegionMode.Assertive
                contentDescription = text
            },
    )
}

/**
 * Nachzaehlung am Satzende: konkrete Zahl der Signalanalyse mit Uebernahme per Tipp.
 * `liveRegion` Assertive aus demselben Grund wie bei [PlausibilityHintRow]: der Vorschlag
 * gilt nur bis zum Loggen.
 */
@Composable
private fun RecountSuggestionRow(
    suggestion: RecountSuggestion,
    onAdopt: () -> Unit,
) {
    val text = stringResource(R.string.recount_suggestion, suggestion.liveReps, suggestion.analysisReps)
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.tertiary,
            modifier =
                Modifier.weight(1f).semantics {
                    liveRegion = LiveRegionMode.Assertive
                    contentDescription = text
                },
        )
        TextButton(onClick = onAdopt) {
            Text(text = stringResource(R.string.recount_adopt, suggestion.analysisReps))
        }
    }
}

@Composable
private fun RepInput(
    reps: String,
    onRepsChange: (String) -> Unit,
    lastReps: Int? = null,
) {
    val decrementDescription = stringResource(R.string.a11y_reps_decrement)
    val incrementDescription = stringResource(R.string.a11y_reps_increment)
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = stringResource(R.string.train_reps_label),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            BrandButtonGhost(
                text = "−",
                onClick = {
                    val next = ((reps.toIntOrNull() ?: 0) - 1).coerceAtLeast(0)
                    onRepsChange(next.toString())
                },
                modifier =
                    Modifier
                        .size(64.dp)
                        .semantics { contentDescription = decrementDescription },
            )
            OutlinedTextField(
                value = reps,
                onValueChange = onRepsChange,
                // 2026-09-27 (Befund 12.3, UI-Hebel 1): der Reps-Feld hatte
                // **keinen** Platzhalter, das Gewicht hatte einen. Der Nutzer
                // tippt bei jedem Satz dieselbe Zahl neu — bei fuenf Saetzen
                // mit 90 s Pause sind das fuenf Tastatur-Oeffnungen pro
                // Uebung, obwohl der Wert bekannt ist.
                //
                // Der Platzhalter ist der Weg, den das Gewichtfeld bereits
                // geht (`train_weight_last`); er kostet nichts, verhindert
                // aber den Schreibvorgang, und der Nutzer sieht vor dem
                // Tippen, welchen Wert er bestaetigt.
                placeholder = {
                    lastReps?.let {
                        Text(stringResource(R.string.train_reps_last, it), maxLines = 1)
                    }
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                textStyle = MaterialTheme.typography.displayMedium.copy(textAlign = TextAlign.Center),
                modifier =
                    Modifier
                        .weight(1f)
                        .heightIn(min = 80.dp),
            )
            BrandButtonGhost(
                text = "+",
                onClick = {
                    val next = (reps.toIntOrNull() ?: 0) + 1
                    onRepsChange(next.toString())
                },
                modifier =
                    Modifier
                        .size(64.dp)
                        .semantics { contentDescription = incrementDescription },
            )
        }
        Text(
            text = stringResource(R.string.train_reps_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * RC-4: Hinweis nach 0 erkannten Reps, solange das Rep-Feld leer ist.
 * Ausgelagert, damit [TrainScreen] unter der Detekt-Komplexitaetsgrenze
 * bleibt.
 */
@Composable
private fun CountedZeroHint(
    countedZero: Boolean,
    repsInput: String,
) {
    if (!countedZero || repsInput.isNotEmpty()) return
    Spacer(Modifier.height(8.dp))
    Text(
        text = stringResource(R.string.train_counted_zero_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.tertiary,
    )
}

/**
 * A.4/RC-5: Sensor-Kopfzeile (Chip + Qualitaet + Puls) statt einer Karte, die
 * mit der Satz-Eingabe um Aufmerksamkeit konkurriert. Fehler stehen direkt
 * unter dem Chip (Design 8.1); der Kalibrier-Einstieg liegt an der
 * Uebungszeile (RC-8), nicht mehr als zweiter Knopf hier.
 */
@Composable
internal fun SensorHeaderRow(
    connection: SensorConnectionState,
    sensorError: SensorErrorReason?,
    signalQuality: SignalQuality,
    heartRateAvailability: com.dropsync.domain.health.HeartRateAvailability,
    heartRateBpm: Int?,
    onRequestHeartRatePermission: () -> Unit,
    onHeartRateResume: () -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            when (connection) {
                SensorConnectionState.DISCONNECTED -> {
                    AssistChip(
                        onClick = onConnect,
                        label = { Text(stringResource(R.string.sensor_connect)) },
                        modifier = Modifier.heightIn(min = 48.dp),
                    )
                }

                SensorConnectionState.CONNECTING -> {
                    Text(
                        text = stringResource(R.string.sensor_status_connecting),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                SensorConnectionState.CONNECTED,
                SensorConnectionState.STREAMING,
                -> {
                    Text(
                        text =
                            stringResource(
                                if (connection == SensorConnectionState.STREAMING) {
                                    R.string.sensor_status_streaming
                                } else {
                                    R.string.sensor_status_connected
                                },
                            ),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    TextButton(onClick = onDisconnect, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.sensor_disconnect))
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            // Herzfrequenz-Badge (Herzfrequenz-Plan Phase 2): Health-Connect
            // als Quelle, Permission-Flow und Pulsanzeige.
            HeartRateBadge(
                availability = heartRateAvailability,
                bpm = heartRateBpm,
                onRequestPermission = onRequestHeartRatePermission,
                onResume = onHeartRateResume,
            )
        }
        if (connection == SensorConnectionState.STREAMING) {
            Text(
                text =
                    stringResource(
                        when (signalQuality) {
                            SignalQuality.GOOD -> R.string.sensor_quality_good
                            SignalQuality.DEGRADED -> R.string.sensor_quality_degraded
                            SignalQuality.UNRELIABLE -> R.string.sensor_quality_unreliable
                        },
                    ),
                style = MaterialTheme.typography.labelSmall,
                color =
                    if (signalQuality == SignalQuality.GOOD) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.error
                    },
            )
        }
        sensorError?.let {
            Text(
                text = stringResource(it.messageRes()),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

/**
 * A.4/RC-5: IDLE-Konsole — keine Uebung gewaehlt, also auch keine
 * Primaeraktion. Die Uebungszeile darueber fuehrt zum ersten Satz.
 */
@Composable
internal fun EmptyConsoleHero(modifier: Modifier = Modifier) {
    FlowRepSurface(modifier = modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.train_console_label),
            style = MaterialTheme.typography.labelMedium,
            color = rememberAccentTextColor(),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.train_pick_exercise_placeholder),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = stringResource(R.string.train_console_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

/**
 * RC-5/A.4: Die Satz-Eingabe ist der Hero. Die grosse Rep-Zahl traegt die
 * Quelle (UI-Handbuch 7.4), der Live-Zaehler ist aus der Sensor-Karte in den
 * Hero gezogen, die Waveform steht direkt unter der Zahl, und es gibt genau
 * eine Primaeraktion: "Satz fertig" — waehrend der Zaehlung "Stopp".
 */
@Composable
internal fun SetEntryHero(
    exerciseName: String?,
    weightKg: String,
    lastWeightKg: Double?,
    reps: String,
    lastReps: Int? = null,
    repsSource: RepsSource,
    setPhase: ActiveSetPhase,
    countdownSeconds: Int,
    liveCountedReps: Int,
    streaming: Boolean,
    signalQuality: SignalQuality,
    hasCalibration: Boolean,
    waveform: FloatArray,
    lastPeakMs: Long,
    plausibilityHint: PlausibilityHint?,
    recountSuggestion: RecountSuggestion? = null,
    countedZero: Boolean,
    maxVolumeKg: Double?,
    restSeconds: Int,
    restMode: RestMode,
    canLog: Boolean,
    onWeightChange: (String) -> Unit,
    onIncrement: () -> Unit,
    onDecrement: () -> Unit,
    onRepsChange: (String) -> Unit,
    onAdoptRecount: () -> Unit = {},
    onStartSet: () -> Unit,
    onStopSet: () -> Unit,
    onLogSet: () -> Unit,
    onOpenRestPref: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FlowRepSurface(modifier = modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.train_console_label),
            style = MaterialTheme.typography.labelMedium,
            color = rememberAccentTextColor(),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = exerciseName ?: stringResource(R.string.train_pick_exercise_placeholder),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = stringResource(R.string.train_console_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
        Spacer(Modifier.height(28.dp))
        WeightInput(
            weightKg = weightKg,
            lastWeightKg = lastWeightKg,
            onWeightChange = onWeightChange,
            onIncrement = onIncrement,
            onDecrement = onDecrement,
        )
        Spacer(Modifier.height(28.dp))
        RepHeroSection(
            reps = reps,
            lastReps = lastReps,
            repsSource = repsSource,
            setPhase = setPhase,
            countdownSeconds = countdownSeconds,
            liveCountedReps = liveCountedReps,
            streaming = streaming,
            signalQuality = signalQuality,
            hasCalibration = hasCalibration,
            onRepsChange = onRepsChange,
            onStartSet = onStartSet,
            onStopSet = onStopSet,
        )
        // 2026-09-27 (Befund 12.3, "Sensor-Waveform aus dem Satz-Hero
        // entfernen"): die Waveform stand **immer** unter der Rep-Zahl, auch
        // wenn gar nicht gezaehlt wurde.
        //
        // Warum das schadet: der Nutzer stellt Gewicht und Wiederholungen
        // ein, liest dabei aber eine Linie, die sich langsam bewegt. Das ist
        // eine bewegte Anzeige an der falschen Stelle — sie zieht den Blick
        // auf etwas, das jetzt nichts bedeutet, und weg von der Eingabe.
        //
        // **Waehrend der Zaehlung** ist sie genau richtig: sie zeigt, dass
        // der Sensor arbeitet, wo der letzte Rep lag, und ob das Signal
        // ueberhaupt noch lebt. Das ist der einzige Zeitpunkt, zu dem sie
        // eine Aussage hat.
        if (streaming && waveform.isNotEmpty() && setPhase == ActiveSetPhase.COUNTING) {
            Spacer(Modifier.height(16.dp))
            SensorWaveform(samples = waveform, lastPeakMs = lastPeakMs)
        }
        // Umbauplan 2026-09-04 Phase 7: unabhaengige Zweitmeinung aus der
        // Signalperiodik, direkt unter dem Feld, das sie in Frage stellt.
        // Bewusst KEIN Gegenstueck fuer "Pruefung bestanden": die Pruefung ist
        // bei kurzen oder unregelmaessigen Saetzen stumm, und ein fehlender
        // Hinweis darf nie als Bestaetigung gelesen werden.
        // Die Nachzaehlung hat Vorrang: beide sagen "die Zahl koennte falsch sein", die
        // Nachzaehlung nennt aber eine konkrete Zahl und bietet die Uebernahme an.
        if (recountSuggestion != null) {
            Spacer(Modifier.height(8.dp))
            RecountSuggestionRow(recountSuggestion, onAdopt = onAdoptRecount)
        } else {
            plausibilityHint?.let { hint ->
                Spacer(Modifier.height(8.dp))
                PlausibilityHintRow(hint)
            }
        }
        // RC-4: 0 erkannte Reps sind kein stummes Nichts — der Nutzer bekommt
        // Grund und Handlung (manuell eintragen oder Signal pruefen), solange
        // das Feld leer ist.
        CountedZeroHint(countedZero = countedZero, repsInput = reps)
        Spacer(Modifier.height(28.dp))
        // A.4: genau eine Primaeraktion je Zustand — waehrend der Zaehlung ist
        // "Stopp" die Primaeraktion im Rep-Hero, sonst "Satz fertig".
        if (setPhase != ActiveSetPhase.COUNTING) {
            FlowRepPrimaryButton(
                text = stringResource(R.string.train_set_done),
                onClick = onLogSet,
                enabled = canLog,
            )
        }
        // C3 (5.3): Kompakte Zeile statt Schalter — Dauer und Musikmodus
        // gelten je Uebung und werden im Dialog gesetzt (der globale
        // Drop-Auto-Schalter lebt in den Einstellungen).
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(top = 8.dp),
        ) {
            Text(
                text =
                    stringResource(
                        R.string.train_rest_pref_row,
                        restSeconds,
                        stringResource(
                            if (restMode == RestMode.DROPSYNC) {
                                R.string.train_rest_pref_mode_dropsync
                            } else {
                                R.string.train_rest_pref_mode_normal
                            },
                        ),
                    ),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onOpenRestPref) {
                Text(stringResource(R.string.train_rest_pref_change))
            }
        }
        // PR-Volumen der gewaehlten Uebung
        maxVolumeKg?.let { volume ->
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.train_pr_volume, volume),
                style = MaterialTheme.typography.bodySmall,
                color = rememberAccentTextColor(),
            )
        }
    }
}

/**
 * RC-5: Der Rep-Hero. Die grosse Zahl ist der gemeinsame Wert von
 * Live-Zaehler und Eingabe; darunter steht die Quelle (AUTO / MANUELL
 * KORRIGIERT / MANUELL / SENSOR GETRENNT, UI-Handbuch 7.4). `+/-` bleibt der
 * schnelle Korrekturpfad (7.2), der Live-Zaehler eine sekundaere Aktion.
 */
@Composable
private fun RepHeroSection(
    reps: String,
    lastReps: Int? = null,
    repsSource: RepsSource,
    setPhase: ActiveSetPhase,
    countdownSeconds: Int,
    liveCountedReps: Int,
    streaming: Boolean,
    signalQuality: SignalQuality,
    hasCalibration: Boolean,
    onRepsChange: (String) -> Unit,
    onStartSet: () -> Unit,
    onStopSet: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = stringResource(R.string.train_reps_label),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        when (setPhase) {
            ActiveSetPhase.COUNTING -> {
                LiveCountHero(liveCountedReps = liveCountedReps, onStopSet = onStopSet)
                // Waehrend der Zaehlung ist die grosse Zahl live — die Quelle
                // ist dann immer der Sensor.
                RepSourceLine(source = RepsSource.Sensor, streaming = streaming)
            }

            ActiveSetPhase.COUNTDOWN -> {
                Text(
                    text = stringResource(R.string.live_count_countdown, countdownSeconds),
                    style = MaterialTheme.typography.headlineMedium,
                    color = rememberAccentTextColor(),
                )
            }

            ActiveSetPhase.IDLE -> {
                RepInput(
                    reps = reps,
                    onRepsChange = onRepsChange,
                    lastReps = lastReps,
                )
                // 2026-09-27 (Befund 12.4, "Sensorabruss aktiv
                // kommunizieren"): die Quelle erklaert die *aktuelle*
                // Zahl. **Und sie erklaert den Abriss auch dann, wenn
                // keine Zahl dasteht.**
                //
                // Vorher stand hier `reps.isNotEmpty() || Abriss` — was
                // korrekt aussieht, aber am Anfang des Satzes greift die
                // zweite Bedingung nie, weil `reps` dann leer ist. Der
                // Nutzer verbindet den Chip, startet den Satz, der
                // Chip reißt nach 40 Sekunden ab — und **90 Sekunden
                // lang passiert nichts**. Die App zählt nicht, und sagt
                // es nicht.
                //
                // Das ist der teuerste Fehlermodus der ganzen App: Der
                // Nutzer hält 90 Sekunden lang Gewicht und Wiederholungen
                // korrekt fest, und am Ende steht dort eine Zahl, die er
                // nie kontrollieren konnte.
                RepSourceLine(source = repsSource, streaming = streaming)
                LiveCountStartRow(
                    streaming = streaming,
                    hasCalibration = hasCalibration,
                    signalQuality = signalQuality,
                    onStartSet = onStartSet,
                )
            }
        }
    }
}

/**
 * RC-5: Der Live-Zaehler als grosse Zahl im Hero plus "Stopp" als
 * Primaeraktion dieses Zustands. TalkBack (Plan 6.2): Zaehlerstand als
 * polite liveRegion, jede neue Rep wird angesagt ohne den Fokus zu klauen.
 */
@Composable
private fun LiveCountHero(
    liveCountedReps: Int,
    onStopSet: () -> Unit,
) {
    val repCountDescription = stringResource(R.string.a11y_reps_counted, liveCountedReps)
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "$liveCountedReps",
            style = MaterialTheme.typography.displayMedium,
            color = rememberAccentTextColor(),
            modifier =
                Modifier
                    .weight(1f)
                    .semantics {
                        liveRegion = LiveRegionMode.Polite
                        contentDescription = repCountDescription
                    },
        )
        BrandButtonPrimary(text = stringResource(R.string.live_count_stop), onClick = onStopSet)
    }
}

/**
 * RC-5: Der Live-Zaehler ist eine sekundaere Aktion im Hero — die
 * Primaeraktion bleibt "Satz fertig". Fehlt die Kalibrierung, steht hier nur
 * der Grund; der Einstieg in den Wizard liegt direkt darueber an der
 * Uebungszeile (RC-8).
 *
 * 2026-09-27 (Produktentscheidung "Sensor optional"): `if (!streaming)
 * return` war eine **stille** Rueckkehr — ohne verbundenen Chip fehlte
 * die ganze Zeile, und der Nutzer konnte nicht unterscheiden zwischen
 * "es gibt kein Auto-Zaehlen" und "das ist gerade nicht verfuegbar".
 *
 * Jetzt bleibt der Knopf sichtbar und **ausgegraut**, mit dem Grund. Das
 * ist die ehrliche Form: die Funktion existiert, sie braucht nur den Chip.
 * Wer keinen Chip hat, arbeitet mit den +/−-Knopfen weiter — das steht
 * als eigener Hinweis darunter, statt versteckt zu werden.
 */
@Composable
private fun LiveCountStartRow(
    streaming: Boolean,
    hasCalibration: Boolean,
    signalQuality: SignalQuality,
    onStartSet: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // Kein Chip: kein Auto-Zaehlen. Der Grund steht da, damit die
        // Funktion nicht einfach "verschwindet" — und der Hinweis auf die
        // manuelle Eingabe, damit klar ist, dass es weitergeht.
        if (!streaming) {
            Text(
                text = stringResource(R.string.live_count_reason_no_sensor),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(R.string.live_count_manual_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Column
        }
        if (hasCalibration) {
            BrandButtonGhost(
                text = stringResource(R.string.live_count_start),
                onClick = onStartSet,
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            Text(
                text = stringResource(R.string.live_count_reason_no_profile),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (signalQuality == SignalQuality.DEGRADED) {
            Text(
                text = stringResource(R.string.live_count_weak_signal),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

/**
 * RC-5 / UI-Handbuch 7.4: Quelle der Rep-Zahl im Hero. Genau eine Zahl, genau
 * eine Quelle — die Zeile nennt zusaetzlich die Handlung, wenn der Chip fehlt
 * ("SENSOR GETRENNT — Reps per +/- weiter", Design 8.1).
 */
@Composable
private fun RepSourceLine(
    source: RepsSource,
    streaming: Boolean,
) {
    val labelRes =
        when (source) {
            RepsSource.Sensor -> R.string.reps_source_auto
            is RepsSource.SensorWithManualCorrection -> R.string.reps_source_corrected
            RepsSource.Manual -> R.string.reps_source_manual
            RepsSource.SensorDisconnected -> R.string.reps_source_disconnected
        }
    val hint =
        when (source) {
            RepsSource.Sensor -> {
                stringResource(R.string.reps_source_auto_hint)
            }

            is RepsSource.SensorWithManualCorrection -> {
                stringResource(R.string.reps_source_corrected_hint, source.counted)
            }

            RepsSource.Manual -> {
                stringResource(
                    if (streaming) {
                        R.string.reps_source_manual_hint
                    } else {
                        R.string.reps_source_manual_disconnected_hint
                    },
                )
            }

            RepsSource.SensorDisconnected -> {
                stringResource(R.string.reps_source_disconnected_hint)
            }
        }
    val warn = source == RepsSource.SensorDisconnected
    val color =
        if (warn) {
            MaterialTheme.colorScheme.tertiary
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(text = stringResource(labelRes), style = MaterialTheme.typography.labelSmall, color = color)
        Text(text = hint, style = MaterialTheme.typography.bodySmall, color = color)
    }
}

/**
 * Herzfrequenz-Badge (Herzfrequenz-Plan Phase 2): zeigt den letzten Puls
 * aus Health Connect; ohne Berechtigung ein Chip zum Nachfragen, ohne
 * Provider ein stiller Hinweis. Daten kommen nur im Foreground (Plan 3.4).
 */
@Composable
private fun HeartRateBadge(
    availability: com.dropsync.domain.health.HeartRateAvailability,
    bpm: Int?,
    onRequestPermission: () -> Unit,
    onResume: () -> Unit,
) {
    LaunchedEffect(availability) {
        if (availability == com.dropsync.domain.health.HeartRateAvailability.PERMISSION_REQUIRED ||
            availability == com.dropsync.domain.health.HeartRateAvailability.NO_RECENT_DATA ||
            availability == com.dropsync.domain.health.HeartRateAvailability.READY
        ) {
            onResume()
        }
    }
    when (availability) {
        com.dropsync.domain.health.HeartRateAvailability.HEALTH_CONNECT_NOT_AVAILABLE,
        com.dropsync.domain.health.HeartRateAvailability.UPDATE_REQUIRED,
        -> {
            // Kein Provider oder Update noetig: bewusst kein UI-Element,
            // der Nutzer hat die Funktion nicht aktiviert.
        }

        com.dropsync.domain.health.HeartRateAvailability.PERMISSION_REQUIRED -> {
            AssistChip(
                onClick = onRequestPermission,
                label = { Text(stringResource(R.string.heart_rate_allow)) },
                modifier = Modifier.heightIn(min = 48.dp),
            )
        }

        com.dropsync.domain.health.HeartRateAvailability.NO_RECENT_DATA -> {
            Text(
                text = stringResource(R.string.heart_rate_none),
                style = MaterialTheme.typography.bodySmall,
            )
        }

        com.dropsync.domain.health.HeartRateAvailability.READY -> {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text =
                        stringResource(
                            R.string.heart_rate_value,
                            bpm?.toString() ?: stringResource(R.string.heart_rate_unknown),
                        ),
                    style = MaterialTheme.typography.titleMedium,
                    color = rememberAccentTextColor(),
                )
            }
        }
    }
}
