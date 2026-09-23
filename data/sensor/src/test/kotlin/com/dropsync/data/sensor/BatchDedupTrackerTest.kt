package com.dropsync.data.sensor

import com.dropsync.domain.sensor.SensorConnectionState
import com.dropsync.domain.sensor.SensorHealth
import com.dropsync.domain.sensor.SignalQuality
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

    // --- Umbauplan Phase 3: SensorHealth-Daten -----------------------------

    @Test
    fun `largest gap is tracked in ms`() {
        val tracker = BatchDedupTracker(expectedBatchIntervalMs = 80)
        tracker.shouldSkip(1000)
        tracker.shouldSkip(1080) // normal
        tracker.shouldSkip(1560) // 480 ms gap
        tracker.shouldSkip(1720) // 160 ms gap
        assertEquals(480L, tracker.largestGapMs)
    }

    @Test
    fun `recent packet loss rate reflects missed batches`() {
        val tracker = BatchDedupTracker(expectedBatchIntervalMs = 80, lossWindowBatches = 10)
        tracker.shouldSkip(1000)
        // 3 missed batches -> loss rate 3/(1+3) = 0.75
        tracker.shouldSkip(1320)
        assertEquals(0.75, tracker.recentPacketLossRate, 0.001)
    }

    @Test
    fun `loss rate falls as new batches arrive`() {
        val tracker = BatchDedupTracker(expectedBatchIntervalMs = 80, lossWindowBatches = 10)
        tracker.shouldSkip(1000)
        tracker.shouldSkip(1320) // 3 missed -> Window: [m,m,m,s] Rate 0.75
        assertEquals(0.75, tracker.recentPacketLossRate, 0.001)
        repeat(5) { i -> tracker.shouldSkip(1400 + i * 80) }
        // Window: [m,m,m,s,s,s,s,s,s] -> 3/9
        assertEquals(3.0 / 9.0, tracker.recentPacketLossRate, 0.001)
        repeat(4) { i -> tracker.shouldSkip(1800 + i * 80) }
        // 13 Eintraege, auf 10 getrimmt: die 3 Misses fallen heraus -> 0.0
        assertEquals(0.0, tracker.recentPacketLossRate, 0.001)
    }

    @Test
    fun `reset clears health counters`() {
        val tracker = BatchDedupTracker(expectedBatchIntervalMs = 80)
        tracker.shouldSkip(1000)
        tracker.shouldSkip(1320)
        tracker.reset()
        assertEquals(0L, tracker.largestGapMs)
        assertEquals(0.0, tracker.recentPacketLossRate, 0.0)
        assertEquals(0, tracker.recentMissedBatches)
        assertEquals(0L, tracker.largestRecentGapMs)
    }

    // --- A3/S-2: Gap im gleitenden Fenster --------------------------------

    @Test
    fun `gap faellt nach dem fenster heraus`() {
        val tracker = BatchDedupTracker(expectedBatchIntervalMs = 80, gapWindowBatches = 10)
        tracker.shouldSkip(1000)
        tracker.shouldSkip(1560) // 560 ms Gap
        assertEquals(560L, tracker.largestRecentGapMs)

        // Zehn weitere Batches schieben den Gap aus dem Fenster.
        repeat(10) { i -> tracker.shouldSkip(1640 + i * 80) }

        assertEquals(0L, tracker.largestRecentGapMs)
        assertEquals("kumulativ bleibt der Gap fuer die Diagnose sichtbar", 560L, tracker.largestGapMs)
    }

    @Test
    fun `gap im fenster bleibt sichtbar`() {
        val tracker = BatchDedupTracker(expectedBatchIntervalMs = 80, gapWindowBatches = 10)
        tracker.shouldSkip(1000)
        tracker.shouldSkip(1560)

        repeat(4) { i -> tracker.shouldSkip(1640 + i * 80) }

        assertEquals(560L, tracker.largestRecentGapMs)
    }

    @Test
    fun `gap groesser 80 s wird als resync nicht gezaehlt`() {
        val tracker = BatchDedupTracker(expectedBatchIntervalMs = 80, gapWindowBatches = 10)
        tracker.shouldSkip(1000)
        tracker.shouldSkip(1000 + 80_000) // >= 80 s: Resync, kein Gap

        assertEquals(0L, tracker.largestRecentGapMs)
        assertEquals(0L, tracker.largestGapMs)
    }

    @Test
    fun `500-ms-gap danach 5 s sauber ergibt GOOD`() {
        val tracker =
            BatchDedupTracker(
                expectedBatchIntervalMs = 80,
                lossWindowBatches = 10,
                gapWindowBatches = 63,
            )
        tracker.shouldSkip(1000)
        tracker.shouldSkip(1560) // 560 ms Gap
        val during =
            SensorHealth(
                connectionState = SensorConnectionState.STREAMING,
                largestRecentGapMs = tracker.largestRecentGapMs,
                recentPacketLossRate = tracker.recentPacketLossRate,
            )
        assertEquals(SignalQuality.UNRELIABLE, during.quality)

        // 63 saubere Batches (rund 5 s) leeren das Gap-Fenster.
        repeat(63) { i -> tracker.shouldSkip(1640 + i * 80) }

        val after =
            SensorHealth(
                connectionState = SensorConnectionState.STREAMING,
                largestRecentGapMs = tracker.largestRecentGapMs,
                recentPacketLossRate = tracker.recentPacketLossRate,
            )
        assertEquals(0L, tracker.largestRecentGapMs)
        assertEquals(SignalQuality.GOOD, after.quality)
    }
}
