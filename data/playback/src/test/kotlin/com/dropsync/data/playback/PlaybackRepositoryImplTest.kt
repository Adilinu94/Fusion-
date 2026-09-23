package com.dropsync.data.playback

import androidx.media3.common.MediaItem
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dropsync.core.common.AppResult
import com.dropsync.core.model.Song
import com.dropsync.core.testing.FakeClock
import com.dropsync.core.testing.TestDispatcherProvider
import com.dropsync.data.playback.PlaybackRepositoryImpl.Companion.toPlaybackState
import com.dropsync.domain.playback.PersistedPlayerState
import com.dropsync.domain.playback.RepeatMode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * App-Zugang zur Wiedergabe (Bauplan 3.3, Schritt 5): Kommando-Delegation,
 * Zustandsabbildung, Listener-Reconnect (Befund 3.2) und entprellte
 * Persistenz — gegen einen SimpleBasePlayer-Fake statt ExoPlayer.
 *
 * Nicht abgedeckt (bewusst): die MediaController-Custom-Command-Pfade
 * (`playSongAt`/`armLanding`/`cancelLanding`/`setScrubbingMode` mit echtem
 * Controller) — sie brauchen den Service-/Session-Stack; der Fake ist kein
 * MediaController, getestet sind die dokumentierten Fallback-Zweige.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PlaybackRepositoryImplTest {
    private val player = FakePlayer()
    private val connection = FakePlayerConnection(player)
    private val store = FakePlayerStateStore()

    private fun TestScope.repository(): PlaybackRepositoryImpl =
        PlaybackRepositoryImpl(
            connection = connection,
            stateStore = store,
            dropLandingArmer = DropLandingArmer(FakeClock()),
            dispatchers = TestDispatcherProvider(StandardTestDispatcher(testScheduler)),
        )

    private fun song(id: Long) =
        Song(
            mediaStoreId = id,
            contentUri = "content://media/external/audio/media/$id",
            displayName = "Song$id.mp3",
            relativePath = "Music/Song$id.mp3",
            durationMs = 180_000,
            sizeBytes = 1_024,
            dateModifiedSeconds = 0,
            title = "Titel $id",
            artist = "Artist",
            album = "Album",
            isAvailable = true,
        )

    private val songs = listOf(song(1), song(2), song(3))

    // --- setQueue ---------------------------------------------------------

    @Test
    fun `setQueue lehnt leere Liste ab`() =
        runTest {
            val result = repository().setQueue(emptyList(), 0, playWhenReady = true)
            assertTrue(result is AppResult.Failure)
        }

    @Test
    fun `setQueue lehnt ungueltigen Startindex ab`() =
        runTest {
            val result = repository().setQueue(songs, startIndex = 7, playWhenReady = true)
            assertTrue(result is AppResult.Failure)
        }

    @Test
    fun `setQueue uebernimmt Queue, Index und Startzustand`() =
        runTest {
            val repository = repository()
            val result = repository.setQueue(songs, startIndex = 1, playWhenReady = true)
            assertTrue(result is AppResult.Success)

            assertEquals(listOf("1", "2", "3"), player.playlistMediaIds)
            assertEquals(1, player.currentMediaItemIndex)
            assertTrue(player.playWhenReady)

            val state = repository.state.first()
            assertTrue(state.isPlaying)
            assertEquals(2L, state.currentSongId)
            assertEquals(1, state.currentIndex)
            assertEquals(listOf(1L, 2L, 3L), state.queueSongIds)
            assertEquals(listOf("Titel 1", "Titel 2", "Titel 3"), state.queue.map { it.title })
        }

    // --- Einzelkommandos --------------------------------------------------

    @Test
    fun `play und pause schalten playWhenReady`() =
        runTest {
            val repository = repository()
            repository.setQueue(songs, 0, playWhenReady = false)
            assertFalse(repository.state.first().isPlaying)

            assertTrue(repository.play() is AppResult.Success)
            assertTrue(repository.state.first().isPlaying)

            assertTrue(repository.pause() is AppResult.Success)
            assertFalse(repository.state.first().isPlaying)
        }

    @Test
    fun `seekTo springt auf die Position des laufenden Titels`() =
        runTest {
            val repository = repository()
            repository.setQueue(songs, startIndex = 2, playWhenReady = true)

            assertTrue(repository.seekTo(12_345) is AppResult.Success)
            assertEquals(12_345L, player.currentPosition)
            assertEquals(2 to 12_345L, player.seekCalls.last())
        }

    @Test
    fun `skipToNext und skipToPrevious laufen durch die Queue`() =
        runTest {
            val repository = repository()
            repository.setQueue(songs, 0, playWhenReady = true)

            repository.skipToNext()
            assertEquals(1, player.currentMediaItemIndex)

            repository.skipToPrevious()
            assertEquals(0, player.currentMediaItemIndex)
        }

    @Test
    fun `skipToQueueIndex ignoriert ungueltige Indizes`() =
        runTest {
            val repository = repository()
            repository.setQueue(songs, 0, playWhenReady = true)

            repository.skipToQueueIndex(2)
            assertEquals(2, player.currentMediaItemIndex)

            repository.skipToQueueIndex(9)
            assertEquals(2, player.currentMediaItemIndex)
        }

    @Test
    fun `moveInQueue verschiebt und ignoriert ungueltige Indizes`() =
        runTest {
            val repository = repository()
            repository.setQueue(songs, 0, playWhenReady = true)

            repository.moveInQueue(0, 2)
            assertEquals(listOf("2", "3", "1"), player.playlistMediaIds)

            repository.moveInQueue(5, 0)
            assertEquals(listOf("2", "3", "1"), player.playlistMediaIds)
        }

    @Test
    fun `removeFromQueue entfernt und ignoriert ungueltige Indizes`() =
        runTest {
            val repository = repository()
            repository.setQueue(songs, 0, playWhenReady = true)

            repository.removeFromQueue(1)
            assertEquals(listOf("1", "3"), player.playlistMediaIds)

            repository.removeFromQueue(7)
            assertEquals(listOf("1", "3"), player.playlistMediaIds)
        }

    @Test
    fun `playNext reiht hinter dem laufenden Titel ein`() =
        runTest {
            val repository = repository()
            repository.setQueue(songs, startIndex = 1, playWhenReady = true)

            repository.playNext(song(9))
            assertEquals(listOf("1", "2", "9", "3"), player.playlistMediaIds)
        }

    @Test
    fun `playNext auf leerer Queue wird zum ersten Titel`() =
        runTest {
            val repository = repository()
            repository.playNext(song(9))
            assertEquals(listOf("9"), player.playlistMediaIds)
        }

    @Test
    fun `addToQueueEnd haengt ans Ende an`() =
        runTest {
            val repository = repository()
            repository.setQueue(songs, 0, playWhenReady = true)

            repository.addToQueueEnd(song(9))
            assertEquals(listOf("1", "2", "3", "9"), player.playlistMediaIds)
        }

    @Test
    fun `setShuffle und setRepeatMode bilden den Modus ab`() =
        runTest {
            val repository = repository()

            repository.setShuffle(true)
            assertTrue(player.shuffleModeEnabled)

            repository.setRepeatMode(RepeatMode.ALL)
            assertEquals(androidx.media3.common.Player.REPEAT_MODE_ALL, player.repeatMode)

            repository.setRepeatMode(RepeatMode.ONE)
            assertEquals(androidx.media3.common.Player.REPEAT_MODE_ONE, player.repeatMode)

            repository.setRepeatMode(RepeatMode.OFF)
            assertEquals(androidx.media3.common.Player.REPEAT_MODE_OFF, player.repeatMode)
        }

    @Test
    fun `setPlaybackSpeed begrenzt auf den sicheren Bereich`() =
        runTest {
            val repository = repository()

            repository.setPlaybackSpeed(0.1f)
            assertEquals(0.5f, player.playbackParameters.speed)

            repository.setPlaybackSpeed(3.0f)
            assertEquals(2.0f, player.playbackParameters.speed)

            repository.setPlaybackSpeed(1.25f)
            assertEquals(1.25f, player.playbackParameters.speed)
        }

    // --- Momentaufnahme und Zustand --------------------------------------

    @Test
    fun `snapshotNow liefert die aktuelle Position der Player-Instanz`() =
        runTest {
            val repository = repository()
            repository.setQueue(songs, 0, playWhenReady = true)
            player.setPositionSilently(4_200)

            val snapshot = repository.snapshotNow()
            assertTrue(snapshot is AppResult.Success)
            assertEquals(4_200L, (snapshot as AppResult.Success).value.positionMs)
            assertEquals(1L, snapshot.value.currentSongId)
        }

    @Test
    fun `playSongAt nutzt ohne MediaController den harten Wechsel`() =
        runTest {
            val repository = repository()
            repository.setQueue(songs, 0, playWhenReady = true)

            val result = repository.playSongAt(song(7), startPositionMs = 1_500)
            assertTrue(result is AppResult.Success)
            assertEquals(listOf("7"), player.playlistMediaIds)
            assertEquals(1_500L, player.currentPosition)
            assertTrue(player.playWhenReady)
            assertEquals(7L, repository.state.first().currentSongId)
        }

    @Test
    fun `armLanding und cancelLanding sind ohne Controller ein No-op`() =
        runTest {
            val repository = repository()
            repository.setQueue(songs, 0, playWhenReady = true)

            assertTrue(repository.armLanding(song(2), 0, 1_000, 100) is AppResult.Success)
            assertTrue(repository.cancelLanding() is AppResult.Success)
            assertTrue(repository.setScrubbingMode(true) is AppResult.Success)
            assertEquals(listOf("1", "2", "3"), player.playlistMediaIds)
        }

    // --- Listener-Reconnect (Befund 3.2) ----------------------------------

    @Test
    fun `Listener folgt einem neuen Controller und loest den alten`() =
        runTest {
            val repository = repository()
            repository.setQueue(songs, 0, playWhenReady = true)
            assertTrue(repository.state.first().isPlaying)

            val second = FakePlayer()
            connection.player = second
            repository.setQueue(songs, 0, playWhenReady = true)
            assertTrue(repository.state.first().isPlaying)

            player.pause()
            runCurrent()
            assertTrue(
                "Event des abgeloesten Players darf den Zustand nicht mehr aendern",
                repository.state.first().isPlaying,
            )
        }

    // --- Persistenz (5.5) -------------------------------------------------

    @Test
    fun `Persistenz schreibt entprellt und der letzte Zustand gewinnt`() =
        runTest {
            val repository = repository()
            repository.setQueue(songs, 0, playWhenReady = true)
            repeat(8) { index -> repository.seekTo(index * 1_000L) }
            advanceUntilIdle()

            assertTrue("Scrub-Serie darf nicht in viele Writes muenden", store.writes.size <= 2)
            val last = store.writes.last()
            assertEquals(7_000L, last.positionMs)
            assertEquals(listOf(1L, 2L, 3L), last.queueSongIds)
            assertEquals(1L, last.currentSongId)
        }

    @Test
    fun `lastPersistedState reicht den gespeicherten Zustand durch`() =
        runTest {
            val persisted = PersistedPlayerState(listOf(5L), 5L, 1_000L, false, RepeatMode.OFF)
            store.stored = persisted

            assertEquals(persisted, repository().lastPersistedState())
        }

    // --- Fehlerpfade ------------------------------------------------------

    @Test
    fun `fehlende Verbindung wird zu AppResult-Failure`() =
        runTest {
            connection.failure = IllegalStateException("Dienst weg")
            val repository = repository()

            assertTrue(repository.play() is AppResult.Failure)
            assertTrue(repository.snapshotNow() is AppResult.Failure)
            assertTrue(repository.playSongAt(song(1), 0) is AppResult.Failure)
        }

    // --- Abbildung Player -> Domainzustand --------------------------------

    @Test
    fun `toPlaybackState verwendet die bekannte Queue bei gleicher Timeline wieder`() {
        val fake = FakePlayer()
        fake.setMediaItems(songs.map(MediaItemFactory::fromSong), 0, 0L)

        val first = fake.toPlaybackState()
        val second = fake.toPlaybackState(first.queue)
        assertSame("Unveraenderte Timeline muss dieselbe Queue-Liste wiederverwenden", first.queue, second.queue)

        fake.removeMediaItem(0)
        val third = fake.toPlaybackState(second.queue)
        assertNotSame("Nach Timeline-Aenderung wird die Liste neu aufgebaut", second.queue, third.queue)
        assertEquals(listOf(2L, 3L), third.queueSongIds)
    }

    @Test
    fun `toPlaybackState bildet Metadaten und Fallback-Titel ab`() {
        val fake = FakePlayer()
        fake.setMediaItems(
            listOf(
                MediaItemFactory.fromSong(songs[0]),
                MediaItem.Builder().setMediaId("cue:99:1").build(),
            ),
            0,
            0L,
        )

        val state = fake.toPlaybackState()
        assertEquals(listOf("Titel 1", "cue:99:1"), state.queue.map { it.title })
        assertEquals(listOf(1L), state.queueSongIds)
        assertEquals(180_000L, state.durationMs)
        assertEquals(0, state.currentIndex)
    }
}
