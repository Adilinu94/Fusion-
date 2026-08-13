package com.dropsync.data.timer

import com.dropsync.core.common.Clock
import com.dropsync.domain.timer.CancelReason
import com.dropsync.domain.timer.RebootGuard
import com.dropsync.domain.timer.RestTimerRecovery
import com.dropsync.domain.timer.RestTimerServiceStarter
import com.dropsync.domain.timer.TimerEngine
import com.dropsync.domain.timer.TimerSnapshotStore
import javax.inject.Inject
import javax.inject.Singleton

/**
 * App-Start-Einstieg fuer den Kill-Fallback (Testinfra-Plan Schritt 2, 5b):
 * prueft, ob ein Timer lief und der Service weg ist, und startet beides neu.
 *
 * Ablauf:
 * 1. RebootGuard: Ruecksprung der monotonen Uhr oder fehlender Wert ->
 *    Snapshot verwerfen (CANCELLED mit DEVICE_REBOOT_OR_UNKNOWN_CLOCK),
 *    kein Neustart.
 * 2. Sonst [RestTimerRecovery.recover]: Snapshot -> Engine rehydrieren.
 * 3. Bei erfolgreicher Rehydrierung den Foreground-Service neu starten,
 *    damit `evaluate()` wieder laeuft.
 *
 * Wird beim Application-Start einmal aufgerufen; idempotent, weil
 * [DefaultRestTimerRecovery] das Snapshot beim Lesen verbraucht.
 */
@Singleton
class TimerRecoveryStarter
    @Inject
    constructor(
        private val engine: TimerEngine,
        private val recovery: RestTimerRecovery,
        private val snapshotStore: TimerSnapshotStore,
        private val monotonicStateStore: MonotonicStateStore,
        private val clock: Clock,
        private val serviceStarter: RestTimerServiceStarter,
    ) {
        suspend fun start() {
            val stored = monotonicStateStore.lastElapsedRealtimeMs()
            if (RebootGuard.shouldDiscard(stored, clock.elapsedRealtimeMs())) {
                snapshotStore.clear()
                engine.cancel(CancelReason.DEVICE_REBOOT_OR_UNKNOWN_CLOCK)
                engine.reset()
                monotonicStateStore.setLastElapsedRealtimeMs(clock.elapsedRealtimeMs())
                return
            }
            if (recovery.recover(engine)) {
                serviceStarter.startForegroundTimerService()
            }
            monotonicStateStore.setLastElapsedRealtimeMs(clock.elapsedRealtimeMs())
        }
    }
