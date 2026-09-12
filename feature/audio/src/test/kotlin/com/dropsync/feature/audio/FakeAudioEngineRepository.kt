package com.dropsync.feature.audio

import com.dropsync.core.common.AppResult
import com.dropsync.domain.audio.AudioEngineRepository
import com.dropsync.domain.audio.AudioInfo
import com.dropsync.domain.audio.BitPerfectSupport
import com.dropsync.domain.audio.DspConfig
import com.dropsync.domain.audio.EqBand
import com.dropsync.domain.audio.EqPreset
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf

/**
 * Fakes fuer `AudioSettingsViewModelTest` (Verbesserungsplan B-UI-1, P2-15).
 *
 * Bewusst modul-lokal nach dem Muster von `feature/settings`/SettingsFakes.kt
 * statt in `:core:testing`: dieser Vertrag wird nur hier gebraucht, und die
 * Recording-Listen (Preset-IDs, gespeicherte Namen) waeren in der geteilten
 * Bibliothek Ballast fuer alle anderen Module.
 *
 * `updateDspConfig` schreibt in denselben Zustand, aus dem `dspConfig` liest
 * — ohne das koennte der Test nicht "geschrieben und wieder gelesen" pruefen,
 * sondern nur "nicht abgestuerzt".
 */
class FakeAudioEngineRepository(
    initial: DspConfig = DspConfig(),
) : AudioEngineRepository {
    private val state = MutableStateFlow(initial)
    override val dspConfig: Flow<DspConfig> = state

    /** Alle Schreibvorgaenge in Reihenfolge - fuer "genau einmal"-Pruefungen. */
    val writes = mutableListOf<DspConfig>()

    /** Zuletzt persistierte Konfiguration (Lesegegenstelle der Tests). */
    val current: DspConfig get() = state.value

    /** IDs der angewendeten Presets in Aufrufreihenfolge. */
    val appliedPresetIds = mutableListOf<Long>()

    /** (Name, Baender) der gespeicherten Presets in Aufrufreihenfolge. */
    val savedPresets = mutableListOf<Pair<String, List<EqBand>>>()

    /** IDs der geloeschten Presets in Aufrufreihenfolge. */
    val deletedPresetIds = mutableListOf<Long>()

    override val audioInfo: Flow<AudioInfo?> = flowOf(null)

    override suspend fun updateDspConfig(config: DspConfig) {
        writes += config
        state.value = config
    }

    override val eqPresets: Flow<List<EqPreset>> = flowOf(emptyList())

    override suspend fun saveEqPreset(
        name: String,
        bands: List<EqBand>,
    ): AppResult<Long> {
        savedPresets += name to bands
        return AppResult.success(savedPresets.size.toLong())
    }

    override suspend fun deleteEqPreset(id: Long): AppResult<Unit> {
        deletedPresetIds += id
        return AppResult.success(Unit)
    }

    override suspend fun applyEqPreset(id: Long): AppResult<Unit> {
        appliedPresetIds += id
        return AppResult.success(Unit)
    }

    override val activeOutputProfileKey: Flow<String?> = flowOf(null)

    override val bitPerfectSupport: Flow<BitPerfectSupport> =
        flowOf(BitPerfectSupport.UNAVAILABLE)
}
