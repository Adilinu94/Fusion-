package com.dropsync.feature.player

import app.cash.turbine.test
import com.dropsync.core.common.AppError
import com.dropsync.core.common.AppResult
import com.dropsync.core.model.Song
import com.dropsync.core.model.SongMarker
import com.dropsync.domain.audio.AudioEngineRepository
import com.dropsync.domain.audio.AudioInfo
import com.dropsync.domain.audio.BitPerfectSupport
import com.dropsync.domain.audio.DspConfig
import com.dropsync.domain.audio.EqBand
import com.dropsync.domain.audio.EqPreset
import com.dropsync.domain.audio.TrackAnalysis
import com.dropsync.domain.audio.TrackAnalysisRepository
import com.dropsync.domain.audio.WaveformBucket
import com.dropsync.domain.library.Album
import com.dropsync.domain.library.Artist
import com.dropsync.domain.library.CueVirtualTrack
import com.dropsync.domain.library.FolderScanResult
import com.dropsync.domain.library.Genre
import com.dropsync.domain.library.LibraryBrowseRepository
import com.dropsync.domain.library.LibraryFolder
import com.dropsync.domain.library.LibraryRepository
import com.dropsync.domain.library.LibraryScanResult
import com.dropsync.domain.library.MarkerRepository
import com.dropsync.domain.library.Playlist
import com.dropsync.domain.library.PlaylistImportResult
import com.dropsync.domain.library.ScannedFile
import com.dropsync.domain.library.ShuffleCandidate
import com.dropsync.domain.library.SongPlayStat
import com.dropsync.domain.playback.PersistedPlayerState
import com.dropsync.domain.playback.PlaybackRepository
import com.dropsync.domain.playback.PlaybackState
import com.dropsync.domain.playback.QueueItem
import com.dropsync.domain.playback.RepeatMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Verifikation Marker/Waveform-Plan Phase 1: `nowPlaying` bildet
 * `PlaybackState` korrekt ab (Position, Dauer, Sichtbarkeit bei leerer
 * Queue); der Ticker-Schritt uebernimmt die Position aus `snapshotNow()`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlayerViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var playbackRepository: FakePlaybackRepository
    private lateinit var libraryRepository: FakeLibraryRepository
    private lateinit var trackAnalysisRepository: FakeTrackAnalysisRepository
    private lateinit var markerRepository: FakeMarkerRepository
    private lateinit var audioEngineRepository: FakeAudioEngineRepository

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        playbackRepository = FakePlaybackRepository()
        libraryRepository = FakeLibraryRepository()
        trackAnalysisRepository = FakeTrackAnalysisRepository()
        markerRepository = FakeMarkerRepository()
        audioEngineRepository = FakeAudioEngineRepository()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel() =
        PlayerViewModel(
            playbackRepository,
            libraryRepository,
            libraryRepository,
            trackAnalysisRepository,
            markerRepository,
            audioEngineRepository,
        )

    @Test
    fun `nowPlaying ist unsichtbar bei leerer Queue`() =
        runTest(dispatcher) {
            viewModel().nowPlaying.test {
                assertEquals(NowPlayingUiState(), awaitItem())
            }
        }

    @Test
    fun `nowPlaying bildet PlaybackState mit Song-Metadaten ab`() =
        runTest(dispatcher) {
            libraryRepository.songById[7L] = songFixture(id = 7L, title = "Drop City")
            playbackRepository.stateFlow.value =
                PlaybackState(
                    isPlaying = true,
                    currentSongId = 7L,
                    positionMs = 12_345L,
                    durationMs = 180_000L,
                )

            viewModel().nowPlaying.test {
                val state = awaitItemUntil { it.isVisible }
                assertTrue(state.isPlaying)
                assertEquals("Drop City", state.title)
                assertEquals("Artist 7", state.artist)
                assertEquals(12_345L, state.positionMs)
                assertEquals(180_000L, state.durationMs)
                assertEquals(7L, state.songId)
                assertEquals("content://media/7", state.contentUri)
            }
        }

    @Test
    fun `nowPlaying faellt auf displayName zurueck wenn Titel fehlt`() =
        runTest(dispatcher) {
            libraryRepository.songById[3L] = songFixture(id = 3L, title = null)
            playbackRepository.stateFlow.value = PlaybackState(currentSongId = 3L)

            viewModel().nowPlaying.test {
                val state = awaitItemUntil { it.isVisible }
                assertEquals("song3.mp3", state.title)
                assertFalse(state.isPlaying)
            }
        }

    @Test
    fun `refreshPosition uebernimmt Position aus snapshotNow`() =
        runTest(dispatcher) {
            playbackRepository.snapshot =
                AppResult.success(PlaybackState(currentSongId = 7L, positionMs = 42_000L))
            val vm = viewModel()
            assertNull(vm.livePositionMs.value)

            vm.refreshPosition()
            dispatcher.scheduler.advanceUntilIdle()

            assertEquals(42_000L, vm.livePositionMs.value)
        }

    @Test
    fun `refreshPosition ignoriert Fehler von snapshotNow`() =
        runTest(dispatcher) {
            playbackRepository.snapshot =
                AppResult.failure(AppError.Unknown("kein Controller"))
            val vm = viewModel()

            vm.refreshPosition()
            dispatcher.scheduler.advanceUntilIdle()

            assertNull(vm.livePositionMs.value)
        }

    @Test
    fun `seekTo und skipToPrevious delegieren an das Repository`() =
        runTest(dispatcher) {
            val vm = viewModel()

            vm.seekTo(9_000L)
            vm.skipToPrevious()
            dispatcher.scheduler.advanceUntilIdle()

            assertEquals(listOf(9_000L), playbackRepository.seekCalls)
            assertEquals(1, playbackRepository.skipToPreviousCalls)
        }

    @Test
    fun `waveform ist Hidden ohne laufenden Song`() =
        runTest(dispatcher) {
            viewModel().waveform.test {
                assertEquals(WaveformUiState.Hidden, awaitItem())
            }
        }

    @Test
    fun `waveform laedt bis der Cache gefuellt ist und liefert dann normalisierte Buckets`() =
        runTest(dispatcher) {
            playbackRepository.stateFlow.value = PlaybackState(currentSongId = 7L)

            viewModel().waveform.test {
                awaitItemUntilWaveform { it == WaveformUiState.Loading }

                trackAnalysisRepository.analyses.value =
                    mapOf(
                        7L to
                            TrackAnalysis(
                                waveformBuckets = listOf(WaveformBucket(min = -127, max = 127)),
                                onsetCandidatesMs = emptyList(),
                            ),
                    )

                val ready = awaitItemUntilWaveform { it is WaveformUiState.Ready } as WaveformUiState.Ready
                assertEquals(1, ready.buckets.size)
                assertEquals(-1f, ready.buckets[0].first, 1e-6f)
                assertEquals(1f, ready.buckets[0].second, 1e-6f)
            }
        }

    @Test
    fun `waveform faellt bei persistiertem Fehlerfall auf Unavailable zurueck`() =
        runTest(dispatcher) {
            playbackRepository.stateFlow.value = PlaybackState(currentSongId = 9L)
            trackAnalysisRepository.analyses.value =
                mapOf(9L to TrackAnalysis(waveformBuckets = emptyList(), onsetCandidatesMs = emptyList()))

            viewModel().waveform.test {
                awaitItemUntilWaveform { it == WaveformUiState.Unavailable }
            }
        }

    @Test
    fun `leiser Track wird in der Waveform auf den Boden angehoben`() =
        runTest(dispatcher) {
            playbackRepository.stateFlow.value = PlaybackState(currentSongId = 11L)
            trackAnalysisRepository.analyses.value =
                mapOf(
                    11L to
                        TrackAnalysis(
                            waveformBuckets = listOf(WaveformBucket(min = 0, max = 10)),
                            onsetCandidatesMs = emptyList(),
                            // Peak 0.2 -> Anzeige-Faktor 1.75 (Boden 0.35).
                            peakLinear = 0.2,
                        ),
                )

            viewModel().waveform.test {
                val ready =
                    awaitItemUntilWaveform { it is WaveformUiState.Ready } as WaveformUiState.Ready
                assertEquals(1, ready.buckets.size)
                assertEquals(10 / 127f * 1.75f, ready.buckets[0].second, 1e-4f)
            }
        }

    @Test
    fun `requestAnalysis reicht den geladenen Song an das Analyse-Repository durch`() =
        runTest(dispatcher) {
            libraryRepository.songById[5L] = songFixture(id = 5L, title = "Peak")
            val vm = viewModel()

            vm.requestAnalysis(5L)
            vm.requestAnalysis(null)
            dispatcher.scheduler.advanceUntilIdle()

            assertEquals(listOf(5L), trackAnalysisRepository.requestedSongIds)
        }

    @Test
    fun `nowPlayingMarkers laedt die aktiven Marker des laufenden Songs`() =
        runTest(dispatcher) {
            markerRepository.markersBySong[7L] =
                listOf(markerFixture(id = 1L, positionMs = 42_000L, songId = 7L))
            playbackRepository.stateFlow.value = PlaybackState(currentSongId = 7L)

            viewModel().nowPlayingMarkers.test {
                var items = awaitItem()
                while (items.isEmpty()) items = awaitItem()
                assertEquals(42_000L, items.single().positionMs)
            }
        }

    @Test
    fun `createMarker und deleteMarker delegieren und laden die Marker neu`() =
        runTest(dispatcher) {
            libraryRepository.songById[7L] = songFixture(id = 7L, title = "Drop City")
            playbackRepository.stateFlow.value = PlaybackState(currentSongId = 7L)
            val vm = viewModel()
            vm.nowPlaying.test { awaitItemUntil { it.isVisible } }

            vm.createMarker("Mein Drop", 42_000L)
            dispatcher.scheduler.advanceUntilIdle()
            assertEquals(listOf(Triple(7L, "Mein Drop", 42_000L)), markerRepository.createCalls)

            vm.deleteMarker(1L)
            dispatcher.scheduler.advanceUntilIdle()
            assertEquals(listOf(1L), markerRepository.deleteCalls)
        }

    @Test
    fun `undo stellt entfernten Queue-Eintrag an alter Position wieder her`() =
        runTest(dispatcher) {
            libraryRepository.songById[7L] = songFixture(id = 7L, title = "Drop City")
            playbackRepository.stateFlow.value =
                PlaybackState(
                    currentSongId = 7L,
                    queue =
                        listOf(
                            QueueItem("m7", 7L, "Drop City", "Artist 7"),
                            QueueItem("m8", 8L, "Other", null),
                            QueueItem("m9", 9L, "Third", null),
                        ),
                    currentIndex = 0,
                )
            val vm = viewModel()
            vm.queue.test {
                awaitQueueUntil { it.items.size == 3 }
                assertFalse(vm.hasQueueUndo())
                vm.removeQueueItem(0)
                assertTrue(vm.hasQueueUndo())
                vm.undoRemoveQueueItem()
                dispatcher.scheduler.advanceUntilIdle()
                assertEquals(listOf(0), playbackRepository.removedIndices)
                assertEquals(listOf(7L), playbackRepository.addedToEnd.map { it.mediaStoreId })
                assertEquals(listOf(2 to 0), playbackRepository.movedPairs)
                assertFalse(vm.hasQueueUndo())
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `undo ohne Song-ID ist nicht verfuegbar`() =
        runTest(dispatcher) {
            playbackRepository.stateFlow.value =
                PlaybackState(
                    queue = listOf(QueueItem("cue-1", null, "CUE-Track", null)),
                    currentIndex = 0,
                )
            val vm = viewModel()
            vm.queue.test {
                awaitQueueUntil { it.items.size == 1 }
                vm.removeQueueItem(0)
                assertFalse(vm.hasQueueUndo())
                vm.undoRemoveQueueItem()
                dispatcher.scheduler.advanceUntilIdle()
                assertTrue(playbackRepository.addedToEnd.isEmpty())
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `undo stellt geloeschten Marker mit Label und Position wieder her`() =
        runTest(dispatcher) {
            markerRepository.markersBySong[7L] =
                listOf(
                    SongMarker(
                        id = 1L,
                        label = "Drop",
                        positionMs = 60_000L,
                        source = com.dropsync.core.model.MarkerSource.MANUAL,
                        isEnabled = true,
                        linkedSongId = 7L,
                    ),
                )
            playbackRepository.stateFlow.value = PlaybackState(currentSongId = 7L)
            val vm = viewModel()
            vm.nowPlayingMarkers.test {
                awaitMarkersUntil { it.size == 1 }
                assertFalse(vm.hasMarkerUndo())
                vm.deleteMarker(1L)
                assertTrue(vm.hasMarkerUndo())
                vm.undoDeleteMarker()
                dispatcher.scheduler.advanceUntilIdle()
                assertEquals(listOf(1L), markerRepository.deleteCalls)
                assertEquals(listOf(Triple(7L, "Drop", 60_000L)), markerRepository.createCalls)
                assertFalse(vm.hasMarkerUndo())
                cancelAndIgnoreRemainingEvents()
            }
        }

    private suspend fun app.cash.turbine.TurbineTestContext<NowPlayingUiState>.awaitItemUntil(
        predicate: (NowPlayingUiState) -> Boolean,
    ): NowPlayingUiState {
        while (true) {
            val item = awaitItem()
            if (predicate(item)) return item
        }
    }

    private suspend fun app.cash.turbine.TurbineTestContext<WaveformUiState>.awaitItemUntilWaveform(
        predicate: (WaveformUiState) -> Boolean,
    ): WaveformUiState {
        while (true) {
            val item = awaitItem()
            if (predicate(item)) return item
        }
    }

    private suspend fun app.cash.turbine.TurbineTestContext<QueueUiState>.awaitQueueUntil(
        predicate: (QueueUiState) -> Boolean,
    ): QueueUiState {
        while (true) {
            val item = awaitItem()
            if (predicate(item)) return item
        }
    }

    private suspend fun app.cash.turbine.TurbineTestContext<List<SongMarker>>.awaitMarkersUntil(
        predicate: (List<SongMarker>) -> Boolean,
    ): List<SongMarker> {
        while (true) {
            val item = awaitItem()
            if (predicate(item)) return item
        }
    }

    private fun songFixture(
        id: Long,
        title: String?,
    ): Song =
        Song(
            mediaStoreId = id,
            contentUri = "content://media/$id",
            displayName = "song$id.mp3",
            relativePath = "Music/",
            durationMs = 180_000L,
            sizeBytes = 1_000L,
            dateModifiedSeconds = 0L,
            title = title,
            artist = "Artist $id",
            album = null,
            isAvailable = true,
        )

    private fun markerFixture(
        id: Long,
        positionMs: Long,
        songId: Long,
    ): SongMarker =
        SongMarker(
            id = id,
            label = "Drop",
            positionMs = positionMs,
            source = com.dropsync.core.model.MarkerSource.MANUAL,
            isEnabled = true,
            linkedSongId = songId,
        )
}

private class FakePlaybackRepository : PlaybackRepository {
    val stateFlow = MutableStateFlow(PlaybackState())
    var snapshot: AppResult<PlaybackState> = AppResult.success(PlaybackState())
    val seekCalls = mutableListOf<Long>()
    var skipToPreviousCalls = 0

    override val state: Flow<PlaybackState> = stateFlow

    override suspend fun setQueue(
        songs: List<Song>,
        startIndex: Int,
        playWhenReady: Boolean,
    ): AppResult<Unit> = AppResult.success(Unit)

    override suspend fun play(): AppResult<Unit> = AppResult.success(Unit)

    override suspend fun pause(): AppResult<Unit> = AppResult.success(Unit)

    override suspend fun seekTo(positionMs: Long): AppResult<Unit> {
        seekCalls += positionMs
        return AppResult.success(Unit)
    }

    override suspend fun skipToNext(): AppResult<Unit> = AppResult.success(Unit)

    override suspend fun skipToPrevious(): AppResult<Unit> {
        skipToPreviousCalls++
        return AppResult.success(Unit)
    }

    override suspend fun skipToQueueIndex(index: Int): AppResult<Unit> = AppResult.success(Unit)

    val movedPairs = mutableListOf<Pair<Int, Int>>()
    val removedIndices = mutableListOf<Int>()
    val addedToEnd = mutableListOf<Song>()

    override suspend fun moveInQueue(
        fromIndex: Int,
        toIndex: Int,
    ): AppResult<Unit> {
        movedPairs += fromIndex to toIndex
        return AppResult.success(Unit)
    }

    override suspend fun removeFromQueue(index: Int): AppResult<Unit> {
        removedIndices += index
        return AppResult.success(Unit)
    }

    override suspend fun playNext(song: Song): AppResult<Unit> = AppResult.success(Unit)

    override suspend fun addToQueueEnd(song: Song): AppResult<Unit> {
        addedToEnd += song
        return AppResult.success(Unit)
    }

    override suspend fun setShuffle(enabled: Boolean): AppResult<Unit> = AppResult.success(Unit)

    override suspend fun setRepeatMode(mode: RepeatMode): AppResult<Unit> = AppResult.success(Unit)

    override suspend fun setPlaybackSpeed(speed: Float): AppResult<Unit> = AppResult.success(Unit)

    override suspend fun lastPersistedState(): PersistedPlayerState? = null

    override suspend fun snapshotNow(): AppResult<PlaybackState> = snapshot

    override suspend fun playSongAt(
        song: Song,
        startPositionMs: Long,
    ): AppResult<Unit> = AppResult.success(Unit)

    /** Scrubbing-Modus: im Fake nur mitgeschrieben. */
    var scrubbingMode: Boolean = false
        private set

    override suspend fun setScrubbingMode(enabled: Boolean): AppResult<Unit> {
        scrubbingMode = enabled
        return AppResult.success(Unit)
    }
}

private class FakeTrackAnalysisRepository : TrackAnalysisRepository {
    val analyses = MutableStateFlow<Map<Long, TrackAnalysis?>>(emptyMap())
    val requestedSongIds = mutableListOf<Long>()
    val onsetRequestedSongIds = mutableListOf<Long>()
    val prewarmedSongIds = mutableListOf<List<Long>>()

    override fun observeAnalysis(songId: Long): Flow<TrackAnalysis?> = analyses.map { it[songId] }

    override suspend fun requestAnalysis(song: Song) {
        requestedSongIds += song.mediaStoreId
    }

    override suspend fun requestAnalysisForNewSongs(songs: List<Song>) {
        requestedSongIds += songs.map { it.mediaStoreId }
    }

    override suspend fun requestAnalysisPrewarm(
        songs: List<Song>,
        limit: Int,
    ) {
        prewarmedSongIds += songs.take(limit).map { it.mediaStoreId }
    }

    override suspend fun requestOnsetDetection(song: Song) {
        onsetRequestedSongIds += song.mediaStoreId
    }
}

private class FakeMarkerRepository : MarkerRepository {
    val markersBySong = mutableMapOf<Long, List<SongMarker>>()
    val createCalls = mutableListOf<Triple<Long, String, Long>>()
    val deleteCalls = mutableListOf<Long>()

    override val unmatchedMarkers: Flow<List<SongMarker>> = emptyFlow()

    override suspend fun importDocument(
        schemaVersion: Int,
        tracks: List<com.dropsync.domain.library.ImportedTrack>,
    ) = AppResult.failure(AppError.Unknown("nicht Teil dieses Tests"))

    override suspend fun linkManually(
        markerId: Long,
        songId: Long,
    ): AppResult<Unit> = AppResult.success(Unit)

    override suspend fun getEnabledMarkersForSong(songId: Long): AppResult<List<SongMarker>> =
        AppResult.success(markersBySong[songId].orEmpty())

    override suspend fun createManualMarker(
        songId: Long,
        label: String,
        positionMs: Long,
    ): AppResult<SongMarker> {
        createCalls += Triple(songId, label, positionMs)
        return AppResult.success(
            SongMarker(
                id = 1L,
                label = label,
                positionMs = positionMs,
                source = com.dropsync.core.model.MarkerSource.MANUAL,
                isEnabled = true,
                linkedSongId = songId,
            ),
        )
    }

    override suspend fun deleteMarker(markerId: Long): AppResult<Unit> {
        deleteCalls += markerId
        return AppResult.success(Unit)
    }

    override val pendingAutoDetectedMarkers: Flow<List<SongMarker>> = emptyFlow()

    override suspend fun confirmMarker(markerId: Long): AppResult<Unit> = AppResult.success(Unit)

    override suspend fun moveMarker(
        markerId: Long,
        newPositionMs: Long,
    ): AppResult<Unit> = AppResult.success(Unit)
}

private class FakeLibraryRepository :
    LibraryRepository,
    LibraryBrowseRepository {
    val songById = mutableMapOf<Long, Song>()

    override val songs: Flow<List<Song>> = emptyFlow()

    override val availableSongs: Flow<List<Song>> = emptyFlow()

    override suspend fun refreshLibrary(force: Boolean): AppResult<LibraryScanResult> =
        AppResult.failure(AppError.Unknown("nicht Teil dieses Tests"))

    override suspend fun getSong(mediaStoreId: Long): AppResult<Song> =
        songById[mediaStoreId]?.let { AppResult.success(it) }
            ?: AppResult.failure(AppError.MediaUnavailable(mediaStoreId))

    override suspend fun markUnavailable(mediaStoreId: Long): AppResult<Unit> = AppResult.success(Unit)

    override suspend fun importCueSheet(
        songId: Long,
        cueText: String,
    ): AppResult<Int> = AppResult.failure(AppError.Unknown("nicht Teil dieses Tests"))

    override fun observeCueTracks(songId: Long): Flow<List<CueVirtualTrack>> = emptyFlow()

    override suspend fun scanFolder(treeUri: String): AppResult<FolderScanResult> =
        AppResult.failure(AppError.Unknown("nicht Teil dieses Tests"))

    override val scannedFiles: Flow<List<ScannedFile>> = emptyFlow()

    override val albums: Flow<List<Album>> = emptyFlow()
    override val artists: Flow<List<Artist>> = emptyFlow()
    override val genres: Flow<List<Genre>> = emptyFlow()
    override val folders: Flow<List<LibraryFolder>> = emptyFlow()
    override val playStats: Flow<List<SongPlayStat>> = emptyFlow()
    override val favorites: Flow<List<Song>> = emptyFlow()
    override val playlists: Flow<List<Playlist>> = emptyFlow()

    override fun songsByAlbum(album: String): Flow<List<Song>> = emptyFlow()

    override fun songsByArtist(artist: String): Flow<List<Song>> = emptyFlow()

    override fun songsByGenre(genre: String): Flow<List<Song>> = emptyFlow()

    override fun songsByFolder(relativePath: String): Flow<List<Song>> = emptyFlow()

    override fun playlistsByLabel(label: com.dropsync.core.model.PlaylistLabel): Flow<List<Playlist>> = emptyFlow()

    override fun songsOfPlaylist(playlistId: Long): Flow<List<Song>> = emptyFlow()

    override fun recentlyAdded(limit: Int): Flow<List<Song>> = emptyFlow()

    override fun recentlyPlayed(limit: Int): Flow<List<Song>> = emptyFlow()

    override fun mostPlayed(limit: Int): Flow<List<Song>> = emptyFlow()

    override fun isFavorite(songId: Long): Flow<Boolean> = kotlinx.coroutines.flow.flowOf(false)

    override suspend fun recordPlayback(songId: Long): AppResult<Unit> = AppResult.success(Unit)

    override suspend fun shuffleCandidates(songIds: List<Long>): AppResult<List<ShuffleCandidate>> =
        AppResult.success(emptyList())

    override suspend fun setFavorite(
        songId: Long,
        favorite: Boolean,
    ): AppResult<Unit> = AppResult.success(Unit)

    override suspend fun search(query: String): AppResult<List<Song>> = AppResult.success(emptyList())

    override suspend fun setPlaylistLabel(
        playlistId: Long,
        label: com.dropsync.core.model.PlaylistLabel?,
    ): AppResult<Unit> = AppResult.success(Unit)

    override suspend fun createPlaylist(name: String): AppResult<Long> = AppResult.success(0L)

    override suspend fun renamePlaylist(
        playlistId: Long,
        name: String,
    ): AppResult<Unit> = AppResult.success(Unit)

    override suspend fun deletePlaylist(playlistId: Long): AppResult<Unit> = AppResult.success(Unit)

    override suspend fun addToPlaylist(
        playlistId: Long,
        songIds: List<Long>,
    ): AppResult<Unit> = AppResult.success(Unit)

    override suspend fun removeFromPlaylist(
        playlistId: Long,
        position: Int,
    ): AppResult<Unit> = AppResult.success(Unit)

    override suspend fun moveInPlaylist(
        playlistId: Long,
        fromPosition: Int,
        toPosition: Int,
    ): AppResult<Unit> = AppResult.success(Unit)

    override suspend fun importM3uPlaylist(
        name: String,
        m3uText: String,
    ): AppResult<PlaylistImportResult> = AppResult.success(PlaylistImportResult(0L, 0, 0, 0))
}

/** Minimal-Fake des Audio-Engine-Zugangs für den Player (EQ-Schnellzugriff). */
private class FakeAudioEngineRepository : AudioEngineRepository {
    val config = MutableStateFlow(DspConfig())

    override val dspConfig: Flow<DspConfig> = config
    override val audioInfo: Flow<AudioInfo?> = MutableStateFlow(null)
    override val eqPresets: Flow<List<EqPreset>> = MutableStateFlow(emptyList())
    override val activeOutputProfileKey: Flow<String?> = MutableStateFlow(null)
    override val bitPerfectSupport: Flow<BitPerfectSupport> =
        MutableStateFlow(BitPerfectSupport.UNAVAILABLE)

    override suspend fun updateDspConfig(config: DspConfig) {
        this.config.value = config
    }

    override suspend fun saveEqPreset(
        name: String,
        bands: List<EqBand>,
    ): AppResult<Long> = AppResult.success(1L)

    override suspend fun deleteEqPreset(id: Long): AppResult<Unit> = AppResult.success(Unit)

    override suspend fun applyEqPreset(id: Long): AppResult<Unit> = AppResult.success(Unit)
}
