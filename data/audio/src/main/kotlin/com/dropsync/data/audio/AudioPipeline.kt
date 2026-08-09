package com.dropsync.data.audio

import androidx.media3.common.audio.AudioProcessor
import com.dropsync.domain.audio.AudioInfo
import com.dropsync.domain.audio.DitherMode
import com.dropsync.domain.audio.DspConfig
import com.dropsync.domain.audio.StereoMatrix
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/** Eingangsformat der laufenden Wiedergabe (vom Decoder gemeldet). */
data class SourceFormatInfo(
    val codecMimeType: String?,
    val bitrateBps: Int?,
    val sampleRateHz: Int?,
    val channelCount: Int?,
    val bitDepth: Int?,
)

/** Konfiguration des tatsaechlich geoeffneten Audiotracks. */
data class OutputFormatInfo(
    val sampleRateHz: Int,
    val encodingName: String,
    val isFloat: Boolean,
)

/**
 * Verbindet Settings, DSP-Prozessoren und Audioinformationen
 * (ADR-0005). Der PlaybackService bezieht hier die Prozessorkette und
 * meldet Format-/Trackereignisse zurueck; Konfigurationsaenderungen aus
 * dem Store wirken sofort und ohne Pipeline-Flush.
 */
@Singleton
class AudioPipeline
    @Inject
    constructor(
        settingsStore: DspSettingsStore,
        deviceMonitor: OutputDeviceMonitor,
    ) {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        private val masterProcessor = MasterDspProcessor()

        private val mutableDspActive = MutableStateFlow(false)
        val dspActive: StateFlow<Boolean> = mutableDspActive.asStateFlow()

        private val mutableConfig = MutableStateFlow(DspConfig.sanitized(DspConfig()))

        /** Zuletzt angewandte (bereinigte) Konfiguration, u. a. fuer Crossfade. */
        val currentConfig: StateFlow<DspConfig> = mutableConfig.asStateFlow()

        private val mutableSourceFormat = MutableStateFlow<SourceFormatInfo?>(null)
        private val mutableOutputFormat = MutableStateFlow<OutputFormatInfo?>(null)

        /** Live-Audioinformationen; null solange keine Quelle bekannt ist. */
        val audioInfo: Flow<AudioInfo?> =
            combine(
                mutableSourceFormat,
                mutableOutputFormat,
                deviceMonitor.device,
                mutableDspActive,
            ) { source, output, device, dspActive ->
                if (source == null) {
                    null
                } else {
                    AudioInfo(
                        codecMimeType = source.codecMimeType,
                        bitrateBps = source.bitrateBps,
                        sourceSampleRateHz = source.sampleRateHz,
                        sourceChannelCount = source.channelCount,
                        sourceBitDepth = source.bitDepth,
                        outputSampleRateHz = output?.sampleRateHz,
                        outputEncoding = output?.encodingName,
                        floatOutput = output?.isFloat == true,
                        dspActive = dspActive,
                        outputDevice = device.kind,
                        outputDeviceName = device.name,
                    )
                }
            }

        init {
            scope.launch {
                settingsStore.config.collect { apply(it) }
            }
        }

        /** Prozessorkette fuer den DefaultAudioSink (Reihenfolge ADR-0005). */
        fun audioProcessors(): Array<AudioProcessor> = arrayOf(masterProcessor)

        private val mutableDuckingGain = MutableStateFlow(1.0)

        /** Aktueller Cue-Ducking-Gain am Preamp-Knoten (1.0 = kein Ducking). */
        val duckingGain: StateFlow<Double> = mutableDuckingGain.asStateFlow()

        /**
         * Cue-Ducking auf dem Preamp-Knoten (Plan Phase 1.5): wirkt in der
         * 64-Bit-Kette vor Preamp/DVC und kollidiert daher weder mit der
         * Nutzerlautstaerke noch mit der digitalen Lautstaerke (DVC).
         */
        fun setDuckingGain(gain: Double) {
            val sanitized = gain.coerceIn(0.0, 1.0)
            mutableDuckingGain.value = sanitized
            masterProcessor.setDuckingGain(sanitized)
        }

        fun onSourceFormatChanged(info: SourceFormatInfo) {
            mutableSourceFormat.value = info
        }

        fun onAudioTrackInitialized(info: OutputFormatInfo) {
            mutableOutputFormat.value = info
        }

        /** Wiedergabe beendet oder Player freigegeben. */
        fun onPlaybackReleased() {
            mutableSourceFormat.value = null
            mutableOutputFormat.value = null
        }

        private fun apply(config: DspConfig) {
            val sanitized = DspConfig.sanitized(config)
            mutableConfig.value = sanitized
            // MusicFX aktiv: interne Kette stumm, sonst Doppel-EQ (Phase 4).
            // Bit-Perfect (ADR-0009): DSP-Kette komplett umgangen.
            val effective =
                if (sanitized.useSystemEffects || sanitized.bitPerfectEnabled) {
                    sanitized.copy(enabled = false)
                } else {
                    sanitized
                }
            masterProcessor.submitConfig(effective)
            mutableDspActive.value = effective.enabled && !isNeutral(effective)
        }

        /** true, wenn keine klangliche Stufe eingreift. */
        private fun isNeutral(config: DspConfig): Boolean =
            config.preampDb == 0.0 &&
                !config.eq.enabled &&
                config.bassGainDb == 0.0 &&
                config.trebleGainDb == 0.0 &&
                config.stereoWidthPercent == StereoMatrix.NEUTRAL_WIDTH_PERCENT &&
                !config.reverb.enabled &&
                config.resampler.targetRateHz == null &&
                config.ditherMode == DitherMode.TPDF &&
                !config.dvcEnabled
    }
