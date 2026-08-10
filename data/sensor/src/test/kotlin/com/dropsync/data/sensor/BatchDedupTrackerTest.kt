package com.dropsync.data.sensor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BatchDedupTrackerTest {
    @Test
    fun `first batch is accepted`() {
        val tracker = BatchDedupTracker()
        assertFalse(tracker.shouldSkip(1000))
        assertEquals(0, tracker.duplicateSkips)
    }

    @Test
    fun `same timestamp is skipped as duplicate`() {
        val tracker = BatchDedupTracker()
        tracker.shouldSkip(1000)
        assertTrue(tracker.shouldSkip(1000))
        assertEquals(1, tracker.duplicateSkips)
    }

    @Test
    fun `normal interval counts no misses`() {
        val tracker = BatchDedupTracker(expectedBatchIntervalMs = 80)
        tracker.shouldSkip(1000)
        tracker.shouldSkip(1080) // exactly one interval later
        assertEquals(0, tracker.estimatedMissedBatches)
    }

    @Test
    fun `gap counts estimated missed batches`() {
        val tracker = BatchDedupTracker(expectedBatchIntervalMs = 80)
        tracker.shouldSkip(1000)
        tracker.shouldSkip(1320) // 4 intervals later -> 3 missed
        assertEquals(3, tracker.estimatedMissedBatches)
    }

    @Test
    fun `jitter rounds down to no miss`() {
        val tracker = BatchDedupTracker(expectedBatchIntervalMs = 80)
        tracker.shouldSkip(1000)
        tracker.shouldSkip(1110) // 110 ms -> 1.4 intervals -> round 1 -> 0 missed
        assertEquals(0, tracker.estimatedMissedBatches)
    }

    @Test
    fun `reconnect resyncs silently`() {
        val tracker = BatchDedupTracker()
        tracker.shouldSkip(100000)
        assertFalse(tracker.shouldSkip(5)) // timestamps restarted
        assertEquals(0, tracker.estimatedMissedBatches)
    }

    @Test
    fun `reset clears all counters`() {
        val tracker = BatchDedupTracker()
        tracker.shouldSkip(1000)
        tracker.shouldSkip(1000)
        tracker.reset()
        assertEquals(0, tracker.duplicateSkips)
        assertEquals(0, tracker.estimatedMissedBatches)
        assertFalse(tracker.shouldSkip(2000))
    }
}
