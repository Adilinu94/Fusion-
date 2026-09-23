package com.dropsync.core.testing

import com.dropsync.domain.timer.DropSyncPlanMarker
import com.dropsync.domain.timer.DropSyncPlanStore

/**
 * Fake des C13-Markers: Tests koennen einen ueberlebenden Plan vorlegen
 * und pruefen, was der Koordinator beim Start daraus macht.
 */
class FakeDropSyncPlanStore(
    initial: DropSyncPlanMarker? = null,
) : DropSyncPlanStore {
    private var marker: DropSyncPlanMarker? = initial

    /** Wie oft der Marker gesetzt wurde (Diagnose in Tests). */
    var saveCalls = 0
        private set

    /** Wie oft der Marker geloescht wurde (Diagnose in Tests). */
    var clearCalls = 0
        private set

    override suspend fun load(): DropSyncPlanMarker? = marker

    override suspend fun save(marker: DropSyncPlanMarker) {
        this.marker = marker
        saveCalls++
    }

    override suspend fun clear() {
        marker = null
        clearCalls++
    }

    fun current(): DropSyncPlanMarker? = marker
}
