package com.dropsync.data.timer

/**
 * In-Memory-[MonotonicStateStore] fuer Tests des Kill-Fallbacks (5b).
 */
internal class FakeMonotonicStateStore : MonotonicStateStore {
    var stored: Long? = null

    override suspend fun lastElapsedRealtimeMs(): Long? = stored

    override suspend fun setLastElapsedRealtimeMs(value: Long) {
        stored = value
    }
}
