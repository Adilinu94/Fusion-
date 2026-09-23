package com.dropsync.data.playback

import com.dropsync.core.model.Song
import com.dropsync.core.testing.FakeClock
import com.dropsync.domain.playback.DropLandingEvent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Armierte Drop-Landung (MP-3) ueber den schmalen [LandingPlayer]-Port:
 * Zielposition, Landung mit Delta, Watchdog (OVERRIDDEN/WATCHDOG),
 * Abbruch, Ersetzen, PLAYER_ERROR, detach und die Fade-Rampe vor dem
 * Wechsel — ohne Media3-Stack.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DropLandingArmerTest {
    private val clock = FakeClock()

    @Test
    fun `arm ohne Player ist false`() =
        runTest {
            val armer = DropLandingArmer(clock)
            assertFalse(armer.arm(song(), 0L, 1_000L, 0L))
        }

    @Test
    fun `Landung feuert auf der Zielposition und meldet Landed mit Delta`() =
        runTest {
            val player = FakeLandingPlayer()
            val armer = DropLandingArmer(clock)
            val events = collectEvents(armer)
            armer.attach(player, backgroundScope)
            player.currentPositionMs = 10_000L

            assertTrue(armer.arm(song(7L), 2_000L, delayMs = 5_000L, fadeMs = 0L))
            val message = player.messages.single()
            assertEquals(0, message.mediaItemIndex)
            assertEquals(15_000L, message.positionMs)

            clock.advanceBy(5_000L)
            advanceTimeBy(5_000L)
            message.onFire()

            assertEquals(DropLandingEvent.Landed(7L, 0L), events.single())
            assertEquals(listOf(song(7L) to 2_000L), player.startedSongs)
            advanceTimeBy(50L)
            runCurrent()
            assertEquals(1f, player.volume, 0f)
        }

    @Test
    fun `Watchdog meldet OVERRIDDEN wenn pausiert`() =
        runTest {
            val player = FakeLandingPlayer()
            val armer = DropLandingArmer(clock)
            val events = collectEvents(armer)
            armer.attach(player, backgroundScope)
            player.isPlaying = false

            assertTrue(armer.arm(song(), 0L, delayMs = 5_000L, fadeMs = 0L))
            clock.advanceBy(5_250L)
            advanceTimeBy(5_250L)
            runCurrent()

            assertEquals(DropLandingEvent.Missed(DropLandingEvent.Reason.OVERRIDDEN), events.single())
            assertTrue(player.startedSongs.isEmpty())
            assertTrue(player.messages.single().cancelled)
            assertEquals(1f, player.volume, 0f)
        }

    @Test
    fun `Watchdog meldet WATCHDOG wenn weiter spielend`() =
        runTest {
            val player = FakeLandingPlayer()
            val armer = DropLandingArmer(clock)
            val events = collectEvents(armer)
            armer.attach(player, backgroundScope)
            player.isPlaying = true

            assertTrue(armer.arm(song(), 0L, delayMs = 5_000L, fadeMs = 0L))
            clock.advanceBy(5_250L)
            advanceTimeBy(5_250L)
            runCurrent()

            assertEquals(DropLandingEvent.Missed(DropLandingEvent.Reason.WATCHDOG), events.single())
            assertTrue(player.startedSongs.isEmpty())
        }

    @Test
    fun `cancel verhindert die Landung`() =
        runTest {
            val player = FakeLandingPlayer()
            val armer = DropLandingArmer(clock)
            val events = collectEvents(armer)
            armer.attach(player, backgroundScope)

            assertTrue(armer.arm(song(), 0L, delayMs = 1_000L, fadeMs = 0L))
            armer.cancel()
            assertTrue(player.messages.single().cancelled)

            clock.advanceBy(2_000L)
            advanceTimeBy(2_000L)
            runCurrent()

            assertTrue(events.isEmpty())
            assertTrue(player.startedSongs.isEmpty())
        }

    @Test
    fun `neue Armierung ersetzt die alte`() =
        runTest {
            val player = FakeLandingPlayer()
            val armer = DropLandingArmer(clock)
            val events = collectEvents(armer)
            armer.attach(player, backgroundScope)

            assertTrue(armer.arm(song(1L), 0L, delayMs = 1_000L, fadeMs = 0L))
            assertTrue(armer.arm(song(2L), 0L, delayMs = 2_000L, fadeMs = 0L))

            val (first, second) = player.messages
            assertTrue(first.cancelled)
            assertFalse(second.cancelled)

            clock.advanceBy(2_000L)
            advanceTimeBy(2_000L)
            second.onFire()

            assertEquals(DropLandingEvent.Landed(2L, 0L), events.single())
            assertEquals(listOf(song(2L) to 0L), player.startedSongs)
        }

    @Test
    fun `Sendefehler meldet PLAYER_ERROR`() =
        runTest {
            val player = FakeLandingPlayer()
            val armer = DropLandingArmer(clock)
            val events = collectEvents(armer)
            armer.attach(player, backgroundScope)
            player.failSend = true

            assertFalse(armer.arm(song(), 0L, delayMs = 1_000L, fadeMs = 0L))

            assertEquals(DropLandingEvent.Missed(DropLandingEvent.Reason.PLAYER_ERROR), events.single())
            assertTrue(player.messages.isEmpty())
        }

    @Test
    fun `detach bricht die Armierung ab`() =
        runTest {
            val player = FakeLandingPlayer()
            val armer = DropLandingArmer(clock)
            val events = collectEvents(armer)
            armer.attach(player, backgroundScope)

            assertTrue(armer.arm(song(), 0L, delayMs = 1_000L, fadeMs = 0L))
            armer.detach()

            clock.advanceBy(2_000L)
            advanceTimeBy(2_000L)
            runCurrent()

            assertTrue(events.isEmpty())
            assertFalse(armer.arm(song(), 0L, 1_000L, 0L))
        }

    @Test
    fun `Fade-Rampe senkt die Lautstaerke vor dem Wechsel`() =
        runTest {
            val player = FakeLandingPlayer()
            val armer = DropLandingArmer(clock)
            val events = collectEvents(armer)
            armer.attach(player, backgroundScope)

            assertTrue(armer.arm(song(3L), 0L, delayMs = 2_000L, fadeMs = 1_000L))

            clock.advanceBy(1_000L)
            advanceTimeBy(1_000L)
            runCurrent()
            assertTrue("Fade muss vor dem Wechsel beginnen", player.volume < 1f)

            clock.advanceBy(1_000L)
            advanceTimeBy(1_000L)
            runCurrent()
            assertEquals("Fade endet am Wechselzeitpunkt", 0f, player.volume, 0f)

            player.messages.single().onFire()
            assertEquals(DropLandingEvent.Landed(3L, 0L), events.single())
            advanceTimeBy(50L)
            runCurrent()
            assertEquals(1f, player.volume, 0f)
        }

    private fun TestScope.collectEvents(armer: DropLandingArmer): MutableList<DropLandingEvent> {
        val events = mutableListOf<DropLandingEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            armer.events.collect { events += it }
        }
        return events
    }

    private fun song(id: Long = 1L): Song =
        Song(
            mediaStoreId = id,
            contentUri = "content://media/song/$id",
            displayName = "song$id.mp3",
            relativePath = "Music/",
            durationMs = 180_000L,
            sizeBytes = 1_000L,
            dateModifiedSeconds = 0L,
            title = "Song $id",
            artist = "Artist",
            album = "Album",
            isAvailable = true,
        )

    private class FakeLandingPlayer : LandingPlayer {
        override var currentPositionMs = 0L
        override var currentMediaItemIndex = 0
        override var isPlaying = true
        override var volume = 1f

        var failSend = false
        val messages = mutableListOf<SentMessage>()
        val startedSongs = mutableListOf<Pair<Song, Long>>()

        data class SentMessage(
            val mediaItemIndex: Int,
            val positionMs: Long,
            val onFire: () -> Unit,
            var cancelled: Boolean = false,
        )

        override fun sendMessageAt(
            mediaItemIndex: Int,
            positionMs: Long,
            onFire: () -> Unit,
        ): LandingMessage {
            if (failSend) throw IllegalStateException("Player nicht verbunden")
            val message = SentMessage(mediaItemIndex, positionMs, onFire)
            messages += message
            return LandingMessage { message.cancelled = true }
        }

        override fun startSong(
            song: Song,
            startPositionMs: Long,
        ) {
            startedSongs += song to startPositionMs
        }
    }
}
