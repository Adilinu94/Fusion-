package com.dropsync.data.sensor

import com.dropsync.domain.sensor.SensorSample
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

/**
 * Zentraler Sample-Fan-out (RC-10): EIN Punkt, an dem rohe Sensor-Samples
 * in die Verbraucher laufen (Waveform-Anzeige, ActiveSetController,
 * Kalibrierung). Alle Abonnenten sehen dieselbe Sample-Folge.
 *
 * Warum nicht direkt `MutableSharedFlow.tryEmit`: SharedFlow puffert still
 * und verwirft bei vollem Puffer das NEUE Sample, ohne dass es jemand
 * zaehlt. Mit zwei Verbrauchern (Waveform + Zaehlpipeline) und
 * unterschiedlicher Konsumgeschwindigkeit ist der Verlust unsichtbar —
 * genau der Befund aus RC-10.
 *
 * Backpressure: DROP_OLDEST. Der Ringpuffer verdrangt bei Ueberlast das
 * AELTESTE Sample; die Gegenwart gewinnt (Rep-Counting braucht das aktuelle
 * Signal, nicht das aelteste). Jede Verdrangung wird in [droppedSamples]
 * gezaehlt und ueber [com.dropsync.domain.sensor.SensorHealth] sichtbar.
 *
 * Der Emitter ist nicht-suspendierend (JitterBuffer-Frame-Tick). Das
 * Verteilen laeuft in einer Drain-Coroutine, die bei vollem
 * SharedFlow-Buffer suspendiert — dadurch staut es im Ringpuffer, und
 * genau dort wird gezaehlt.
 */
internal class SensorSampleFanout(
    scope: CoroutineScope,
    private val capacity: Int = DEFAULT_CAPACITY,
) {
    private val lock = Any()
    private val pending = ArrayDeque<SensorSample>(capacity)
    private var dropped = 0L
    private val wakeup = Channel<Unit>(Channel.CONFLATED)
    private val mutableSamples = MutableSharedFlow<SensorSample>(extraBufferCapacity = capacity)

    /** Alle Verbraucher sehen dieselbe Folge (echter Fan-out, kein Work-Stealing). */
    val samples: SharedFlow<SensorSample> = mutableSamples.asSharedFlow()

    /** Anzahl der seit dem letzten [reset] verdrangten Samples. */
    val droppedSamples: Long
        get() = synchronized(lock) { dropped }

    init {
        scope.launch {
            for (ignored in wakeup) {
                drain()
            }
        }
    }

    /**
     * Uebernimmt ein Sample ohne Suspendieren. Ohne Abonnenten wird nichts
     * gepuffert und nichts gezaehlt: Samples, die niemand sehen will, sind
     * kein Verlust im Sinne der Health-Anzeige.
     */
    fun emit(sample: SensorSample) {
        if (mutableSamples.subscriptionCount.value == 0) return
        synchronized(lock) {
            if (pending.size >= capacity) {
                pending.removeFirst()
                dropped++
            }
            pending.addLast(sample)
        }
        wakeup.trySend(Unit)
    }

    /** Setzt Puffer und Zaehler zurueck (Stream-Neustart). */
    fun reset() {
        synchronized(lock) {
            pending.clear()
            dropped = 0L
        }
    }

    private suspend fun drain() {
        while (true) {
            val next = synchronized(lock) { pending.removeFirstOrNull() } ?: return
            mutableSamples.emit(next)
        }
    }

    companion object {
        const val DEFAULT_CAPACITY = 64
    }
}
