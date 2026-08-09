package com.dropsync.data.timer

import com.dropsync.domain.timer.DropRestRequestBus
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Prozessweiter [DropRestRequestBus] (Schritt 11): ein gepufferter
 * SharedFlow ohne Replay, damit nur waehrend aktiver Beobachtung durch den
 * Player-Screen gestartet wird. `tryEmit` ist nicht blockierend; geht ein
 * Ereignis mangels Sammler verloren, ist das fachlich unkritisch.
 */
class DefaultDropRestRequestBus : DropRestRequestBus {
    private val mutableRequests = MutableSharedFlow<Unit>(replay = 0, extraBufferCapacity = 1)

    override val requests: SharedFlow<Unit> = mutableRequests.asSharedFlow()

    override fun request() {
        mutableRequests.tryEmit(Unit)
    }
}
