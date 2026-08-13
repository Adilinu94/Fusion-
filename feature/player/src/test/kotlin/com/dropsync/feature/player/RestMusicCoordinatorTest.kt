package com.dropsync.feature.player

import com.dropsync.core.common.AppError
import com.dropsync.core.common.AppResult
import com.dropsync.core.model.MarkerSource
import com.dropsync.core.model.PlaylistLabel
import com.dropsync.core.model.RestMusicBehavior
import com.dropsync.core.model.Song
import com.dropsync.core.model.SongMarker
import com.dropsync.core.testing.FakeClock
import com.dropsync.core.testing.TestDispatcherProvider
import com.dropsync.domain.audio.AudioEngineRepository
import com.dropsync.domain.audio.AudioInfo
import com.dropsync.domain.audio.BitPerfectSupport
import com.dropsync.domain.audio.DspConfig
import com.dropsync.domain.audio.EqBand
import com.dropsync.domain.audio.EqPreset
import com.dropsync.domain.library.Album
import com.dropsync.domain.library.Artist
import com.dropsync.domain.library.Genre
import com.dropsync.domain.library.ImportReport
import com.dropsync.domain.library.ImportedTrack
import com.dropsync.domain.library.LibraryBrowseRepository
import com.dropsync.domain.library.LibraryFolder
import com.dropsync.domain.library.MarkerRepository
import com.dropsync.domain.library.Playlist
import com.dropsync.domain.library.PlaylistImportResult
import com.dropsync.domain.library.ShuffleCandidate
import com.dropsync.domain.playback.AudioRouteProfile
import com.dropsync.domain.playback.PersistedPlayerState
import com.dropsync.domain.playback.PlaybackRepository
import com.dropsync.domain.playback.PlaybackState
import com.dropsync.domain.playback.RepeatMode
import com.dropsync.domain.playback.RestDuckingGate
import com.dropsync.domain.playback.RestMusicSettingsRepository
import com.dropsync.domain.playback.RouteProfileRepository
import com.dropsync.domain.timer.NoOpCueOutput
import com.dropsync.domain.timer.TimerEngine
import com.dropsync.domain.timer.TimerMode
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifikation Musik-Workout-Plan Phase 4: der Coordinator setzt bei
 * Pausenbeginn die Rest-Queue, terminiert die Drop-Landung und greift bei
 * [RestMusicBehavior.NORMAL] nie ein. Timer ist die echte [TimerEngine]
 * (deterministisch ueber [FakeClock]); die Repositories sind Fakes.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RestMusicCoordinatorTest {
    private val dispatcher = StandardTestDispatcher()
    private val clock = FakeClock()
    private val engine = TimerEngine(clock, NoOpCueOutput()) { "session-1" }
    private val settings = CoordinatorRestMusicSettings()
    private val playback = CoordinatorPlaybackRepository()
    private val browse = CoordinatorBrowseRepository()
    private val markers = CoordinatorMarkerRepository()
    private val restDucking = CoordinatorRestDucking()

    private fun coordinator() =
        RestMusicCoordinator(
            timerEngine = engine,
            restMusicSettings = settings,
            playbackRepository = playback,
            browseRepository = browse,
            markerRepository = markers,
            audioEngine = CoordinatorAudioEngine(),
            routeProfiles = CoordinatorRouteProfiles(),
            restDucking = restDucking,
            clock = clock,
            dispatchers = TestDispatcherProvider(dispatcher),
        )

    @Test
    fun `Pausenbeginn setzt die Rest-Queue`() =
        runTest(dispatcher) {
            browse.playlistsByLabelMap[PlaylistLabel.REST] = listOf(playlist(1L))
            browse.songsByPlaylist[1L] = listOf(song(10L), song(11L))
            settings.behaviorState.value = RestMusicBehavior.REST_PLAYLIST

            coordinator().start()
            engine.start(TimerMode.REST, durationMs = 30_000L)
            dispatcher.scheduler.advanceUntilIdle()

            assertEquals(listOf(listOf(10L, 11L) to true), playback.setQueueCalls)
            assertTrue(playback.crossfadeCalls.isEmpty())
        }

    @Test
    fun `Drop-Landung wird terminiert und per Crossfade ausgefuehrt`() =
        runTest(dispatcher) {
            browse.playlistsByLabelMap[PlaylistLabel.REST] = listOf(playlist(1L))
            browse.songsByPlaylist[1L] = listOf(song(10L))
            browse.playlistsByLabelMap[PlaylistLabel.WORK] = listOf(playlist(2L))
            browse.songsByPlaylist[2L] = listOf(song(20L, durationMs = 200_000L))
            // Drop bei 20 s; Restzeit 30 s -> erst Pausenmusik, dann Song von
            // vorn nach 10 s, sodass der Drop das Pausenende trifft.
            markers.markersBySong[20L] = listOf(marker(id = 5L, positionMs = 20_000L, songId = 20L))
            settings.behaviorState.value = RestMusicBehavior.DROP_LANDING

            coordinator().start()
            engine.start(TimerMode.REST, durationMs = 30_000L)
            dispatcher.scheduler.runCurrent()

            assertEquals(listOf(listOf(10L) to true), playback.setQueueCalls)
            assertTrue(playback.crossfadeCalls.isEmpty())

            // Verzoegerung R - D = 10 s abwarten: dann startet der Work-Titel.
            dispatcher.scheduler.advanceTimeBy(10_001L)
            dispatcher.scheduler.runCurrent()

            assertEquals(listOf(20L to 0L), playback.crossfadeCalls)
        }

    @Test
    fun `manuelle Pause bricht die Drop-Landung ab`() =
        runTest(dispatcher) {
            browse.playlistsByLabelMap[PlaylistLabel.REST] = listOf(playlist(1L))
            browse.songsByPlaylist[1L] = listOf(song(10L))
            browse.playlistsByLabelMap[PlaylistLabel.WORK] = listOf(playlist(2L))
            browse.songsByPlaylist[2L] = listOf(song(20L, durationMs = 200_000L))
            markers.markersBySong[20L] = listOf(marker(id = 5L, positionMs = 20_000L, songId = 20L))
            settings.behaviorState.value = RestMusicBehavior.DROP_LANDING

            coordinator().start()
            engine.start(TimerMode.REST, durationMs = 30_000L)
            dispatcher.scheduler.runCurrent()

            // Nutzer pausiert (Medientaste) waehrend der Pause: Landung faellt aus.
            playback.playing = false
            dispatcher.scheduler.advanceTimeBy(10_001L)
            dispatcher.scheduler.runCurrent()

            assertTrue(playback.crossfadeCalls.isEmpty())
        }

    @Test
    fun `NORMAL greift nicht in die Wiedergabe ein`() =
        runTest(dispatcher) {
            browse.playlistsByLabelMap[PlaylistLabel.REST] = listOf(playlist(1L))
            browse.songsByPlaylist[1L] = listOf(song(10L))
            settings.behaviorState.value = RestMusicBehavior.NORMAL

            coordinator().start()
            engine.start(TimerMode.REST, durationMs = 30_000L)
            dispatcher.scheduler.advanceUntilIdle()

            assertTrue(playback.setQueueCalls.isEmpty())
            assertTrue(playback.crossfadeCalls.isEmpty())
        }

    @Test
    fun `Pausenende startet einen Work-Titel ohne Landung`() =
        runTest(dispatcher) {
            browse.playlistsByLabelMap[PlaylistLabel.REST] = listOf(playlist(1L))
            browse.songsByPlaylist[1L] = listOf(song(10L))
            browse.playlistsByLabelMap[PlaylistLabel.WORK] = listOf(playlist(2L))
            browse.songsByPlaylist[2L] = listOf(song(20L))
            settings.behaviorState.value = RestMusicBehavior.REST_PLAYLIST

            coordinator().start()
            engine.start(TimerMode.REST, durationMs = 30_000L)
            dispatcher.scheduler.advanceUntilIdle()

            // Pause laeuft ab: monotone Uhr vorstellen und Timer neu bewerten.
            clock.advanceBy(30_000L)
            engine.evaluate()
            dispatcher.scheduler.advanceUntilIdle()

            assertEquals(
                listOf(listOf(10L) to true, listOf(20L) to true),
                playback.setQueueCalls,
            )
        }

    @Test
    fun `Rest-Ducking wird bei Pausenbeginn aktiviert und am Ende zurueckgenommen`() =
        runTest(dispatcher) {
            browse.playlistsByLabelMap[PlaylistLabel.REST] = listOf(playlist(1L))
            browse.songsByPlaylist[1L] = listOf(song(10L))
            settings.behaviorState.value = RestMusicBehavior.REST_PLAYLIST

            coordinator().start()
            engine.start(TimerMode.REST, durationMs = 30_000L)
            dispatcher.scheduler.advanceUntilIdle()
            assertTrue(
                "Rest-Ducking muss bei Pausenbeginn aktiv sein",
                restDucking.activations.lastOrNull() == true,
            )

            clock.advanceBy(30_000L)
            engine.evaluate()
            dispatcher.scheduler.advanceUntilIdle()
            assertTrue(
                "Rest-Ducking muss am Pausenende zurueckgenommen sein",
                restDucking.activations.lastOrNull() == false,
            )
        }

    @Test
    fun `Latenz aus dem Route-Profil verschiebt die Landung`() =
        runTest(dispatcher) {
            // Phase 6: WorkStart = Go - Marker - Latenz. R = 30 s,
            // D = 12 s, L = 200 ms -> Start nach 17.8 s statt 18 s.
            browse.playlistsByLabelMap[PlaylistLabel.REST] = listOf(playlist(1L))
            browse.songsByPlaylist[1L] = listOf(song(10L))
            browse.playlistsByLabelMap[PlaylistLabel.WORK] = listOf(playlist(2L))
            browse.songsByPlaylist[2L] = listOf(song(20L, durationMs = 200_000L))
            markers.markersBySong[20L] = listOf(marker(id = 5L, positionMs = 12_000L, songId = 20L))
            settings.behaviorState.value = RestMusicBehavior.DROP_LANDING
            val routeProfiles = CoordinatorRouteProfiles().apply { latencyMs = 200L }

            RestMusicCoordinator(
                timerEngine = engine,
                restMusicSettings = settings,
                playbackRepository = playback,
                browseRepository = browse,
                markerRepository = markers,
                audioEngine = CoordinatorAudioEngine(),
                routeProfiles = routeProfiles,
                restDucking = restDucking,
                clock = clock,
                dispatchers = TestDispatcherProvider(dispatcher),
            ).start()
            engine.start(TimerMode.REST, durationMs = 30_000L)
            dispatcher.scheduler.runCurrent()

            // Vor 17.8 s noch keine Landung.
            dispatcher.scheduler.advanceTimeBy(17_000L)
            dispatcher.scheduler.runCurrent()
            assertTrue(playback.crossfadeCalls.isEmpty())

            // Nach 17.8 s Landung von vorn (INTRO).
            dispatcher.scheduler.advanceTimeBy(1_000L)
            dispatcher.scheduler.runCurrent()
            assertEquals(listOf(20L to 0L), playback.crossfadeCalls)
        }

    @Test
    fun `DIRECT_TO_DROP springt beim Go direkt zur Drop-Position`() =
        runTest(dispatcher) {
            // Drop (60 s) liegt hinter der Restzeit (20 s): Rest-Musik
            // laeuft die volle Restzeit, beim Go springt der Player direkt
            // auf den Drop (startAtPositionMs = 60 s).
            browse.playlistsByLabelMap[PlaylistLabel.REST] = listOf(playlist(1L))
            browse.songsByPlaylist[1L] = listOf(song(10L))
            browse.playlistsByLabelMap[PlaylistLabel.WORK] = listOf(playlist(2L))
            browse.songsByPlaylist[2L] = listOf(song(20L, durationMs = 200_000L))
            markers.markersBySong[20L] = listOf(marker(id = 5L, positionMs = 60_000L, songId = 20L))
            settings.behaviorState.value = RestMusicBehavior.DROP_LANDING

            coordinator().start()
            engine.start(TimerMode.REST, durationMs = 20_000L)
            dispatcher.scheduler.runCurrent()

            dispatcher.scheduler.advanceTimeBy(20_001L)
            dispatcher.scheduler.runCurrent()

            assertEquals(listOf(20L to 60_000L), playback.crossfadeCalls)
        }

    private fun playlist(id: Long) = Playlist(id = id, name = "P$id", trackCount = 1)

    private fun song(
        id: Long,
        durationMs: Long = 180_000L,
    ) = Song(
        mediaStoreId = id,
        contentUri = "content://media/$id",
        displayName = "song$id.mp3",
        relativePath = "Music/",
        durationMs = durationMs,
        sizeBytes = 1_000L,
        dateModifiedSeconds = 0L,
        title = "Song $id",
        artist = "Artist $id",
        album = null,
        isAvailable = true,
    )

    private fun marker(
        id: Long,
        positionMs: Long,
        songId: Long,
    ) = SongMarker(
        id = id,
        label = "Drop",
        positionMs = positionMs,
        source = MarkerSource.MANUAL,
        isEnabled = true,
        linkedSongId = songId,
    )
}

private class CoordinatorRestMusicSettings : RestMusicSettingsRepository {
    val behaviorState = MutableStateFlow(RestMusicBehavior.NORMAL)

    override val behavior: Flow<RestMusicBehavior> = behaviorState

    override suspend fun setBehavior(behavior: RestMusicBehavior) {
        behaviorState.value = behavior
    }
}

private class CoordinatorPlaybackRepository : PlaybackRepository {
    val setQueueCalls = mutableListOf<Pair<List<Long>, Boolean>>()
    val crossfadeCalls = mutableListOf<Pair<Long, Long>>()
    var playing = true

    override val state: Flow<PlaybackState> = MutableStateFlow(PlaybackState())

    override suspend fun setQueue(
        songs: List<Song>,
        startIndex: Int,
        playWhenReady: Boolean,
    ): AppResult<Unit> {
        setQueueCalls += songs.map { it.mediaStoreId } to playWhenReady
        return AppResult.success(Unit)
    }

    override suspend fun crossfadeTo(
        song: Song,
        startPositionMs: Long,
    ): AppResult<Unit> {
        crossfadeCalls += song.mediaStoreId to startPositionMs
        return AppResult.success(Unit)
    }

    override suspend fun snapshotNow(): AppResult<PlaybackState> = AppResult.success(PlaybackState(isPlaying = playing))

    override suspend fun play(): AppResult<Unit> = AppResult.success(Unit)

    override suspend fun pause(): AppResult<Unit> = AppResult.success(Unit)

    override suspend fun seekTo(positionMs: Long): AppResult<Unit> = AppResult.success(Unit)

    override suspend fun skipToNext(): AppResult<Unit> = AppResult.success(Unit)

    override suspend fun skipToPrevious(): AppResult<Unit> = AppResult.success(Unit)

    override suspend fun skipToQueueIndex(index: Int): AppResult<Unit> = AppResult.success(Unit)

    override suspend fun moveInQueue(
        fromIndex: Int,
        toIndex: Int,
    ): AppResult<Unit> = AppResult.success(Unit)

    override suspend fun removeFromQueue(index: Int): AppResult<Unit> = AppResult.success(Unit)

    override suspend fun playNext(song: Song): AppResult<Unit> = AppResult.success(Unit)

    override suspend fun addToQueueEnd(song: Song): AppResult<Unit> = AppResult.success(Unit)

    override suspend fun setShuffle(enabled: Boolean): AppResult<Unit> = AppResult.success(Unit)

    override suspend fun setRepeatMode(mode: RepeatMode): AppResult<Unit> = AppResult.success(Unit)

    override suspend fun lastPersistedState(): PersistedPlayerState? = null
}

private class CoordinatorBrowseRepository : LibraryBrowseRepository {
    val playlistsByLabelMap = mutableMapOf<PlaylistLabel, List<Playlist>>()
    val songsByPlaylist = mutableMapOf<Long, List<Song>>()

    override fun playlistsByLabel(label: PlaylistLabel): Flow<List<Playlist>> =
        flowOf(playlistsByLabelMap[label].orEmpty())

    override fun songsOfPlaylist(playlistId: Long): Flow<List<Song>> = flowOf(songsByPlaylist[playlistId].orEmpty())

    override val albums: Flow<List<Album>> = emptyFlow()
    override val artists: Flow<List<Artist>> = emptyFlow()
    override val genres: Flow<List<Genre>> = emptyFlow()
    override val folders: Flow<List<LibraryFolder>> = emptyFlow()
    override val playStats: Flow<List<com.dropsync.domain.library.SongPlayStat>> = emptyFlow()
    override val favorites: Flow<List<Song>> = emptyFlow()
    override val playlists: Flow<List<Playlist>> = emptyFlow()

    override fun songsByAlbum(album: String): Flow<List<Song>> = emptyFlow()

    override fun songsByArtist(artist: String): Flow<List<Song>> = emptyFlow()

    override fun songsByGenre(genre: String): Flow<List<Song>> = emptyFlow()

    override fun songsByFolder(relativePath: String): Flow<List<Song>> = emptyFlow()

    override fun recentlyAdded(limit: Int): Flow<List<Song>> = emptyFlow()

    override fun recentlyPlayed(limit: Int): Flow<List<Song>> = emptyFlow()

    override fun mostPlayed(limit: Int): Flow<List<Song>> = emptyFlow()

    override fun isFavorite(songId: Long): Flow<Boolean> = flowOf(false)

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
        label: PlaylistLabel?,
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
    ): AppResult<PlaylistImportResult> = AppResult.failure(AppError.Unknown("nicht Teil dieses Tests"))
}

private class CoordinatorMarkerRepository : MarkerRepository {
    val markersBySong = mutableMapOf<Long, List<SongMarker>>()

    override val unmatchedMarkers: Flow<List<SongMarker>> = emptyFlow()
    override val pendingAutoDetectedMarkers: Flow<List<SongMarker>> = emptyFlow()

    override suspend fun importDocument(
        schemaVersion: Int,
        tracks: List<ImportedTrack>,
    ): AppResult<ImportReport> = AppResult.failure(AppError.Unknown("nicht Teil dieses Tests"))

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
    ): AppResult<SongMarker> = AppResult.failure(AppError.Unknown("nicht Teil dieses Tests"))

    override suspend fun deleteMarker(markerId: Long): AppResult<Unit> = AppResult.success(Unit)

    override suspend fun confirmMarker(markerId: Long): AppResult<Unit> = AppResult.success(Unit)

    override suspend fun moveMarker(
        markerId: Long,
        newPositionMs: Long,
    ): AppResult<Unit> = AppResult.success(Unit)
}

private class CoordinatorAudioEngine : AudioEngineRepository {
    var config = DspConfig()

    override val dspConfig: Flow<DspConfig> = MutableStateFlow(config)
    override val audioInfo: Flow<AudioInfo?> = emptyFlow()
    override val eqPresets: Flow<List<EqPreset>> = emptyFlow()
    override val activeOutputProfileKey: Flow<String?> = emptyFlow()
    override val bitPerfectSupport: Flow<BitPerfectSupport> = flowOf(BitPerfectSupport.UNAVAILABLE)

    override suspend fun updateDspConfig(config: DspConfig) {
        this.config = config
    }

    override suspend fun saveEqPreset(
        name: String,
        bands: List<EqBand>,
    ): AppResult<Long> = AppResult.failure(AppError.Unknown("nicht Teil dieses Tests"))

    override suspend fun deleteEqPreset(id: Long): AppResult<Unit> = AppResult.success(Unit)

    override suspend fun applyEqPreset(id: Long): AppResult<Unit> = AppResult.success(Unit)
}

private class CoordinatorRouteProfiles : RouteProfileRepository {
    var latencyMs: Long? = null

    override val currentProfile: Flow<AudioRouteProfile?> = emptyFlow()

    override suspend fun currentLatencyMs(): Long? = latencyMs

    override suspend fun markStale() = Unit

    override suspend fun upsert(profile: AudioRouteProfile) = Unit
}

private class CoordinatorRestDucking : RestDuckingGate {
    val activations = mutableListOf<Boolean>()

    override suspend fun setActive(active: Boolean) {
        activations += active
    }
}
