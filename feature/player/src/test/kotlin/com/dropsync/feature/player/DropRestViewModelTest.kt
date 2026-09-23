package com.dropsync.feature.player

import com.dropsync.core.common.AppResult
import com.dropsync.core.model.MarkerSource
import com.dropsync.core.model.Song
import com.dropsync.core.model.SongMarker
import com.dropsync.core.testing.FakeClock
import com.dropsync.core.testing.FakeMarkerRepository
import com.dropsync.domain.playback.DropLandingEvent
import com.dropsync.domain.playback.PersistedPlayerState
import com.dropsync.domain.playback.PlaybackRepository
import com.dropsync.domain.playback.PlaybackState
import com.dropsync.domain.playback.RepeatMode
import com.dropsync.domain.timer.DropRestEligibility
import com.dropsync.domain.timer.NoOpCueOutput
import com.dropsync.domain.timer.RestTimerServiceStarter
import com.dropsync.domain.timer.TimerEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * D5/A8: Das Drop-Rest-Gate liest die Marker des laufenden Titels aus einem
 * Flow (ein Abo je Songwechsel, Room invalidiert bei Marker-Aenderungen)
 * und tastet im 500-ms-Takt nur noch die Wiedergabezeit ab — vorher lief je
 * Takt eine Marker-Query.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DropRestViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val playback = GatePlaybackRepository()
    private val markers = FakeMarkerRepository()
    private val clock = FakeClock()
    private val engine = TimerEngine(clock, NoOpCueOutput()) { "session-1" }

    @Before
    fun setUpMainDispatcher() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDownMainDispatcher() {
        Dispatchers.resetMain()
    }

    private fun viewModel() =
        DropRestViewModel(
            playbackRepository = playback,
            markerRepository = markers,
            timerEngine = engine,
            restTimerServiceStarter = RestTimerServiceStarter { },
        )

    @Test
    fun `Gate-Polling fragt marker nicht im 500-ms-takt ab`() =
        runTest(dispatcher) {
            playback.stateFlow.value = PlaybackState(isPlaying = true, currentSongId = 7L)
            playback.snapshotSongId = 7L
            playback.snapshotPositionMs = 10_000L
            markers.enabledMarkersBySong[7L] =
                listOf(marker(id = 1L, positionMs = 30_000L, songId = 7L))
            val vm = viewModel()

            val job = backgroundScope.launch { vm.eligibility.collect {} }
            advanceTimeBy(2_600L)
            job.cancel()

            // EIN Marker-Abo fuer den Titel, aber viele Positions-Abtastungen.
            assertEquals(listOf(7L), markers.observedMarkerSongs)
            assertTrue(
                "snapshotNow muss im Takt laufen, war ${playback.snapshotCalls}",
                playback.snapshotCalls >= 5,
            )
            assertTrue(vm.eligibility.value is DropRestEligibility.Eligible)
        }

    @Test
    fun `Songwechsel laedt die Marker genau einmal neu`() =
        runTest(dispatcher) {
            playback.stateFlow.value = PlaybackState(isPlaying = true, currentSongId = 7L)
            playback.snapshotSongId = 7L
            markers.enabledMarkersBySong[7L] =
                listOf(marker(id = 1L, positionMs = 30_000L, songId = 7L))
            markers.enabledMarkersBySong[8L] =
                listOf(marker(id = 2L, positionMs = 40_000L, songId = 8L))
            val vm = viewModel()

            val job = backgroundScope.launch { vm.eligibility.collect {} }
            advanceTimeBy(600L)
            // Songwechsel: derselbe Takt laeuft weiter, das Gate zieht nach.
            playback.snapshotSongId = 8L
            playback.stateFlow.value = playback.stateFlow.value.copy(currentSongId = 8L)
            advanceTimeBy(600L)
            job.cancel()

            assertEquals(listOf(7L, 8L), markers.observedMarkerSongs)
        }

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

/** Minimaler Wiedergabe-Fake: Zustand und Momentaufnahme zaehlbar. */
private class GatePlaybackRepository : PlaybackRepository {
    val stateFlow = MutableStateFlow(PlaybackState())
    var snapshotSongId: Long? = null
    var snapshotPositionMs = 0L
    var snapshotCalls = 0
        private set

    override val state: Flow<PlaybackState> = stateFlow

    override val landingEvents: Flow<DropLandingEvent> = emptyFlow()

    override suspend fun snapshotNow(): AppResult<PlaybackState> {
        snapshotCalls++
        return AppResult.success(
            PlaybackState(
                isPlaying = true,
                currentSongId = snapshotSongId,
                positionMs = snapshotPositionMs,
            ),
        )
    }

    override suspend fun setQueue(
        songs: List<Song>,
        startIndex: Int,
        playWhenReady: Boolean,
    ): AppResult<Unit> = unsupported()

    override suspend fun play(): AppResult<Unit> = unsupported()

    override suspend fun pause(): AppResult<Unit> = unsupported()

    override suspend fun seekTo(positionMs: Long): AppResult<Unit> = unsupported()

    override suspend fun skipToNext(): AppResult<Unit> = unsupported()

    override suspend fun skipToPrevious(): AppResult<Unit> = unsupported()

    override suspend fun skipToQueueIndex(index: Int): AppResult<Unit> = unsupported()

    override suspend fun moveInQueue(
        fromIndex: Int,
        toIndex: Int,
    ): AppResult<Unit> = unsupported()

    override suspend fun removeFromQueue(index: Int): AppResult<Unit> = unsupported()

    override suspend fun playNext(song: Song): AppResult<Unit> = unsupported()

    override suspend fun addToQueueEnd(song: Song): AppResult<Unit> = unsupported()

    override suspend fun setShuffle(enabled: Boolean): AppResult<Unit> = unsupported()

    override suspend fun setRepeatMode(mode: RepeatMode): AppResult<Unit> = unsupported()

    override suspend fun setPlaybackSpeed(speed: Float): AppResult<Unit> = unsupported()

    override suspend fun lastPersistedState(): PersistedPlayerState? = unsupported()

    override suspend fun playSongAt(
        song: Song,
        startPositionMs: Long,
    ): AppResult<Unit> = unsupported()

    override suspend fun armLanding(
        song: Song,
        startPositionMs: Long,
        delayMs: Long,
        fadeMs: Long,
    ): AppResult<Unit> = unsupported()

    override suspend fun cancelLanding(): AppResult<Unit> = unsupported()

    override suspend fun setScrubbingMode(enabled: Boolean): AppResult<Unit> = unsupported()

    private fun unsupported(): Nothing = error("in diesem Test nicht benutzt")
}
