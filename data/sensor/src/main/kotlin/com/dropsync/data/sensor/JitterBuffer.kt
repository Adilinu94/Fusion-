package com.dropsync.data.sensor

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.ArrayDeque

/**
 * Turns bursty BLE packets into an even 50 Hz stream (port of
 * jitter_buffer.dart). Latency: bufferSize * tickInterval = 6 * 20 ms =
 * 120 ms (acceptable for rep counting).
 */
class JitterBuffer<T>(
    private val scope: CoroutineScope,
    private val onFrame: (T) -> Unit,
    private val bufferSize: Int = 6,
    private val tickIntervalMs: Long = 20,
) {
    private val queue = ArrayDeque<T>()
    private var job: Job? = null

    var droppedFrames = 0
        private set
    var outputFrames = 0
        private set
    var underrunCount = 0
        private set
    private var totalTicks = 0

    /** Starts periodic output. Idempotent. */
    fun start() {
        if (job != null) return
        job =
            scope.launch {
                while (isActive) {
                    delay(tickIntervalMs)
                    tick()
                }
            }
    }

    /** Stops output and clears the buffer (disconnect / session end). */
    fun stop() {
        job?.cancel()
        job = null
        queue.clear()
    }

    /** Drop-oldest when full. */
    @Synchronized
    fun add(item: T) {
        if (queue.size >= bufferSize) {
            queue.pollFirst()
            droppedFrames++
        }
        queue.addLast(item)
    }

    fun addBatch(items: List<T>) = items.forEach(::add)

    private fun tick() {
        val item: T?
        synchronized(this) {
            totalTicks++
            item = queue.pollFirst()
            if (item == null) underrunCount++ else outputFrames++
        }
        item?.let(onFrame)
    }

    val isRunning: Boolean
        get() = job != null

    val queueLength: Int
        get() = queue.size

    /** Drop rate 0..1; > 0.1 is critical (consider filter reset). */
    val dropRate: Double
        get() {
            val total = droppedFrames + outputFrames
            return if (total == 0) 0.0 else droppedFrames.toDouble() / total
        }

    /** Underrun rate 0..1; high values hint at packet loss. */
    val underrunRate: Double
        get() = if (totalTicks == 0) 0.0 else underrunCount.toDouble() / totalTicks

    fun reset() {
        stop()
        droppedFrames = 0
        outputFrames = 0
        underrunCount = 0
        totalTicks = 0
    }
}
