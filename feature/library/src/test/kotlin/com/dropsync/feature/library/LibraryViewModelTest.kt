package com.dropsync.feature.library

import app.cash.turbine.test
import com.dropsync.core.model.MarkerSource
import com.dropsync.core.model.PlaylistLabel
import com.dropsync.core.model.SongMarker
import com.dropsync.core.testing.FakeLibraryBrowseRepository
import com.dropsync.core.testing.FakeMarkerRepository
import com.dropsync.domain.library.Playlist
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * C4 (U-2/U-3): Work-/Rest-Karten filtern nach Playlist-Label und nennen
 * die Abdeckung; die Review-Aktionen sind quittierbar und umkehrbar.
 */
class LibraryViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val browse = FakeLibraryBrowseRepository()
    private val markers = FakeMarkerRepository()
    private val playback = FakePlaybackRepository()
    private val analysis = FakeTrackAnalysisRepository()
    private val library = FakeLibraryRepository()

    @Before
    fun setUp() {
        // viewModelScope haengt am Main-Dispatcher; ohne setMain wirft jeder
        // launch im ViewModel "Module with the Main dispatcher had failed".
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel(): LibraryViewModel =
        LibraryViewModel(
            libraryRepository = library,
            browseRepository = browse,
            playbackRepository = playback,
            viewPreferences = FakeViewPreferences(),
            trackAnalysisRepository = analysis,
            folderFilter = FakeMusicFolderFilter(),
            markerRepository = markers,
        )

    @Test
    fun `nur gelabelte playlists werden zu dropsync karten`() =
        runTest(dispatcher) {
            browse.playlistsFlow.value =
                listOf(
                    Playlist(id = 1L, name = "Work", trackCount = 2, label = PlaylistLabel.WORK),
                    Playlist(id = 2L, name = "Normal", trackCount = 5, label = null),
                )
            browse.setSongsOfPlaylist(1L, listOf(testSong(10L), testSong(11L)))
            markers.songsWithMarkersFlow.value = setOf(10L)

            val vm = viewModel()
            vm.dropSyncCards.test {
                val cards = awaitItem()
                assertEquals(1, cards.size)
                assertEquals("Work", cards.first().name)
                assertEquals(PlaylistLabel.WORK, cards.first().label)
                assertEquals(2, cards.first().coverage.totalSongs)
                assertEquals(1, cards.first().coverage.songsWithDrop)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `abdeckung nennt offene kandidaten der playlist`() =
        runTest(dispatcher) {
            browse.playlistsFlow.value =
                listOf(Playlist(id = 1L, name = "Rest", trackCount = 2, label = PlaylistLabel.REST))
            browse.setSongsOfPlaylist(1L, listOf(testSong(10L), testSong(11L)))
            markers.pendingFlow.value =
                listOf(
                    marker(id = 100L, songId = 10L),
                    marker(id = 101L, songId = 11L),
                    marker(id = 102L, songId = 99L),
                )

            val vm = viewModel()
            vm.dropSyncCards.test {
                val card = awaitItem().single()
                assertEquals(2, card.coverage.pendingReviews)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `dropsync verwenden startet die playlist als queue`() =
        runTest(dispatcher) {
            val songs = listOf(testSong(10L), testSong(11L))
            browse.setSongsOfPlaylist(1L, songs)

            viewModel().useWithDropSync(1L)

            assertEquals(1, playback.setQueueCalls.size)
            assertEquals(songs, playback.setQueueCalls.first().first)
            assertEquals(0, playback.setQueueCalls.first().second)
        }

    @Test
    fun `anhoeren spielt den marker mit vorlauf`() =
        runTest(dispatcher) {
            val song = testSong(10L)
            library.songsFlow.value = listOf(song)

            viewModel().previewMarker(marker(id = 100L, songId = 10L, positionMs = 60_000L))

            assertEquals(1, playback.playSongAtCalls.size)
            assertEquals(song, playback.playSongAtCalls.first().first)
            assertEquals(60_000L - MARKER_PREVIEW_LEAD_MS, playback.playSongAtCalls.first().second)
        }

    @Test
    fun `anhoeren am anfang bleibt bei null`() =
        runTest(dispatcher) {
            library.songsFlow.value = listOf(testSong(10L))

            viewModel().previewMarker(marker(id = 100L, songId = 10L, positionMs = 1_000L))

            assertEquals(0L, playback.playSongAtCalls.first().second)
        }

    @Test
    fun `bestaetigen meldet quittung und laesst sich umkehren`() =
        runTest(dispatcher) {
            val candidate = marker(id = 100L, songId = 10L)
            markers.pendingFlow.value = listOf(candidate)
            val vm = viewModel()
            // WhileSubscribed: ohne Collector bleibt die Review-Liste leer.
            backgroundScope.launch { vm.pendingMarkerReviews.collect {} }
            runCurrent()

            vm.markerReviewFeedback.test {
                vm.confirmMarker(100L)
                assertEquals(MarkerReviewAction.CONFIRMED, awaitItem())

                assertTrue(vm.hasMarkerReviewUndo())
                vm.undoMarkerReview()
                assertEquals(listOf(100L to false), markers.enabledCalls)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `verwerfen meldet quittung und stellt den marker wieder her`() =
        runTest(dispatcher) {
            val candidate = marker(id = 100L, songId = 10L, positionMs = 42_000L)
            markers.pendingFlow.value = listOf(candidate)
            val vm = viewModel()
            backgroundScope.launch { vm.pendingMarkerReviews.collect {} }
            runCurrent()

            vm.markerReviewFeedback.test {
                vm.discardMarker(100L)
                assertEquals(MarkerReviewAction.DISCARDED, awaitItem())

                assertTrue(vm.hasMarkerReviewUndo())
                vm.undoMarkerReview()
                assertEquals(listOf(candidate), markers.restoredMarkers)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `erkennung stoesst nur markerlose titel an`() =
        runTest(dispatcher) {
            browse.setSongsOfPlaylist(1L, listOf(testSong(10L), testSong(11L)))
            markers.songsWithMarkersFlow.value = setOf(10L)

            viewModel().detectDropsForPlaylist(1L)

            assertEquals(listOf(11L), analysis.onsetRequests)
        }

    private fun marker(
        id: Long,
        songId: Long,
        positionMs: Long = 30_000L,
    ): SongMarker =
        SongMarker(
            id = id,
            label = "Drop",
            positionMs = positionMs,
            source = MarkerSource.AUTO_DETECTED,
            isEnabled = false,
            linkedSongId = songId,
        )
}
