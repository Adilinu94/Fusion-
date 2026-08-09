package com.dropsync.feature.audio

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.dropsync.core.designsystem.icon.BrandIcons
import com.dropsync.domain.audio.DspConfig
import com.dropsync.domain.audio.EqBand
import com.dropsync.domain.audio.EqMode
import com.dropsync.domain.audio.EqPreset

/**
 * Equalizer-Abschnitt (Plan Phase 2): grafisch (feste ISO-Frequenzen,
 * 10/15/31 Baender, nur Gain) oder parametrisch (Frequenz/Q/Typ je Band
 * fest, Gain regelbar). Presets lassen sich anwenden, speichern und
 * (Nutzerpresets) loeschen.
 */
@Composable
internal fun EqSection(
    config: DspConfig,
    presets: List<EqPreset>,
    viewModel: AudioSettingsViewModel,
) {
    SectionCard(title = stringResource(R.string.audio_eq_title), iconRes = BrandIcons.Equalizer) {
        SwitchRow(
            label = stringResource(R.string.audio_eq_enable),
            checked = config.eq.enabled,
            onCheckedChange = { on -> viewModel.update { it.copy(eq = it.eq.copy(enabled = on)) } },
        )
        ChoiceChips(
            options = EqMode.entries,
            selected = config.eq.mode,
            optionLabel = { stringResource(it.labelRes()) },
            onSelect = { mode -> viewModel.update { it.copy(eq = it.eq.copy(mode = mode)) } },
        )
        if (config.eq.mode == EqMode.GRAPHIC) {
            ChoiceChips(
                options = GRAPHIC_BAND_COUNTS,
                selected = graphicCountOf(config.eq.bands.size),
                optionLabel = { stringResource(R.string.audio_eq_bands_count, it) },
                onSelect = { viewModel.setGraphicBandCount(it) },
            )
        }
        config.eq.bands.forEachIndexed { index, band ->
            LabeledSlider(
                label = formatFrequency(band.frequencyHz),
                value = band.gainDb.toFloat(),
                valueRange = EqBand.MIN_GAIN_DB.toFloat()..EqBand.MAX_GAIN_DB.toFloat(),
                onValueChangeFinished = { viewModel.setBandGain(index, it.toDouble()) },
                valueText = { formatGainDb(it) },
                enabled = config.eq.enabled,
            )
        }
        PresetRow(presets = presets, config = config, viewModel = viewModel)
    }
}

@Composable
private fun PresetRow(
    presets: List<EqPreset>,
    config: DspConfig,
    viewModel: AudioSettingsViewModel,
) {
    var menuOpen by remember { mutableStateOf(false) }
    var presetName by remember { mutableStateOf("") }
    Box(modifier = Modifier.padding(top = 8.dp)) {
        OutlinedButton(onClick = { menuOpen = true }) {
            Text(stringResource(R.string.audio_eq_presets))
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            presets.forEach { preset ->
                DropdownMenuItem(
                    text = { Text(preset.name) },
                    trailingIcon = {
                        if (!preset.isBuiltIn) {
                            IconButton(onClick = { viewModel.deletePreset(preset.id) }) {
                                Icon(
                                    painterResource(BrandIcons.Delete),
                                    contentDescription =
                                        stringResource(R.string.audio_eq_preset_delete),
                                )
                            }
                        }
                    },
                    onClick = {
                        viewModel.applyPreset(preset.id)
                        menuOpen = false
                    },
                )
            }
        }
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            value = presetName,
            onValueChange = { presetName = it },
            singleLine = true,
            label = { Text(stringResource(R.string.audio_eq_preset_name)) },
            modifier = Modifier.weight(1f),
        )
        Button(
            onClick = {
                viewModel.savePreset(presetName.trim(), config.eq.bands)
                presetName = ""
            },
            enabled = presetName.isNotBlank(),
        ) {
            Text(stringResource(R.string.audio_eq_preset_save))
        }
    }
}

private val GRAPHIC_BAND_COUNTS = listOf(10, 15, 31)

private fun graphicCountOf(size: Int): Int =
    when (size) {
        15 -> 15
        31 -> 31
        else -> 10
    }

private fun EqMode.labelRes(): Int =
    when (this) {
        EqMode.GRAPHIC -> R.string.audio_eq_mode_graphic
        EqMode.PARAMETRIC -> R.string.audio_eq_mode_parametric
    }
