package com.dropsync.data.audio

import com.dropsync.core.common.AppError
import com.dropsync.core.common.AppResult
import com.dropsync.core.common.DispatcherProvider
import com.dropsync.core.database.TransactionRunner
import com.dropsync.core.database.dao.EqPresetDao
import com.dropsync.core.database.entity.EqPresetEntity
import com.dropsync.domain.audio.AudioEngineRepository
import com.dropsync.domain.audio.AudioInfo
import com.dropsync.domain.audio.BitPerfectSupport
import com.dropsync.domain.audio.DspConfig
import com.dropsync.domain.audio.EqBand
import com.dropsync.domain.audio.EqPreset
import com.dropsync.domain.audio.EqSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * Einziger App-Zugang zur Audio-Engine (Modulregel 3.2). DSP-Aenderungen
 * gehen ueber den Store; die [AudioPipeline] beobachtet ihn und wendet
 * Werte sofort an. EQ-Presets liegen in Room (Plan Phase 2): eingebaute
 * sind unloeschbar, Nutzerpresets sind frei benannt (CRUD).
 */
class AudioEngineRepositoryImpl(
    private val settingsStore: DspSettingsStore,
    pipeline: AudioPipeline,
    private val eqPresetDao: EqPresetDao,
    private val transactionRunner: TransactionRunner,
    private val dispatchers: DispatcherProvider,
    profileController: OutputProfileController,
    bitPerfectGateway: BitPerfectGateway,
) : AudioEngineRepository {
    override val dspConfig: Flow<DspConfig> = settingsStore.config

    override val audioInfo: Flow<AudioInfo?> = pipeline.audioInfo

    override val activeOutputProfileKey: Flow<String?> = profileController.activeProfileKey

    override val bitPerfectSupport: Flow<BitPerfectSupport> = bitPerfectGateway.support

    override val eqPresets: Flow<List<EqPreset>> =
        eqPresetDao.observePresets().map { rows -> rows.map { it.toDomain() } }

    override suspend fun updateDspConfig(config: DspConfig) {
        settingsStore.save(DspConfig.sanitized(config))
    }

    override suspend fun saveEqPreset(
        name: String,
        bands: List<EqBand>,
    ): AppResult<Long> =
        withContext(dispatchers.io) {
            val trimmed = name.trim()
            if (trimmed.isEmpty()) {
                return@withContext AppResult.failure(AppError.Unknown("Preset-Name ist leer"))
            }
            if (bands.isEmpty()) {
                return@withContext AppResult.failure(AppError.Unknown("Preset ohne Baender"))
            }
            val sanitized = bands.take(EqSettings.MAX_BANDS).map(EqBand::sanitized)
            try {
                transactionRunner {
                    val existingId = eqPresetDao.getIdByName(trimmed)
                    if (existingId != null) {
                        val existing = eqPresetDao.getPreset(existingId)
                        if (existing?.preset?.isBuiltIn == true) {
                            return@transactionRunner AppResult.failure(
                                AppError.Unknown("Eingebautes Preset '$trimmed' ist gesperrt"),
                            )
                        }
                        eqPresetDao.deleteBands(existingId)
                        eqPresetDao.insertBands(sanitized.toBandEntities(existingId))
                        AppResult.success(existingId)
                    } else {
                        val newId =
                            eqPresetDao.insertPreset(
                                EqPresetEntity(name = trimmed, isBuiltIn = false),
                            )
                        eqPresetDao.insertBands(sanitized.toBandEntities(newId))
                        AppResult.success(newId)
                    }
                }
            } catch (e: Exception) {
                AppResult.failure(AppError.DatabaseFailure("saveEqPreset"))
            }
        }

    override suspend fun deleteEqPreset(id: Long): AppResult<Unit> =
        withContext(dispatchers.io) {
            try {
                val rows = eqPresetDao.deleteUserPreset(id)
                if (rows > 0) {
                    AppResult.success(Unit)
                } else {
                    AppResult.failure(AppError.Unknown("Preset $id fehlt oder ist eingebaut"))
                }
            } catch (e: Exception) {
                AppResult.failure(AppError.DatabaseFailure("deleteEqPreset"))
            }
        }

    override suspend fun applyEqPreset(id: Long): AppResult<Unit> =
        withContext(dispatchers.io) {
            try {
                val preset =
                    eqPresetDao.getPreset(id)?.toDomain()
                        ?: return@withContext AppResult.failure(
                            AppError.Unknown("Preset $id fehlt"),
                        )
                val current = settingsStore.config.first()
                val updated =
                    current.copy(
                        eq = current.eq.copy(enabled = true, bands = preset.bands),
                    )
                settingsStore.save(DspConfig.sanitized(updated))
                AppResult.success(Unit)
            } catch (e: Exception) {
                AppResult.failure(AppError.DatabaseFailure("applyEqPreset"))
            }
        }
}
