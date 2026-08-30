package com.dropsync.data.sensor

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.ArrayDeque

/**
 * Turns bursty BLE packets into an even 50 Hz stream (port of
 * jitter_buffer.dart).
 *
 * Dimensionierung (P0-Fix): ein BLE-Batch enthaelt
 * [BleProtocolParser.SAMPLES_PER_BATCH] = 4 Samples, der Tick entnimmt genau
 * eines je [tickIntervalMs]. Treffen zwei Batches innerhalb eines
 * Tick-Intervalls ein — im read()-Polling-Loop der Normalfall — liegen
 * kurzzeitig 8 Samples an. Der frueher verwendete Puffer von 6 Eintraegen
 * verwarf dabei bei JEDEM Doppel-Batch 2 Samples: keine Jitter-Absorption,
 * sondern stille Dezimierung des Signals. [DEFAULT_BUFFER_SIZE] fasst
 * deshalb zwei vollstaendige Batches plus Reserve.
 *
 * Latenz: bufferSize * tickInterval nur im Vollzustand; im Regelbetrieb
 * bestimmt die Batch-Ankunftsrate die Fuellhoehe, nicht die Kapazitaet.
 */
class JitterBuffer<T>(
    private val scope: CoroutineScope,
    private val onFrame: (T) -> Unit,
    private val bufferSize: Int = DEFAULT_BUFFER_SIZE,
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

    companion object {
        /**
         * Zwei vollstaendige BLE-Batches (2 x 4 Samples) plus Reserve fuer
         * einen dritten halben. Kleinere Werte verwerfen bei jedem
         * Doppel-Batch Samples (P0-Fix).
         */
        const val DEFAULT_BUFFER_SIZE = 12
    }
}
