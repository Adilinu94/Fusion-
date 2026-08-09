package com.dropsync.feature.audio

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dropsync.domain.audio.AudioEngineRepository
import com.dropsync.domain.audio.AudioInfo
import com.dropsync.domain.audio.BitPerfectSupport
import com.dropsync.domain.audio.DspConfig
import com.dropsync.domain.audio.EqBand
import com.dropsync.domain.audio.EqPreset
import com.dropsync.domain.audio.EqSettings
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * UI-State und Aktionen der Audio-/DSP-Einstellungen (Plan Phase 1/2/5).
 * Einziger Zugang ist [AudioEngineRepository] (Modulregel 3.2). Jede
 * Aenderung wird sofort persistiert; die Pipeline uebernimmt sie live,
 * sodass Anpassungen unmittelbar hoerbar sind.
 */
@HiltViewModel
class AudioSettingsViewModel
    @Inject
    constructor(
        private val repository: AudioEngineRepository,
    ) : ViewModel() {
        val dspConfig: StateFlow<DspConfig> =
            repository.dspConfig.stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5_000),
                DspConfig(),
            )

        val audioInfo: StateFlow<AudioInfo?> =
            repository.audioInfo.stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5_000),
                null,
            )

        val eqPresets: StateFlow<List<EqPreset>> =
            repository.eqPresets.stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5_000),
                emptyList(),
            )

        val activeOutputProfileKey: StateFlow<String?> =
            repository.activeOutputProfileKey.stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5_000),
                null,
            )

        val bitPerfectSupport: StateFlow<BitPerfectSupport> =
            repository.bitPerfectSupport.stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5_000),
                BitPerfectSupport.UNAVAILABLE,
            )

        /** Wendet [transform] auf die aktuelle Konfiguration an und speichert. */
        fun update(transform: (DspConfig) -> DspConfig) {
            val next = transform(dspConfig.value)
            viewModelScope.launch { repository.updateDspConfig(next) }
        }

        /** Setzt den Grafik-EQ auf [count] neutrale ISO-Baender (10/15/31). */
        fun setGraphicBandCount(count: Int) {
            update { config ->
                config.copy(eq = config.eq.copy(bands = EqSettings.graphicBands(count)))
            }
        }

        /** Aendert den Gain eines einzelnen Bandes (Grafik- und Parametrik-EQ). */
        fun setBandGain(
            index: Int,
            gainDb: Double,
        ) {
            update { config ->
                val bands =
                    config.eq.bands.mapIndexed { i, band ->
                        if (i == index) band.copy(gainDb = gainDb) else band
                    }
                config.copy(eq = config.eq.copy(bands = bands))
            }
        }

        fun applyPreset(id: Long) {
            viewModelScope.launch { repository.applyEqPreset(id) }
        }

        fun savePreset(
            name: String,
            bands: List<EqBand>,
        ) {
            viewModelScope.launch { repository.saveEqPreset(name, bands) }
        }

        fun deletePreset(id: Long) {
            viewModelScope.launch { repository.deleteEqPreset(id) }
        }
    }
