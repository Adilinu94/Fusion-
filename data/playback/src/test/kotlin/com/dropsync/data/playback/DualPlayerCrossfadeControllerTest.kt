package com.dropsync.data.playback

import com.dropsync.domain.audio.MixPreset
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

@OptIn(ExperimentalCoroutinesApi::class)
class DualPlayerCrossfadeControllerTest {
    @Test
    fun `rampe ueberblendet beide spieler mit equal power`() =
        runTest {
            val outgoing = FakeVolumeTarget(volume = 0.8f, isPlaying = true)
            val incoming = FakeVolumeTarget(volume = 1f)
            var completed = false
            val controller = DualPlayerCrossfadeController(this, stepMs = 100L)

            controller.start(outgoing, incoming, durationSeconds = 1, preset = MixPreset.FADE) {
                completed = true
            }
            runCurrent()
            advanceTimeBy(500L)
            runCurrent()

            assertEquals(0.8f * sqrt(0.5).toFloat(), outgoing.volume, 0.002f)
            assertEquals(sqrt(0.5).toFloat(), incoming.volume, 0.002f)
            assertFalse(completed)
            assertTrue(incoming.isPlaying)

            advanceUntilIdle()

            assertTrue(completed)
            assertEquals(0f, outgoing.volume, 0f)
            assertEquals(1f, incoming.volume, 0f)
            assertTrue(outgoing.stopped)
        }

    @Test
    fun `abbruch stellt lautstaerken wieder her und pausiert neuen spieler`() =
        runTest {
            val outgoing = FakeVolumeTarget(volume = 0.7f, isPlaying = true)
            val incoming = FakeVolumeTarget(volume = 0.9f)
            val controller = DualPlayerCrossfadeController(this, stepMs = 100L)

            controller.start(outgoing, incoming, durationSeconds = 2, preset = MixPreset.MELT)
            runCurrent()
            advanceTimeBy(500L)
            runCurrent()
            controller.cancel()

            assertEquals(0.7f, outgoing.volume, 0f)
            assertEquals(0.9f, incoming.volume, 0f)
            assertFalse(incoming.isPlaying)
            assertFalse(outgoing.stopped)
        }

    @Test
    fun `null dauer startet keinen zweiten spieler`() =
        runTest {
            val outgoing = FakeVolumeTarget(volume = 0.65f, isPlaying = true)
            val incoming = FakeVolumeTarget(volume = 1f)
            val controller = DualPlayerCrossfadeController(this)

            val job = controller.start(outgoing, incoming, durationSeconds = 0, preset = MixPreset.FADE)

            assertEquals(null, job)
            assertEquals(0, incoming.playCalls)
            assertEquals(0.65f, outgoing.volume, 0f)
            assertEquals(1f, incoming.volume, 0f)
        }

    @Test
    fun `ungueltige dauer wird auf zwoelf sekunden begrenzt`() =
        runTest {
            val outgoing = FakeVolumeTarget(volume = 1f, isPlaying = true)
            val incoming = FakeVolumeTarget()
            val controller = DualPlayerCrossfadeController(this, stepMs = 1_000L)

            controller.start(outgoing, incoming, durationSeconds = 50, preset = MixPreset.BLEND)
            runCurrent()
            advanceTimeBy(11_999L)
            runCurrent()
            assertTrue(incoming.isPlaying)
            advanceTimeBy(1L)
            runCurrent()

            assertTrue(outgoing.stopped)
            assertEquals(1f, incoming.volume, 0f)
        }

    private class FakeVolumeTarget(
        override var volume: Float = 1f,
        override var isPlaying: Boolean = false,
    ) : CrossfadeVolumeTarget {
        var playCalls = 0
        var stopped = false

        override fun play() {
            isPlaying = true
            playCalls += 1
        }

        override fun pause() {
            isPlaying = false
        }

        override fun stop() {
            isPlaying = false
            stopped = true
        }
    }
}
