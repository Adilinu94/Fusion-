package com.dropsync.feature.settings

import com.dropsync.core.common.AppResult
import com.dropsync.core.model.AccentColor
import com.dropsync.core.model.RestMusicBehavior
import com.dropsync.core.model.Song
import com.dropsync.core.model.SongMarker
import com.dropsync.core.model.ThemeMode
import com.dropsync.domain.audio.AudioEngineRepository
import com.dropsync.domain.audio.AudioInfo
import com.dropsync.domain.audio.BitPerfectSupport
import com.dropsync.domain.audio.DspConfig
import com.dropsync.domain.audio.EqBand
import com.dropsync.domain.audio.EqPreset
import com.dropsync.domain.health.HeartRateAvailability
import com.dropsync.domain.health.HeartRateSample
import com.dropsync.domain.health.HeartRateSource
import com.dropsync.domain.library.CueVirtualTrack
import com.dropsync.domain.library.FolderScanResult
import com.dropsync.domain.library.ImportReport
import com.dropsync.domain.library.ImportedTrack
import com.dropsync.domain.library.LibraryListConfig
import com.dropsync.domain.library.LibraryRepository
import com.dropsync.domain.library.LibraryScanResult
import com.dropsync.domain.library.LibraryViewConfig
import com.dropsync.domain.library.LibraryViewPreferencesRepository
import com.dropsync.domain.library.MarkerRepository
import com.dropsync.domain.library.ScannedFile
import com.dropsync.domain.playback.RestMusicSettingsRepository
import com.dropsync.domain.settings.AccentColorRepository
import com.dropsync.domain.settings.DebugSettingsRepository
import com.dropsync.domain.settings.ThemeSettingsRepository
import com.dropsync.domain.workout.WorkoutGoalRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf

/**
 * Fakes fuer `SettingsViewModelTest` (Verbesserungsplan B-UI-1).
 *
 * Bewusst modul-lokal statt in `:core:testing`: diese Vertraege werden nur
 * hier gebraucht, und `:core:testing` waere sonst das einzige Modul, das
 * `:domain:library` und `:domain:audio` allein wegen der Fakes kennt.
 *
 * Die Setter halten ihren Wert in einem `MutableStateFlow`, damit ein Test
 * "geschrieben und wieder gelesen" pruefen kann, nicht nur "nicht
 * abgestuerzt".
 */
class FakeRestMusicSettingsRepository(
    initial: RestMusicBehavior = RestMusicBehavior.NORMAL,
    initialDropAuto: Boolean = RestMusicSettingsRepository.DEFAULT_DROP_AUTO_ENABLED,
) : RestMusicSettingsRepository {
    private val state = MutableStateFlow(initial)
    override val behavior: Flow<RestMusicBehavior> = state

    /** Zuletzt gesetzter Wert; null, wenn nie geschrieben wurde. */
    var lastWritten: RestMusicBehavior? = null
        private set

    private val dropAutoState = MutableStateFlow(initialDropAuto)
    override val dropAutoEnabled: Flow<Boolean> = dropAutoState

    var lastDropAutoWritten: Boolean? = null
        private set

    override suspend fun setBehavior(behavior: RestMusicBehavior) {
        lastWritten = behavior
        state.value = behavior
    }

    override suspend fun setDropAutoEnabled(enabled: Boolean) {
        lastDropAutoWritten = enabled
        dropAutoState.value = enabled
    }
}

/**
 * P2-17/RC-7: Entwickler-Schalter. Startwert explizit waehlbar, damit Tests
 * sowohl "aus" (Default) als auch "an" (Diagnose sichtbar) abdecken.
 */
class FakeDebugSettingsRepository(
    initial: Boolean = false,
) : DebugSettingsRepository {
    private val state = MutableStateFlow(initial)
    override val diagnosticsEnabled: Flow<Boolean> = state

    /** Zuletzt gesetzter Wert; null, wenn nie geschrieben wurde. */
    var lastWritten: Boolean? = null
        private set

    override suspend fun setDiagnosticsEnabled(enabled: Boolean) {
        lastWritten = enabled
        state.value = enabled
    }
}

class FakeThemeSettingsRepository(
    initial: ThemeMode = ThemeMode.SYSTEM,
) : ThemeSettingsRepository {
    private val state = MutableStateFlow(initial)
    override val themeMode: Flow<ThemeMode> = state

    var lastWritten: ThemeMode? = null
        private set

    override suspend fun setThemeMode(mode: ThemeMode) {
        lastWritten = mode
        state.value = mode
    }
}

class FakeAccentColorRepository(
    initial: AccentColor = AccentColor.LIME,
) : AccentColorRepository {
    private val state = MutableStateFlow(initial)
    override val accentColor: Flow<AccentColor> = state

    var lastWritten: AccentColor? = null
        private set

    override suspend fun setAccentColor(color: AccentColor) {
        lastWritten = color
        state.value = color
    }
}

class FakeLibraryViewPreferencesRepository(
    smartShuffle: Boolean = false,
) : LibraryViewPreferencesRepository {
    private val shuffleState = MutableStateFlow(smartShuffle)

    override val config: Flow<LibraryViewConfig?> = flowOf(null)

    override suspend fun setConfig(config: LibraryViewConfig) = Unit

    override val smartShuffleEnabled: Flow<Boolean> = shuffleState

    var lastShuffleWritten: Boolean? = null
        private set

    override suspend fun setSmartShuffleEnabled(enabled: Boolean) {
        lastShuffleWritten = enabled
        shuffleState.value = enabled
    }

    override fun listConfig(categoryKey: String): Flow<LibraryListConfig?> = flowOf(null)

    override suspend fun setListConfig(
        categoryKey: String,
        config: LibraryListConfig,
    ) = Unit
}

/**
 * Haelt die DSP-Konfiguration wie der echte Store: `updateDspConfig`
 * schreibt, `dspConfig` liest denselben Zustand. Ohne das wuerden die
 * Mix-Tests eine Konfiguration lesen, die ihr eigener Aufruf nie erreicht
 * hat.
 */
class FakeAudioEngineRepository(
    initial: DspConfig = DspConfig(),
) : AudioEngineRepository {
    private val state = MutableStateFlow(initial)
    override val dspConfig: Flow<DspConfig> = state

    /** Alle Schreibvorgaenge in Reihenfolge - fuer "genau einmal"-Pruefungen. */
    val writes = mutableListOf<DspConfig>()

    val current: DspConfig get() = state.value

    override val audioInfo: Flow<AudioInfo?> = flowOf(null)

    override suspend fun updateDspConfig(config: DspConfig) {
        writes += config
        state.value = config
    }

    override val eqPresets: Flow<List<EqPreset>> = flowOf(emptyList())

    override suspend fun saveEqPreset(
        name: String,
        bands: List<EqBand>,
    ): AppResult<Long> = AppResult.success(1L)

    override suspend fun deleteEqPreset(id: Long): AppResult<Unit> = AppResult.success(Unit)

    override suspend fun applyEqPreset(id: Long): AppResult<Unit> = AppResult.success(Unit)

    override val activeOutputProfileKey: Flow<String?> = flowOf(null)

    override val bitPerfectSupport: Flow<BitPerfectSupport> =
        flowOf(BitPerfectSupport.UNAVAILABLE)
}

class FakeMarkerRepository(
    unmatched: List<SongMarker> = emptyList(),
    private val importResult: AppResult<ImportReport> =
        AppResult.success(ImportReport(added = 0, updated = 0, unmatched = 0, rejectedViolations = emptyList())),
) : MarkerRepository {
    override val unmatchedMarkers: Flow<List<SongMarker>> = flowOf(unmatched)

    /** Argumente des letzten `importDocument`-Aufrufs. */
    var lastImport: Pair<Int, List<ImportedTrack>>? = null
        private set

    var lastLink: Pair<Long, Long>? = null
        private set

    override suspend fun importDocument(
        schemaVersion: Int,
        tracks: List<ImportedTrack>,
    ): AppResult<ImportReport> {
        lastImport = schemaVersion to tracks
        return importResult
    }

    override suspend fun linkManually(
        markerId: Long,
        songId: Long,
    ): AppResult<Unit> {
        lastLink = markerId to songId
        return AppResult.success(Unit)
    }

    override suspend fun getEnabledMarkersForSong(songId: Long): AppResult<List<SongMarker>> =
        AppResult.success(emptyList())

    override suspend fun getEnabledMarkersForSongs(songIds: List<Long>): AppResult<Map<Long, List<SongMarker>>> =
        AppResult.success(emptyMap())

    override fun observeEnabledMarkersForSong(songId: Long): Flow<List<SongMarker>> = flowOf(emptyList())

    override suspend fun createManualMarker(
        songId: Long,
        label: String,
        positionMs: Long,
    ): AppResult<SongMarker> = error("im Test nicht benutzt")

    override suspend fun deleteMarker(markerId: Long): AppResult<Unit> = AppResult.success(Unit)

    override val pendingAutoDetectedMarkers: Flow<List<SongMarker>> = flowOf(emptyList())

    override suspend fun confirmMarker(markerId: Long): AppResult<Unit> = AppResult.success(Unit)

    override val songsWithEnabledMarkers: Flow<Set<Long>> = flowOf(emptySet())

    override suspend fun setMarkerEnabled(
        markerId: Long,
        enabled: Boolean,
    ): AppResult<Unit> = AppResult.success(Unit)

    override suspend fun restoreMarker(marker: SongMarker): AppResult<Unit> = AppResult.success(Unit)

    override suspend fun moveMarker(
        markerId: Long,
        newPositionMs: Long,
    ): AppResult<Unit> = AppResult.success(Unit)

    override suspend fun renameMarker(
        markerId: Long,
        newLabel: String,
    ): AppResult<Unit> = AppResult.success(Unit)
}

class FakeLibraryRepository(
    private val available: List<Song> = emptyList(),
) : LibraryRepository {
    override val songs: Flow<List<Song>> = flowOf(available)
    override val availableSongs: Flow<List<Song>> = flowOf(available)

    override suspend fun refreshLibrary(force: Boolean): AppResult<LibraryScanResult> = error("im Test nicht benutzt")

    override suspend fun getSong(mediaStoreId: Long): AppResult<Song> = error("im Test nicht benutzt")

    override suspend fun markUnavailable(mediaStoreId: Long): AppResult<Unit> = AppResult.success(Unit)

    override suspend fun importCueSheet(
        songId: Long,
        cueText: String,
    ): AppResult<Int> = error("im Test nicht benutzt")

    override fun observeCueTracks(songId: Long): Flow<List<CueVirtualTrack>> = flowOf(emptyList())

    override suspend fun scanFolder(treeUri: String): AppResult<FolderScanResult> = error("im Test nicht benutzt")

    override val scannedFiles: Flow<List<ScannedFile>> = flowOf(emptyList())
}

/**
 * Eigener Goal-Fake statt `core:testing`-Variante: die dortige verwirft den
 * geschriebenen Wert (`= Unit`), womit "Setter schreibt in sein Repository"
 * nicht pruefbar waere.
 */
class RecordingWorkoutGoalRepository(
    initial: Int = WorkoutGoalRepository.DEFAULT_WEEKLY_GOAL,
) : WorkoutGoalRepository {
    private val state = MutableStateFlow(initial)
    override val weeklyTrainingGoal: Flow<Int> = state

    var lastWritten: Int? = null
        private set

    override suspend fun setWeeklyTrainingGoal(days: Int) {
        lastWritten = days
        state.value = days
    }
}

/**
 * Modul-lokaler Herzfrequenz-Fake (B5): Sync-Schalter als StateFlow plus
 * frei setzbare Verfuegbarkeit — wie die anderen Fakes hier schreibt der
 * Setter denselben Zustand, den der Strom liest.
 */
class FakeHeartRateSourceForSettings(
    syncEnabled: Boolean = true,
    availability: HeartRateAvailability = HeartRateAvailability.READY,
) : HeartRateSource {
    private val syncState = MutableStateFlow(syncEnabled)
    private val availabilityState = MutableStateFlow(availability)

    override val heartRateSyncEnabled: Flow<Boolean> = syncState
    override val availability: Flow<HeartRateAvailability> = availabilityState
    override val latestSample: Flow<HeartRateSample?> = flowOf(null)
    override val requiredPermissions: Set<String> = emptySet()

    var lastSyncWritten: Boolean? = null
        private set

    override suspend fun setHeartRateSyncEnabled(enabled: Boolean) {
        lastSyncWritten = enabled
        syncState.value = enabled
    }

    override suspend fun refreshAvailability() = Unit

    override suspend fun refresh(): AppResult<Unit> = AppResult.success(Unit)
}
