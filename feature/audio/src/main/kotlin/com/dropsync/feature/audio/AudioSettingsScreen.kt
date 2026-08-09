package com.dropsync.feature.audio

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dropsync.core.designsystem.icon.BrandIcons
import com.dropsync.domain.audio.AudioInfo
import com.dropsync.domain.audio.DspConfig
import com.dropsync.domain.audio.StereoMatrix

/**
 * Audio-/DSP-Einstellungen (Plan Phase 1/2/5): Audioinformationen,
 * Vorverstaerker/Limiter, EQ (grafisch/parametrisch) mit Presets,
 * Klangregler, Stereobreite, Reverb, Resampler, Dither, DVC, Crossfade,
 * Systemeffekte (MusicFX), Bit-Perfect und aktives Ausgabeprofil.
 * Erreichbar als Unterseite der Einstellungen (kein viertes Hauptziel).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AudioSettingsScreen(
    contentPadding: PaddingValues,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: AudioSettingsViewModel = hiltViewModel(),
) {
    val config by viewModel.dspConfig.collectAsStateWithLifecycle()
    val info by viewModel.audioInfo.collectAsStateWithLifecycle()
    val presets by viewModel.eqPresets.collectAsStateWithLifecycle()
    val bitPerfect by viewModel.bitPerfectSupport.collectAsStateWithLifecycle()
    val profileKey by viewModel.activeOutputProfileKey.collectAsStateWithLifecycle()

    Column(
        modifier =
            modifier
                .fillMaxSize()
                .padding(contentPadding),
    ) {
        TopAppBar(
            title = { Text(stringResource(R.string.audio_title)) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(
                        painterResource(BrandIcons.Back),
                        contentDescription = stringResource(R.string.audio_back),
                    )
                }
            },
        )
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            item { AudioInfoPanel(info) }
            item { MasterSection(config, viewModel) }
            item { ToneSection(config, viewModel) }
            item { EqSection(config, presets, viewModel) }
            item { StereoSection(config, viewModel) }
            item { ReverbSection(config, viewModel) }
            item { ResamplerSection(config, viewModel) }
            item { DitherSection(config, viewModel) }
            item { DvcSection(config, viewModel) }
            item { CrossfadeSection(config, viewModel) }
            item { SystemEffectsSection(config, viewModel) }
            item { BitPerfectSection(config, bitPerfect, viewModel) }
            item { OutputProfileSection(profileKey) }
        }
    }
}

@Composable
private fun AudioInfoPanel(info: AudioInfo?) {
    SectionCard(title = stringResource(R.string.audio_info_title), iconRes = BrandIcons.Info) {
        if (info == null) {
            Text(
                text = stringResource(R.string.audio_info_idle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@SectionCard
        }
        InfoRow(stringResource(R.string.audio_info_codec), info.codecMimeType ?: "—")
        InfoRow(
            stringResource(R.string.audio_info_source),
            formatRateBitsChannels(info.sourceSampleRateHz, info.sourceBitDepth, info.sourceChannelCount),
        )
        info.bitrateBps?.let { InfoRow(stringResource(R.string.audio_info_bitrate), "${it / 1000} kbps") }
        InfoRow(
            stringResource(R.string.audio_info_output),
            listOfNotNull(
                info.outputSampleRateHz?.let { formatHz(it) },
                info.outputEncoding,
            ).joinToString(" · ").ifEmpty { "—" },
        )
        InfoRow(
            stringResource(R.string.audio_info_float),
            stringResource(if (info.floatOutput) R.string.audio_yes else R.string.audio_no),
        )
        InfoRow(
            stringResource(R.string.audio_info_dsp),
            stringResource(if (info.dspActive) R.string.audio_info_dsp_active else R.string.audio_info_dsp_bypass),
        )
        InfoRow(
            stringResource(R.string.audio_info_device),
            info.outputDeviceName ?: info.outputDevice?.name ?: "—",
        )
    }
}

@Composable
private fun InfoRow(
    label: String,
    value: String,
) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(text = label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(text = value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun MasterSection(
    config: DspConfig,
    viewModel: AudioSettingsViewModel,
) {
    SectionCard(title = stringResource(R.string.audio_master_title), iconRes = BrandIcons.AudioDsp) {
        SwitchRow(
            label = stringResource(R.string.audio_master_enable),
            checked = config.enabled,
            onCheckedChange = { on -> viewModel.update { it.copy(enabled = on) } },
            description = stringResource(R.string.audio_master_enable_desc),
        )
        LabeledSlider(
            label = stringResource(R.string.audio_preamp),
            value = config.preampDb.toFloat(),
            valueRange = DspConfig.PREAMP_MIN_DB.toFloat()..DspConfig.PREAMP_MAX_DB.toFloat(),
            onValueChangeFinished = { v -> viewModel.update { it.copy(preampDb = v.toDouble()) } },
            valueText = { formatGainDb(it) },
            enabled = config.enabled,
        )
        SwitchRow(
            label = stringResource(R.string.audio_limiter),
            checked = config.limiterEnabled,
            onCheckedChange = { on -> viewModel.update { it.copy(limiterEnabled = on) } },
            enabled = config.enabled,
        )
    }
}

@Composable
private fun ToneSection(
    config: DspConfig,
    viewModel: AudioSettingsViewModel,
) {
    SectionCard(title = stringResource(R.string.audio_tone_title), iconRes = BrandIcons.BassTreble) {
        LabeledSlider(
            label = stringResource(R.string.audio_bass),
            value = config.bassGainDb.toFloat(),
            valueRange = DspConfig.TONE_MIN_DB.toFloat()..DspConfig.TONE_MAX_DB.toFloat(),
            onValueChangeFinished = { v -> viewModel.update { it.copy(bassGainDb = v.toDouble()) } },
            valueText = { formatGainDb(it) },
            enabled = config.enabled,
        )
        LabeledSlider(
            label = stringResource(R.string.audio_treble),
            value = config.trebleGainDb.toFloat(),
            valueRange = DspConfig.TONE_MIN_DB.toFloat()..DspConfig.TONE_MAX_DB.toFloat(),
            onValueChangeFinished = { v -> viewModel.update { it.copy(trebleGainDb = v.toDouble()) } },
            valueText = { formatGainDb(it) },
            enabled = config.enabled,
        )
    }
}

@Composable
private fun StereoSection(
    config: DspConfig,
    viewModel: AudioSettingsViewModel,
) {
    SectionCard(title = stringResource(R.string.audio_stereo_title), iconRes = BrandIcons.StereoWidth) {
        LabeledSlider(
            label = stringResource(R.string.audio_stereo_width),
            value = config.stereoWidthPercent.toFloat(),
            valueRange = StereoMatrix.MIN_WIDTH_PERCENT.toFloat()..StereoMatrix.MAX_WIDTH_PERCENT.toFloat(),
            onValueChangeFinished = { v -> viewModel.update { it.copy(stereoWidthPercent = v.toInt()) } },
            valueText = { "${it.toInt()} %" },
            enabled = config.enabled,
        )
    }
}

// --- Gemeinsame Formatierung (auch von AudioEqSection/AudioEffectSections genutzt) ---

internal fun formatFrequency(hz: Double): String =
    if (hz >= 1_000.0) {
        "%.1f kHz".format(hz / 1_000.0)
    } else {
        "%.0f Hz".format(hz)
    }

internal fun formatHz(hz: Int): String = formatFrequency(hz.toDouble())

internal fun formatGainDb(db: Float): String = "%+.1f dB".format(db)

private fun formatRateBitsChannels(
    rateHz: Int?,
    bitDepth: Int?,
    channels: Int?,
): String {
    val parts =
        listOfNotNull(
            rateHz?.let { formatHz(it) },
            bitDepth?.let { "$it bit" },
            channels?.let { "$it ch" },
        )
    return if (parts.isEmpty()) "—" else parts.joinToString(" · ")
}
