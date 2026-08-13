package com.dropsync.core.testing

import com.dropsync.domain.timer.TimerSnapshot
import com.dropsync.domain.timer.TimerSnapshotStore

/**
 * In-Memory-[TimerSnapshotStore] fuer pure JVM-Tests der Recovery-Logik (5b).
 * Haelt genau ein Snapshot; [save] ueberschreibt, [clear] leert.
 */
class FakeTimerSnapshotStore : TimerSnapshotStore {
    private var current: TimerSnapshot? = null

    /** Zuletzt gespeichertes Snapshot (null, wenn leer). */
    val snapshot: TimerSnapshot?
        get() = current

    /** Anzahl der clear()-Aufrufe, um Bereinigung zu pruefen. */
    var clearCount = 0
        private set

    override suspend fun load(): TimerSnapshot? = current

    override suspend fun save(snapshot: TimerSnapshot) {
        current = snapshot
    }

    override suspend fun clear() {
        clearCount++
        current = null
    }
}
