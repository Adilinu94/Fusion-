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
    override val requests: SharedFlow<Unit> = _requests

    /** Anzahl der gefeuerten Anforderungen (fuer Assertions). */
    var requestCount: Int = 0
        private set

    override fun request() {
        requestCount++
        _requests.tryEmit(Unit)
    }
}
