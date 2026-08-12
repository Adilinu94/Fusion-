package com.dropsync.domain.timer

/**
 * Standard-Recovery (5b): rehydriert einen laufenden Timer aus dem Store.
 *
 * Rein JVM und damit ohne Android testbar. Die Entscheidung ist absichtlich
 * schmal: kein Snapshot -> `false`; ein vorhandenes Snapshot wird der Engine
 * zum [TimerEngine.restore] angeboten. Ob die Engine es annimmt (Status,
 * DROPSYNC-Ausschluss) entscheidet sie selbst; bei Ablehnung wird der Store
 * bereinigt, damit kein veralteter Zustand liegen bleibt.
 */
class DefaultRestTimerRecovery(
    private val store: TimerSnapshotStore,
) : RestTimerRecovery {
    override suspend fun recover(engine: TimerEngine): Boolean {
        val snapshot = store.load() ?: return false
        val restored = engine.restore(snapshot)
        // Verbrauchtes oder abgelehntes Snapshot entfernen, damit ein
        // erneuter Start nicht denselben Zustand doppelt rehydriert.
        store.clear()
        return restored
    }
}
