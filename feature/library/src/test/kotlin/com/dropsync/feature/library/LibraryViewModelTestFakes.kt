package com.dropsync.feature.library

import com.dropsync.core.common.AppResult
import com.dropsync.core.model.Song
import com.dropsync.domain.audio.TrackAnalysis
import com.dropsync.domain.audio.TrackAnalysisRepository
import com.dropsync.domain.library.CueVirtualTrack
import com.dropsync.domain.library.FolderScanResult
import com.dropsync.domain.library.LibraryListConfig
import com.dropsync.domain.library.LibraryRepository
import com.dropsync.domain.library.LibraryScanResult
import com.dropsync.domain.library.LibraryViewConfig
import com.dropsync.domain.library.LibraryViewPreferencesRepository
import com.dropsync.domain.library.MusicFolderFilterRepository
import com.dropsync.domain.library.ScannedFile
import com.dropsync.domain.playback.DropLandingEvent
import com.dropsync.domain.playback.PersistedPlayerState
import com.dropsync.domain.playback.PlaybackRepository
import com.dropsync.domain.playback.PlaybackState
import com.dropsync.domain.playback.QueueItem
import com.dropsync.domain.playback.RepeatMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf

/** C4: minimaler Song-Bau fuer Library-Tests. */
internal fun testSong(
    id: Long,
    title: String = "Titel $id",
    durationMs: Long = 180_000L,
): Song =
    Song(
        mediaStoreId = id,
        contentUri = "content://song/$id",
        displayName = "$title.mp3",
        relativePath = "Musik/$title.mp3",
        durationMs = durationMs,
        sizeBytes = 1_000L,
        dateModifiedSeconds = 0L,
        title = title,
        artist = "Artist",
        album = "Album",
        isAvailable = true,
    )

/** C4: LibraryRepository-Fake (nur was die Tests brauchen). */
internal class FakeLibraryRepository(
    songs: List<Song> = emptyList(),
) : LibraryRepository {
    val songsFlow = MutableStateFlow(songs)
    override val songs: Flow<List<Song>> = songsFlow
    override val availableSongs: Flow<List<Song>> = songsFlow
    override val scannedFiles: Flow<List<ScannedFile>> = flowOf(emptyList())

    override suspend fun refreshLibrary(force: Boolean): AppResult<LibraryScanResult> =
        AppResult.failure(
            com.dropsync.core.common.AppError
                .Unknown("nicht Teil dieses Tests"),
        )

    override suspend fun getSong(mediaStoreId: Long): AppResult<Song> =
        songsFlow.value
            .firstOrNull { it.mediaStoreId == mediaStoreId }
            ?.let { AppResult.Success(it) }
            ?: AppResult.failure(
                com.dropsync.core.common.AppError
                    .MediaUnavailable(mediaStoreId),
            )

    override suspend fun markUnavailable(mediaStoreId: Long): AppResult<Unit> = AppResult.Success(Unit)

    override suspend fun importCueSheet(
        songId: Long,
        cueText: String,
    ): AppResult<Int> =
        AppResult.failure(
            com.dropsync.core.common.AppError
                .Unknown("nicht Teil dieses Tests"),
        )

    override fun observeCueTracks(songId: Long): Flow<List<CueVirtualTrack>> = flowOf(emptyList())

    override suspend fun scanFolder(treeUri: String): AppResult<FolderScanResult> =
        AppResult.failure(
            com.dropsync.core.common.AppError
                .Unknown("nicht Teil dieses Tests"),
        )
}

/** C4: Playback-Fake mit Aufzeichnung der Vorschau-/Queue-Aufrufe. */
internal class FakePlaybackRepository : PlaybackRepository {
    val stateFlow = MutableStateFlow(PlaybackState())
    val setQueueCalls = mutableListOf<Pair<List<Song>, Int>>()
    val playSongAtCalls = mutableListOf<Pair<Song, Long>>()
    val playCalls = mutableListOf<Unit>()

    override val state: Flow<PlaybackState> = stateFlow
    override val landingEvents: Flow<DropLandingEvent> = flowOf()

    override suspend fun setQueue(
        songs: List<Song>,
        startIndex: Int,
        playWhenReady: Boolean,
    ): AppResult<Unit> {
        setQueueCalls += songs to startIndex
        return AppResult.Success(Unit)
    }

    override suspend fun play(): AppResult<Unit> {
        playCalls += Unit
        return AppResult.Success(Unit)
    }

    override suspend fun pause(): AppResult<Unit> = AppResult.Success(Unit)

    override suspend fun seekTo(positionMs: Long): AppResult<Unit> = AppResult.Success(Unit)

    override suspend fun skipToNext(): AppResult<Unit> = AppResult.Success(Unit)

    override suspend fun skipToPrevious(): AppResult<Unit> = AppResult.Success(Unit)

    override suspend fun skipToQueueIndex(index: Int): AppResult<Unit> = AppResult.Success(Unit)

    override suspend fun moveInQueue(
        fromIndex: Int,
        toIndex: Int,
    ): AppResult<Unit> = AppResult.Success(Unit)

    override suspend fun removeFromQueue(index: Int): AppResult<Unit> = AppResult.Success(Unit)

    override suspend fun playNext(song: Song): AppResult<Unit> = AppResult.Success(Unit)

    override suspend fun addToQueueEnd(song: Song): AppResult<Unit> = AppResult.Success(Unit)

    override suspend fun setShuffle(enabled: Boolean): AppResult<Unit> = AppResult.Success(Unit)

    override suspend fun setRepeatMode(mode: RepeatMode): AppResult<Unit> = AppResult.Success(Unit)

    override suspend fun setPlaybackSpeed(speed: Float): AppResult<Unit> = AppResult.Success(Unit)

    override suspend fun lastPersistedState(): PersistedPlayerState? = null

    override suspend fun snapshotNow(): AppResult<PlaybackState> = AppResult.Success(stateFlow.value)

    override suspend fun playSongAt(
        song: Song,
        startPositionMs: Long,
    ): AppResult<Unit> {
        playSongAtCalls += song to startPositionMs
        return AppResult.Success(Unit)
    }

    override suspend fun armLanding(
        song: Song,
        startPositionMs: Long,
        delayMs: Long,
        fadeMs: Long,
    ): AppResult<Unit> = AppResult.Success(Unit)

    override suspend fun cancelLanding(): AppResult<Unit> = AppResult.Success(Unit)

    override suspend fun setScrubbingMode(enabled: Boolean): AppResult<Unit> = AppResult.Success(Unit)
}

/** C4: View-Preferences-Fake (Defaults, Schreibpfade aufgezeichnet). */
internal class FakeViewPreferences : LibraryViewPreferencesRepository {
    override val config: Flow<LibraryViewConfig?> = flowOf(null)
    override val smartShuffleEnabled: Flow<Boolean> = flowOf(false)

    override suspend fun setConfig(config: LibraryViewConfig) = Unit

    override suspend fun setSmartShuffleEnabled(enabled: Boolean) = Unit

    override fun listConfig(categoryKey: String): Flow<LibraryListConfig?> = flowOf(null)

    override suspend fun setListConfig(
        categoryKey: String,
        config: LibraryListConfig,
    ) = Unit
}

/** C4: Analyse-Fake; zeichnet die angestossenen Erkennungen auf. */
internal class FakeTrackAnalysisRepository : TrackAnalysisRepository {
    val onsetRequests = mutableListOf<Long>()

    override fun observeAnalysis(songId: Long): Flow<TrackAnalysis?> = flowOf(null)

    override suspend fun requestAnalysis(song: Song) = Unit

    override suspend fun requestAnalysisForNewSongs(songs: List<Song>) = Unit

    override suspend fun requestAnalysisPrewarm(
        songs: List<Song>,
        limit: Int,
    ) = Unit

    override suspend fun requestOnsetDetection(song: Song) {
        onsetRequests += song.mediaStoreId
    }
}

/** C4: Ordnerfilter-Fake (nichts ausgeschlossen). */
internal class FakeMusicFolderFilter : MusicFolderFilterRepository {
    override val excludedFolders: Flow<Set<String>> = flowOf(emptySet())

    override suspend fun setExcludedFolders(paths: Set<String>) = Unit
}

/** C4: QueueItem-Bau fuer PlaybackState-Zustaende (Music Home). */
internal fun testQueueItem(song: Song): QueueItem =
    QueueItem(
        mediaId = song.mediaStoreId.toString(),
        songId = song.mediaStoreId,
        title = song.title ?: song.displayName,
        artist = song.artist,
    )
