package com.dropsync.core.testing

import com.dropsync.domain.timer.DropRestRequestBus
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * Test-Fake fuer den [DropRestRequestBus]: zeichnet Anforderungen auf,
 * damit Tests den Drop-Auto-Pfad belegen koennen (Befund 3.14).
 */
class FakeDropRestRequestBus : DropRestRequestBus {
    private val _requests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private val _cancellations = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    override val requests: SharedFlow<Unit> = _requests
    override val cancellations: SharedFlow<Unit> = _cancellations

    /** Anzahl der gefeuerten Anforderungen (fuer Assertions). */
    var requestCount: Int = 0
        private set

    /**
     * Anzahl der Abbrechungen (fuer Assertions, Befund 5.11: Undo muss
     * einen laufenden Musik-Plan beenden, nicht nur den Satz loeschen).
     */
    var cancelCount: Int = 0
        private set

    override fun request() {
        requestCount++
        _requests.tryEmit(Unit)
    }

    override fun cancelForUndo() {
        cancelCount++
        _cancellations.tryEmit(Unit)
    }
}
