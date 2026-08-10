package com.dropsync.data.sensor

import kotlin.math.roundToInt

/**
 * BLE-independent dedup + gap detection (port of batch_dedup_tracker.dart).
 * The polling loop can read the same on-wire batch twice (HyperOS drops
 * notifications) or miss one entirely; this tracker makes both OBSERVABLE
 * instead of silently continuing.
 */
class BatchDedupTracker(
    /** Nominal gap between two genuinely different batches (4 x 20 ms). */
    private val expectedBatchIntervalMs: Int = 80,
) {
    private var lastTimestampMs: Int? = null

    /** Byte-identical re-reads of the previous batch. */
    var duplicateSkips = 0
        private set

    /** Batches likely produced but never read (from timestamp gaps). */
    var estimatedMissedBatches = 0
        private set

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
            if (missed > 0) estimatedMissedBatches += missed
        }
        lastTimestampMs = timestampMs
        return false
    }

    /** Reset on reconnect. */
    fun reset() {
        lastTimestampMs = null
        duplicateSkips = 0
        estimatedMissedBatches = 0
    }
}
