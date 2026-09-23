package com.dropsync.data.sensor

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Serialisierte FIFO fuer GATT-Operationen (Android erlaubt genau eine
 * ausstehende Operation). Aus [BleGattClient] extrahiert, damit die
 * Queue-Logik ohne Bluetooth-Hardware auf der JVM testbar ist.
 *
 * Regeln (Umbauplan Phase 2.1):
 * - Nur eine Operation gleichzeitig; die naechste startet erst, wenn
 *   [opDone] mit dem passenden Callback-Typ kommt.
 * - Spaete Callbacks fremder Typen werden ignoriert (Typ- und ID-Guard).
 * - Pro Operation laeuft ein Timeout ([timeoutMs]). Fehlt der Callback,
 *   wird die Continuation ueber [onTimeout] freigegeben, die Queue
 *   freigeschaltet und bei READ/DISCOVER [GattEvent.OperationTimedOut]
 *   gemeldet (Reihenfolge: Freigabe -> Queue -> Event).
 *
 * Fix gegenueber dem Vorgaenger: Der Timeout-Job wird erst bei [opDone]
 * oder [clear] storniert, nicht, wenn der angesetzte Aufruf zurueckkehrt.
 * Die Android-GATT-Aufrufe sind synchron — sonst waere der Timeout sofort
 * tot und ein haengender READ wuerde die Queue dauerhaft blockieren.
 */
internal class GattOperationQueue(
    private val scope: CoroutineScope,
    private val timeoutMs: Long = BleGattClient.OPERATION_TIMEOUT_MS,
    private val onTimeout: (GattOperationType) -> Unit = {},
    private val onEvent: (GattEvent) -> Unit = {},
) {
    private var nextOpId = 0L

    private data class ActiveOp(
        val id: Long,
        val type: GattOperationType,
        val timeoutJob: Job?,
    )

    private val activeOp = AtomicReference<ActiveOp?>(null)
    private val opInFlight = AtomicBoolean(false)
    private val opQueue = ConcurrentLinkedQueue<Pair<GattOperationType, suspend () -> Unit>>()

    fun enqueue(
        type: GattOperationType,
        op: suspend () -> Unit,
    ) {
        opQueue.add(type to op)
        drain()
    }

    /** Beendet die aktive Operation, deren Typ zum Callback passt. */
    fun opDone(type: GattOperationType) {
        val active = activeOp.get() ?: return
        if (active.type != type) return
        opDone(active.id)
    }

    /** Setzt die Queue zurueck (close/disconnect): alles leer, nichts aktiv. */
    fun clear() {
        activeOp.getAndSet(null)?.timeoutJob?.cancel()
        opInFlight.set(false)
        opQueue.clear()
    }

    private fun drain() {
        if (!opInFlight.compareAndSet(false, true)) return
        val (type, op) =
            opQueue.poll() ?: run {
                opInFlight.set(false)
                return
            }
        scope.launch {
            val opId = ++nextOpId
            val timeoutJob =
                scope.launch {
                    delay(timeoutMs)
                    if (activeOp.get()?.id == opId) {
                        onTimeout(type)
                        opDone(opId)
                        if (type == GattOperationType.READ || type == GattOperationType.DISCOVER) {
                            onEvent(GattEvent.OperationTimedOut)
                        }
                    }
                }
            activeOp.set(ActiveOp(opId, type, timeoutJob))
            op()
        }
    }

    private fun opDone(opId: Long) {
        val active = activeOp.get() ?: return
        if (active.id != opId) return
        if (!activeOp.compareAndSet(active, null)) return
        active.timeoutJob?.cancel()
        opInFlight.set(false)
        drain()
    }
}
