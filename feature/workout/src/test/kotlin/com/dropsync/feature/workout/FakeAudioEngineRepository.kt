package com.dropsync.feature.workout

import com.dropsync.core.common.AppResult
import com.dropsync.core.model.PlaylistLabel
import com.dropsync.core.model.Song
import com.dropsync.domain.audio.AudioEngineRepository
import com.dropsync.domain.audio.AudioInfo
import com.dropsync.domain.audio.BitPerfectSupport
import com.dropsync.domain.audio.DspConfig
import com.dropsync.domain.audio.EqBand
import com.dropsync.domain.audio.EqPreset
import com.dropsync.domain.library.Album
import com.dropsync.domain.library.Artist
import com.dropsync.domain.library.Genre
import com.dropsync.domain.library.LibraryBrowseRepository
import com.dropsync.domain.library.LibraryFolder
import com.dropsync.domain.library.Playlist
import com.dropsync.domain.library.PlaylistImportResult
import com.dropsync.domain.library.ShuffleCandidate
import com.dropsync.domain.library.SongPlayStat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf

/**
 * C3: minimaler DSP-Fake — der Ducking-Wert ist schreib- und lesbar, damit
 * "am Ort" und Einstellungen denselben Wert teilen (ein Test wertet das aus).
 */
internal class FakeAudioEngineRepository : AudioEngineRepository {
    private val config = MutableStateFlow(DspConfig())

    override val dspConfig: Flow<DspConfig> = config

    override val audioInfo: Flow<AudioInfo?> = flowOf(null)

    override suspend fun updateDspConfig(config: DspConfig) {
        this.config.value = config
    }

    override val eqPresets: Flow<List<EqPreset>> = flowOf(emptyList())

    override suspend fun saveEqPreset(
        name: String,
        bands: List<EqBand>,
    ): AppResult<Long> = AppResult.Success(0L)

    override suspend fun deleteEqPreset(id: Long): AppResult<Unit> = AppResult.Success(Unit)

    override suspend fun applyEqPreset(id: Long): AppResult<Unit> = AppResult.Success(Unit)

    override val activeOutputProfileKey: Flow<String?> = flowOf(null)

    override val bitPerfectSupport: Flow<BitPerfectSupport> = flowOf(BitPerfectSupport.UNAVAILABLE)
}
