package com.dropsync.feature.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.dropsync.core.designsystem.theme.rememberAccentTextColor
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Tempo-Steuerung aus dem Player (BPM-Lock): Regler 0,5..2,0x in
 * 0,05-Schritten, Preset-Chips und Ziel-Kadenz-Schalter. Der Lock setzt
 * den Faktor automatisch aus Ziel-BPM / Track-BPM (Oktav-gefalftet) und
 * zieht bei Titelwechseln nach. Sicherer Bereich 0,5..2,0x wie in der
 * Recherche empfohlen (media3-Issue #1101).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TempoSheet(
    speed: Float,
    trackBpm: Float?,
    bpmLockEnabled: Boolean,
    lockTargetBpm: Int,
    onDismiss: () -> Unit,
    onSpeedSelected: (Float) -> Unit,
    onBpmLockChanged: (Boolean) -> Unit,
    onTargetBpmChanged: (Int) -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.padding(horizontal = 24.dp)) {
            Text(
                text = stringResource(R.string.now_playing_tempo_title),
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                text = String.format(Locale.ROOT, "%.2fx", speed),
                style = MaterialTheme.typography.headlineMedium,
                color = rememberAccentTextColor(),
            )
            // 7.2/5: lokalisierte Beschreibung statt hartcodiertem deutschem
            // Literal; der String-Read liegt in der Composition.
            val tempoDescription = stringResource(R.string.now_playing_tempo_a11y_value, speed)
            Slider(
                value = speed,
                onValueChange = { raw ->
                    // 0,05er-Rasterung; beiaktivem Lock ueberschreibt die
                    // manuelle Wahl den Lock (Nutzer hat Vorrang).
                    onSpeedSelected((raw * SPEED_STEPS).roundToInt() / SPEED_STEPS)
                },
                valueRange = PlayerViewModel.MIN_PLAYBACK_SPEED..PlayerViewModel.MAX_PLAYBACK_SPEED,
                modifier =
                    Modifier.semantics {
                        contentDescription = tempoDescription
                    },
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SPEED_PRESETS.forEach { preset ->
                    FilterChip(
                        selected = abs(speed - preset) < 0.001f,
                        onClick = { onSpeedSelected(preset) },
                        label = { Text(String.format(Locale.ROOT, "%.2fx", preset)) },
                    )
                }
                FilterChip(
                    selected = abs(speed - 1f) < 0.001f,
                    onClick = { onSpeedSelected(1f) },
                    label = { Text(stringResource(R.string.now_playing_tempo_original)) },
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // BPM-Lock: Ziel-Kadenz haelt das Wiedergabetempo nach.
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.now_playing_tempo_lock_title),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text =
                            if (trackBpm != null) {
                                stringResource(
                                    R.string.now_playing_tempo_lock_track_bpm,
                                    trackBpm.roundToInt(),
                                )
                            } else {
                                stringResource(R.string.now_playing_tempo_lock_no_bpm)
                            },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = bpmLockEnabled,
                    onCheckedChange = onBpmLockChanged,
                    enabled = trackBpm != null,
                )
            }
            if (bpmLockEnabled && trackBpm != null) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    IconButton(onClick = { onTargetBpmChanged(lockTargetBpm - 5) }) {
                        Text(text = "−", style = MaterialTheme.typography.titleLarge)
                    }
                    Text(
                        text = stringResource(R.string.now_playing_tempo_lock_target, lockTargetBpm),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                    IconButton(onClick = { onTargetBpmChanged(lockTargetBpm + 5) }) {
                        Text(text = "+", style = MaterialTheme.typography.titleLarge)
                    }
                }
                Text(
                    text =
                        stringResource(
                            R.string.now_playing_tempo_lock_effective,
                            PlayerViewModel.speedForBpmLock(lockTargetBpm, trackBpm),
                        ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

private const val SPEED_STEPS = 20f // 0,05er-Rasterung
private val SPEED_PRESETS = listOf(0.75f, 1.25f, 1.5f)
