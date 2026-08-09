package com.dropsync.feature.audio

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.dropsync.core.designsystem.icon.BrandIcons
import com.dropsync.domain.audio.BitPerfectSupport
import com.dropsync.domain.audio.CrossfadeCurves
import com.dropsync.domain.audio.DitherMode
import com.dropsync.domain.audio.DspConfig
import com.dropsync.domain.audio.ResamplerQuality
import com.dropsync.domain.audio.ResamplerSettings

@Composable
internal fun ReverbSection(
    config: DspConfig,
    viewModel: AudioSettingsViewModel,
) {
    SectionCard(title = stringResource(R.string.audio_reverb_title), iconRes = BrandIcons.Reverb) {
        SwitchRow(
            label = stringResource(R.string.audio_reverb_enable),
            checked = config.reverb.enabled,
            onCheckedChange = { on -> viewModel.update { it.copy(reverb = it.reverb.copy(enabled = on)) } },
            enabled = config.enabled,
        )
        LabeledSlider(
            label = stringResource(R.string.audio_reverb_room),
            value = config.reverb.roomSize.toFloat(),
            valueRange = 0f..1f,
            onValueChangeFinished = { v ->
                viewModel.update { it.copy(reverb = it.reverb.copy(roomSize = v.toDouble())) }
            },
            valueText = { formatPercent(it) },
            enabled = config.enabled && config.reverb.enabled,
        )
        LabeledSlider(
            label = stringResource(R.string.audio_reverb_damping),
            value = config.reverb.damping.toFloat(),
            valueRange = 0f..1f,
            onValueChangeFinished = { v ->
                viewModel.update { it.copy(reverb = it.reverb.copy(damping = v.toDouble())) }
            },
            valueText = { formatPercent(it) },
            enabled = config.enabled && config.reverb.enabled,
        )
        LabeledSlider(
            label = stringResource(R.string.audio_reverb_wet),
            value = config.reverb.wet.toFloat(),
            valueRange = 0f..1f,
            onValueChangeFinished = { v -> viewModel.update { it.copy(reverb = it.reverb.copy(wet = v.toDouble())) } },
            valueText = { formatPercent(it) },
            enabled = config.enabled && config.reverb.enabled,
        )
    }
}

@Composable
internal fun ResamplerSection(
    config: DspConfig,
    viewModel: AudioSettingsViewModel,
) {
    SectionCard(title = stringResource(R.string.audio_resampler_title), iconRes = BrandIcons.Resampler) {
        Text(stringResource(R.string.audio_resampler_quality))
        ChoiceChips(
            options = ResamplerQuality.entries,
            selected = config.resampler.quality,
            optionLabel = { stringResource(it.labelRes()) },
            onSelect = { q -> viewModel.update { it.copy(resampler = it.resampler.copy(quality = q)) } },
        )
        Text(stringResource(R.string.audio_resampler_rate))
        ChoiceChips(
            options = listOf<Int?>(null) + ResamplerSettings.SUPPORTED_RATES,
            selected = config.resampler.targetRateHz,
            optionLabel = { rate -> rate?.let { formatHz(it) } ?: stringResource(R.string.audio_auto) },
            onSelect = { rate -> viewModel.update { it.copy(resampler = it.resampler.copy(targetRateHz = rate)) } },
        )
        // Akku-Hinweis (Plan Phase 7): Hi-Res-Resampling mit Sinc kostet CPU.
        val targetRate = config.resampler.targetRateHz
        if (config.resampler.quality == ResamplerQuality.SINC && targetRate != null && targetRate > 48_000) {
            Text(
                text = stringResource(R.string.audio_resampler_battery_hint),
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
internal fun DitherSection(
    config: DspConfig,
    viewModel: AudioSettingsViewModel,
) {
    SectionCard(title = stringResource(R.string.audio_dither_title)) {
        Text(
            text = stringResource(R.string.audio_dither_desc),
            modifier = Modifier.padding(bottom = 4.dp),
        )
        ChoiceChips(
            options = DitherMode.entries,
            selected = config.ditherMode,
            optionLabel = { stringResource(it.labelRes()) },
            onSelect = { mode -> viewModel.update { it.copy(ditherMode = mode) } },
        )
    }
}

@Composable
internal fun DvcSection(
    config: DspConfig,
    viewModel: AudioSettingsViewModel,
) {
    SectionCard(title = stringResource(R.string.audio_dvc_title), iconRes = BrandIcons.Dvc) {
        SwitchRow(
            label = stringResource(R.string.audio_dvc_enable),
            checked = config.dvcEnabled,
            onCheckedChange = { on -> viewModel.update { it.copy(dvcEnabled = on) } },
            description = stringResource(R.string.audio_dvc_desc),
            enabled = config.enabled,
        )
        LabeledSlider(
            label = stringResource(R.string.audio_dvc_volume),
            value = config.dvcVolume.toFloat(),
            valueRange = 0f..1f,
            onValueChangeFinished = { v -> viewModel.update { it.copy(dvcVolume = v.toDouble()) } },
            valueText = { formatPercent(it) },
            enabled = config.enabled && config.dvcEnabled,
        )
    }
}

@Composable
internal fun CrossfadeSection(
    config: DspConfig,
    viewModel: AudioSettingsViewModel,
) {
    SectionCard(title = stringResource(R.string.audio_crossfade_title), iconRes = BrandIcons.Swap) {
        LabeledSlider(
            label = stringResource(R.string.audio_crossfade_seconds),
            value = config.crossfadeSeconds.toFloat(),
            valueRange = 0f..CrossfadeCurves.MAX_SECONDS.toFloat(),
            steps = CrossfadeCurves.MAX_SECONDS - 1,
            onValueChangeFinished = { v -> viewModel.update { it.copy(crossfadeSeconds = v.toInt()) } },
            valueText = { "${it.toInt()} s" },
        )
        Text(
            text = stringResource(R.string.audio_crossfade_desc),
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
internal fun SystemEffectsSection(
    config: DspConfig,
    viewModel: AudioSettingsViewModel,
) {
    SectionCard(title = stringResource(R.string.audio_musicfx_title)) {
        SwitchRow(
            label = stringResource(R.string.audio_musicfx_enable),
            checked = config.useSystemEffects,
            onCheckedChange = { on -> viewModel.update { it.copy(useSystemEffects = on) } },
            description = stringResource(R.string.audio_musicfx_desc),
        )
    }
}

@Composable
internal fun BitPerfectSection(
    config: DspConfig,
    support: BitPerfectSupport,
    viewModel: AudioSettingsViewModel,
) {
    SectionCard(title = stringResource(R.string.audio_bitperfect_title), iconRes = BrandIcons.BitPerfect) {
        SwitchRow(
            label = stringResource(R.string.audio_bitperfect_enable),
            checked = config.bitPerfectEnabled && support.available,
            onCheckedChange = { on -> viewModel.update { it.copy(bitPerfectEnabled = on) } },
            description = stringResource(R.string.audio_bitperfect_desc),
            enabled = support.available,
        )
        if (support.available) {
            support.deviceName?.let { Text(stringResource(R.string.audio_bitperfect_device, it)) }
            if (support.sampleRatesHz.isNotEmpty()) {
                Text(
                    stringResource(
                        R.string.audio_bitperfect_rates,
                        support.sampleRatesHz.joinToString(", ") { formatHz(it) },
                    ),
                )
            }
            if (support.encodings.isNotEmpty()) {
                Text(stringResource(R.string.audio_bitperfect_encodings, support.encodings.joinToString(", ")))
            }
        } else {
            Text(
                text = stringResource(R.string.audio_bitperfect_unavailable),
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
internal fun OutputProfileSection(activeProfileKey: String?) {
    val context = LocalContext.current
    SectionCard(title = stringResource(R.string.audio_profile_title), iconRes = BrandIcons.OutputDevice) {
        Text(
            text =
                activeProfileKey
                    ?.let { stringResource(R.string.audio_profile_active, it) }
                    ?: stringResource(R.string.audio_profile_none),
        )
        Text(
            text = stringResource(R.string.audio_profile_bt_hint),
            modifier = Modifier.padding(top = 4.dp),
        )
        OutlinedButton(
            onClick = {
                runCatching {
                    context.startActivity(
                        Intent(Settings.ACTION_SOUND_SETTINGS)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
            },
            modifier = Modifier.padding(top = 8.dp),
        ) {
            Text(stringResource(R.string.audio_profile_open_settings))
        }
    }
}

// --- Label-Zuordnung der Enums ---

private fun ResamplerQuality.labelRes(): Int =
    when (this) {
        ResamplerQuality.LINEAR -> R.string.audio_resampler_linear
        ResamplerQuality.SINC -> R.string.audio_resampler_sinc
    }

private fun DitherMode.labelRes(): Int =
    when (this) {
        DitherMode.OFF -> R.string.audio_dither_off
        DitherMode.TPDF -> R.string.audio_dither_tpdf
        DitherMode.SHAPED -> R.string.audio_dither_shaped
    }

private fun formatPercent(fraction: Float): String = "${(fraction * 100).toInt()} %"
