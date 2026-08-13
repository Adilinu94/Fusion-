package com.dropsync.data.timer

import com.dropsync.core.testing.FakeClock
import com.dropsync.core.testing.FakeTimerSnapshotStore
import com.dropsync.domain.timer.DefaultRestTimerRecovery
import com.dropsync.domain.timer.NoOpCueOutput
import com.dropsync.domain.timer.RestTimerServiceStarter
import com.dropsync.domain.timer.TimerEngine
import com.dropsync.domain.timer.TimerMode
import com.dropsync.domain.timer.TimerSession
import com.dropsync.domain.timer.TimerSnapshot
import com.dropsync.domain.timer.TimerStatus
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM-Test fuer [TimerRecoveryStarter] (Kill-Fallback 5b): die
 * App-Start-Entscheidung - Reboot verwerfen, Snapshot rehydrieren, Service
 * neu starten - ohne echten Foreground-Service.
 */
class TimerRecoveryStarterTest {
    private val clock = FakeClock(initialElapsedRealtimeMs = 50_000, initialEpochMillis = 1_000)
    private val store = FakeTimerSnapshotStore()
    private val monotonic = FakeMonotonicStateStore()
    private var serviceStarts = 0

    private fun newEngine(): TimerEngine =
        TimerEngine(clock, NoOpCueOutput()) { "restored" }

    private fun newStarter(engine: TimerEngine): TimerRecoveryStarter =
        TimerRecoveryStarter(
            engine = engine,
            recovery = DefaultRestTimerRecovery(store),
            snapshotStore = store,
            monotonicStateStore = monotonic,
            clock = clock,
            serviceStarter = RestTimerServiceStarter { serviceStarts++ },
        )

    private fun runningSnapshot(): TimerSnapshot =
        TimerSnapshot(
            session =
                TimerSession(
                    id = "s1",
                    mode = TimerMode.REST,
                    durationMs = 60_000,
                    startedElapsedRealtimeMs = 0L,
                    markerPositionMs = null,
                    plannedCues = emptyList(),
                ),
            status = TimerStatus.RUNNING,
            endElapsedRealtimeMs = clock.elapsedRealtimeMs() + 30_000,
            pausedRemainingMs = null,
        )

    @Test
    fun `reboot verwirft snapshot und startet keinen service`() = runTest {
        store.save(runningSnapshot())
        monotonic.stored = clock.elapsedRealtimeMs() + 5_000 // Ruecksprung simuliert

        newStarter(newEngine()).start()

        assertNull("Snapshot wurde nach Reboot nicht verworfen", store.snapshot)
        assertEquals(0, serviceStarts)
    }

    @Test
    fun `fehlender monotoner wert verwirft snapshot`() = runTest {
        store.save(runningSnapshot())
        monotonic.stored = null

        newStarter(newEngine()).start()

        assertNull(store.snapshot)
        assertEquals(0, serviceStarts)
        assertEquals(clock.elapsedRealtimeMs(), monotonic.stored)
    }

    @Test
    fun `snapshot rehydriert engine und startet service neu`() = runTest {
        store.save(runningSnapshot())
        monotonic.stored = clock.elapsedRealtimeMs() - 10_000

        val engine = newEngine()
        newStarter(engine).start()

        assertEquals(TimerStatus.RUNNING, engine.state.value.status)
        assertEquals(1, serviceStarts)
        assertNull("Snapshot wurde verbraucht", store.snapshot)
    }

    @Test
    fun `kein snapshot startet keinen service`() = runTest {
        monotonic.stored = clock.elapsedRealtimeMs() - 10_000

        newStarter(newEngine()).start()

        assertEquals(0, serviceStarts)
        assertEquals(TimerStatus.IDLE, newEngine().state.value.status)
    }

    @Test
    fun `monotoner wert wird nach jedem start aktualisiert`() = runTest {
        val engine = newEngine()
        newStarter(engine).start()
        assertEquals(clock.elapsedRealtimeMs(), monotonic.stored)
        assertTrue(monotonic.stored!! >= 50_000)
    }
}
