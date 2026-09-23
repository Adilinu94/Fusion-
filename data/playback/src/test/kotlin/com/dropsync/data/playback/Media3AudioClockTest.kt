package com.dropsync.data.playback

import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dropsync.domain.playback.AudioClock
import com.dropsync.domain.playback.AudioRouteProfile
import com.dropsync.domain.playback.ClockConfidence
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration

/**
 * MVP-AudioClock (Design Phase 6, ADR-0012): Position-Interpolation
 * zwischen Media3-Updates ueber die monotone Systemzeit — mit
 * Delta-Tests (5c-Regel: nie absolute Geraetezeiten pruefen).
 *
 * Der Event-Layer des Players laeuft asynchron ueber den Main-Looper;
 * die Tests feuern die Clock-Listener-Events deshalb direkt
 * (daher `internal positionListener` in [Media3AudioClock]).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class Media3AudioClockTest {
    private val routeProfiles = FakeRouteProfiles()
    private val clock = Media3AudioClock(routeProfiles)
    private val fakePlayer = FakePlayer()

    private fun item(id: String): MediaItem = MediaItem.Builder().setMediaId(id).build()

    private fun playingPlayer(startPositionMs: Long = 5_000L): FakePlayer =
        FakePlayer().apply {
            setMediaItems(listOf(item("1")), 0, startPositionMs)
            prepare()
            play()
        }

    // --- ohne Player ------------------------------------------------------

    @Test
    fun `ohne gebundenen Player ist die Uhr unverfuegbar`() =
        runTest {
            assertEquals(AudioClock.Mode.UNAVAILABLE, clock.mode)
            assertEquals(0L, clock.audiblePositionMs())
            assertEquals(0L, clock.playheadPositionMs())
            assertNull(clock.snapshot())
        }

    // --- Mode und Interpolation -------------------------------------------

    @Test
    fun `attach aktiviert BEST_EFFORT und liest die Player-Position`() {
        fakePlayer.setMediaItems(listOf(item("1")), 0, 5_000L)
        clock.attach(fakePlayer)

        assertEquals(AudioClock.Mode.BEST_EFFORT, clock.mode)
        assertEquals(5_000L, clock.playheadPositionMs())
        // Nicht spielend: Position bleibt auf dem letzten Stand (base).
        assertEquals(5_000L, clock.audiblePositionMs())
    }

    @Test
    fun `waehrend Wiedergabe interpoliert die Uhr ueber die laufende Systemzeit`() {
        val playing = playingPlayer()
        clock.attach(playing)
        assertTrue(playing.isPlaying)

        val base = clock.audiblePositionMs()
        ShadowSystemClock.advanceBy(Duration.ofMillis(500))
        assertEquals(base + 500L, clock.audiblePositionMs())

        ShadowSystemClock.advanceBy(Duration.ofMillis(300))
        assertEquals(base + 800L, clock.audiblePositionMs())
    }

    @Test
    fun `ohne Wiedergabe friert die Position auf dem letzten Stand`() {
        fakePlayer.setMediaItems(listOf(item("1")), 0, 5_000L)
        clock.attach(fakePlayer)

        ShadowSystemClock.advanceBy(Duration.ofMillis(1_000))
        assertEquals("Nicht spielend darf die Uhr nicht weiterlaufen", 5_000L, clock.audiblePositionMs())
    }

    // --- Player-Events ----------------------------------------------------

    @Test
    fun `onIsPlayingChanged nimmt die Uhr neu auf und startet die Interpolation`() {
        fakePlayer.setMediaItems(listOf(item("1")), 0, 5_000L)
        clock.attach(fakePlayer)
        ShadowSystemClock.advanceBy(Duration.ofMillis(1_000))

        fakePlayer.setPositionSilently(7_000L)
        clock.positionListener.onIsPlayingChanged(true)

        assertEquals(7_000L, clock.audiblePositionMs())
        ShadowSystemClock.advanceBy(Duration.ofMillis(250))
        assertEquals(7_250L, clock.audiblePositionMs())
    }

    @Test
    fun `onIsPlayingChanged stoppt die Interpolation beim Pausieren`() {
        val playing = playingPlayer()
        clock.attach(playing)
        ShadowSystemClock.advanceBy(Duration.ofMillis(400))

        playing.pause()
        playing.setPositionSilently(5_400L)
        clock.positionListener.onIsPlayingChanged(false)

        val frozen = clock.audiblePositionMs()
        ShadowSystemClock.advanceBy(Duration.ofMillis(900))
        assertEquals("Nach Pause darf die Position nicht weiterlaufen", frozen, clock.audiblePositionMs())
    }

    @Test
    fun `onPlaybackStateChanged setzt die Positions-Basis neu`() {
        fakePlayer.setMediaItems(listOf(item("1")), 0, 5_000L)
        clock.attach(fakePlayer)

        fakePlayer.setPositionSilently(9_000L)
        clock.positionListener.onPlaybackStateChanged(Player.STATE_READY)

        assertEquals(9_000L, clock.audiblePositionMs())
    }

    // --- detach -----------------------------------------------------------

    @Test
    fun `detach loest die Bindung wieder`() =
        runTest {
            clock.attach(playingPlayer())
            assertEquals(AudioClock.Mode.BEST_EFFORT, clock.mode)

            clock.detach()

            assertEquals(AudioClock.Mode.UNAVAILABLE, clock.mode)
            assertEquals(0L, clock.audiblePositionMs())
            assertEquals(0L, clock.playheadPositionMs())
            assertNull(clock.snapshot())
        }

    // --- Snapshot ---------------------------------------------------------

    @Test
    fun `Snapshot traegt Route-Profil, Confidence und Spielerzustand durch`() =
        runTest {
            routeProfiles.profile =
                AudioRouteProfile(
                    routeKey = "bt-a2dp",
                    sampleRate = 48_000,
                    channels = 2,
                    estimatedLatencyMs = 180L,
                )
            val playing = playingPlayer()
            clock.attach(playing)

            val snapshot = requireNotNull(clock.snapshot())
            assertEquals(180L, snapshot.outputLatencyMs)
            assertEquals("bt-a2dp", snapshot.routeId)
            assertEquals(ClockConfidence.SOFTWARE_ESTIMATE, snapshot.confidence)
            assertEquals(playing.currentPosition, snapshot.playerPositionMs)
            assertTrue(snapshot.isPlaying)
            assertFalse(snapshot.isLoading)
            assertFalse(snapshot.hadRecentUnderrun)
        }

    @Test
    fun `Snapshot ohne Profil meldet Latenz und Route als null`() =
        runTest {
            routeProfiles.profile = null
            clock.attach(playingPlayer())

            val snapshot = requireNotNull(clock.snapshot())
            assertNull(snapshot.outputLatencyMs)
            assertNull(snapshot.routeId)
        }

    @Test
    fun `Snapshot meldet den Ladezustand des Players`() =
        runTest {
            fakePlayer.setMediaItems(listOf(item("1")), 0, 5_000L)
            fakePlayer.prepare()
            fakePlayer.setLoadingSilently(true)
            clock.attach(fakePlayer)

            val snapshot = requireNotNull(clock.snapshot())
            assertTrue(snapshot.isLoading)
        }

    // --- Underrun-Fenster -------------------------------------------------

    @Test
    fun `Player-Fehler zaehlt als kuerzlicher Underrun und faellt nach dem Fenster raus`() =
        runTest {
            clock.attach(playingPlayer())
            assertFalse(requireNotNull(clock.snapshot()).hadRecentUnderrun)

            clock.positionListener.onPlayerError(
                PlaybackException("boom", null, PlaybackException.ERROR_CODE_UNSPECIFIED),
            )
            assertTrue(requireNotNull(clock.snapshot()).hadRecentUnderrun)

            ShadowSystemClock.advanceBy(Duration.ofMillis(2_001))
            assertFalse(
                "Nach dem 2s-Fenster faellt der Underrun-Hinweis raus",
                requireNotNull(clock.snapshot()).hadRecentUnderrun,
            )
        }
}
