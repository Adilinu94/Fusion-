package com.dropsync.feature.audio

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp

/** Abschnittskarte mit Titel; buendelt zusammengehoerige Regler. */
@Composable
internal fun SectionCard(
    title: String,
    modifier: Modifier = Modifier,
    iconRes: Int? = null,
    content: @Composable () -> Unit,
) {
    ElevatedCard(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                iconRes?.let {
                    Icon(
                        painterResource(it),
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                    )
                }
                Text(text = title, style = MaterialTheme.typography.titleMedium)
            }
            content()
        }
    }
}

/**
 * Ein-/Ausschalter mit Beschriftung; erfuellt 48-dp-Ziel und traegt eine
 * optionale Erklaerung fuer weniger offensichtliche Schalter (12.4/12.5).
 */
@Composable
internal fun SwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    description: String? = null,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = label, style = MaterialTheme.typography.bodyLarge)
            description?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}

/**
 * Schieberegler mit Live-Wertanzeige. Der Wert wird erst beim Loslassen
 * uebernommen (onValueChangeFinished), damit die Persistenz nicht bei
 * jedem Pixel schreibt. [stateDescription] macht den Wert fuer
 * Screenreader lesbar (Barrierefreiheit, Plan Phase 7).
 */
@Composable
internal fun LabeledSlider(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChangeFinished: (Float) -> Unit,
    valueText: (Float) -> String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    steps: Int = 0,
) {
    var local by remember(value) { mutableFloatStateOf(value) }
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(text = label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(text = valueText(local), style = MaterialTheme.typography.bodyMedium)
        }
        Slider(
            value = local,
            onValueChange = { local = it },
            onValueChangeFinished = { onValueChangeFinished(local) },
            valueRange = valueRange,
            steps = steps,
            enabled = enabled,
            modifier = Modifier.semantics { stateDescription = valueText(local) },
        )
    }
}

/**
 * Waagerechte Auswahl aus [options] (Enum o. Aehnliches) als FilterChips;
 * genau eine Option ist aktiv. Ersetzt einen Radio-Block platzsparend.
 */
@Composable
internal fun <T> ChoiceChips(
    options: List<T>,
    selected: T,
    optionLabel: @Composable (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEach { option ->
            FilterChip(
                selected = option == selected,
                onClick = { onSelect(option) },
                label = { Text(optionLabel(option)) },
            )
        }
    }
}
