package com.dropsync.data.sensor

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class JitterBufferTest {
    @Test
    fun `emits one item per tick in fifo order`() =
        runTest {
            val emitted = mutableListOf<Int>()
            val buffer =
                JitterBuffer<Int>(scope = this, onFrame = { emitted.add(it) }, tickIntervalMs = 20)
            buffer.addBatch(listOf(1, 2, 3))
            buffer.start()
            advanceTimeBy(65) // 3 ticks
            assertEquals(listOf(1, 2, 3), emitted)
            buffer.stop()
        }

    @Test
    fun `full buffer drops oldest`() =
        runTest {
            val emitted = mutableListOf<Int>()
            val buffer =
                JitterBuffer<Int>(scope = this, onFrame = { emitted.add(it) }, bufferSize = 2, tickIntervalMs = 20)
            buffer.addBatch(listOf(1, 2, 3)) // 1 dropped
            assertEquals(1, buffer.droppedFrames)
            buffer.start()
            advanceTimeBy(45)
            assertEquals(listOf(2, 3), emitted)
            buffer.stop()
        }

    @Test
    fun `empty buffer counts underruns`() =
        runTest {
            val buffer = JitterBuffer<Int>(scope = this, onFrame = {}, tickIntervalMs = 20)
            buffer.start()
            advanceTimeBy(105) // 5 ticks, all underrun
            assertTrue(buffer.underrunCount >= 4)
            assertTrue(buffer.underrunRate > 0.9)
            buffer.stop()
        }

    @Test
    fun `stop clears queue`() =
        runTest {
            val emitted = mutableListOf<Int>()
            val buffer =
                JitterBuffer<Int>(scope = this, onFrame = { emitted.add(it) }, tickIntervalMs = 20)
            buffer.addBatch(listOf(1, 2, 3))
            buffer.start()
            advanceTimeBy(25)
            buffer.stop()
            assertEquals(0, buffer.queueLength)
            assertEquals(1, emitted.size)
        }

    @Test
    fun `start is idempotent`() =
        runTest {
            val job = launch { }
            val buffer = JitterBuffer<Int>(scope = this, onFrame = {}, tickIntervalMs = 20)
            buffer.start()
            buffer.start()
            assertTrue(buffer.isRunning)
            buffer.stop()
            job.cancel()
        }
}
