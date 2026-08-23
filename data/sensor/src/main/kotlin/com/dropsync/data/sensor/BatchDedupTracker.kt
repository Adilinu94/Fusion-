package com.dropsync.data.sensor

import kotlin.math.roundToInt

/**
 * BLE-independent dedup + gap detection (port of batch_dedup_tracker.dart).
 * The polling loop can read the same on-wire batch twice (HyperOS drops
 * notifications) or miss one entirely; this tracker makes both OBSERVABLE
 * instead of silently continuing.
 *
 * Umbauplan Phase 3: additionally tracks the largest observed gap and the
 * recent packet-loss rate so [SensorHealth] can classify the stream. The
 * loss rate uses a real ring buffer over the last [lossWindowBatches]
 * batches (each entry = seen or missed), not a lossy aggregation.
 */
class BatchDedupTracker(
    /** Nominal gap between two genuinely different batches (4 x 20 ms). */
    private val expectedBatchIntervalMs: Int = 80,
    /** Window for the recent loss rate (batches). */
    private val lossWindowBatches: Int = 50,
) {
    private var lastTimestampMs: Int? = null

    /** Byte-identical re-reads of the previous batch. */
    var duplicateSkips = 0
        private set

    /** Batches likely produced but never read (from timestamp gaps). */
    var estimatedMissedBatches = 0
        private set

    /** Umbauplan Phase 3: largest real gap between two batches in ms. */
    var largestGapMs = 0L
        private set

    /** Umbauplan Phase 3: missed batches inside the recent window. */
    var recentMissedBatches = 0
        private set

    // true = batch arrived, false = batch missed (ring buffer).
    private val recentWindow = ArrayDeque<Boolean>()

    /** Missed / (seen + missed) over the last [lossWindowBatches] batches. */
    val recentPacketLossRate: Double
        get() {
            if (recentWindow.isEmpty()) return 0.0
            return recentMissedBatches.toDouble() / recentWindow.size
        }

    /** True when this wire timestamp is a duplicate and must be skipped. */
    fun shouldSkip(timestampMs: Int): Boolean {
        val last = lastTimestampMs
        if (last == null) {
            lastTimestampMs = timestampMs
            return false
        }
        if (timestampMs == last) {
            duplicateSkips++
            return true
        }
        val elapsed = timestampMs - last
        // Negative or absurdly large elapsed: reconnect (timestamps restart)
        // or millis() overflow (~49.7 days) — resync silently, don't count.
        if (elapsed > 0 && elapsed < expectedBatchIntervalMs * 1000) {
            val missed = (elapsed.toDouble() / expectedBatchIntervalMs).roundToInt() - 1
            if (missed > 0) {
                estimatedMissedBatches += missed
                repeat(missed) { pushRecent(false) }
                if (elapsed.toLong() > largestGapMs) largestGapMs = elapsed.toLong()
            }
        }
        pushRecent(true)
        lastTimestampMs = timestampMs
        return false
    }

    private fun pushRecent(seen: Boolean) {
        recentWindow.addLast(seen)
        if (!seen) recentMissedBatches++
        while (recentWindow.size > lossWindowBatches) {
            val oldest = recentWindow.removeFirst()
            if (!oldest) recentMissedBatches--
        }
    }

    /** Reset on reconnect. */
    fun reset() {
        lastTimestampMs = null
        duplicateSkips = 0
        estimatedMissedBatches = 0
        largestGapMs = 0L
        recentMissedBatches = 0
        recentWindow.clear()
    }
}
