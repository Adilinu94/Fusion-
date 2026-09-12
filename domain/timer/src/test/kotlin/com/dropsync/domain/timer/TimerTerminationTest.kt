package com.dropsync.domain.timer

import com.dropsync.core.testing.FakeClock
import com.dropsync.core.testing.FakeTimerSnapshotStore
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Tests fuer [TimerTermination] (Ausbauplan A1): die Abbruch-Reihenfolge
 * Engine stoppen -> Snapshot loeschen -> Uhr stempeln, ohne Main-Blockade.
 * Der Kill-Race-Fall (Phase 10.3) ist der vierte Test: Nach terminate() darf
 * kein Recovery mehr restaurieren.
 */
class TimerTerminationTest {
    private val clock = FakeClock(initialElapsedRealtimeMs = 50_000, initialEpochMillis = 1_000)
    private val store = FakeTimerSnapshotStore()
    private val calls = mutableListOf<String>()
    private val engine = TimerEngine(clock, NoOpCueOutput())
    private val termination =
        TimerTermination(
            engine,
            clearSnapshot = {
                calls += "clear"
                store.clear()
            },
            stampMonotonicClock = { calls += "stamp" },
        )

    @Test
    fun `terminate bricht laufenden Timer ab und leert den Store in Reihenfolge`() =
        runTest {
            engine.start(TimerMode.REST, 60_000)
            termination.terminate()
            assertEquals(TimerStatus.IDLE, engine.state.value.status)
            assertEquals(1, store.clearCount)
            assertEquals(listOf("clear", "stamp"), calls)
        }

    @Test
    fun `terminate ohne laufenden Timer ist harmlos`() =
        runTest {
            termination.terminate()
            assertEquals(TimerStatus.IDLE, engine.state.value.status)
            assertEquals(1, store.clearCount)
            assertEquals(listOf("clear", "stamp"), calls)
        }

    @Test
    fun `restartFresh startet frischen REST-Timer`() =
        runTest {
            engine.start(TimerMode.REST, 60_000)
            termination.restartFresh(15_000)
            assertEquals(TimerStatus.RUNNING, engine.state.value.status)
            assertEquals(
                TimerMode.REST,
                engine.state.value.session
                    ?.mode,
            )
            assertEquals(15_000, engine.state.value.remainingMs)
            assertEquals(1, store.clearCount)
        }

    @Test
    fun `nach terminate restauriert kein Recovery mehr (Kill-Race Phase 10_3)`() =
        runTest {
            engine.start(TimerMode.REST, 60_000)
            store.save(checkNotNull(engine.snapshot()))
            termination.terminate()
            assertFalse(DefaultRestTimerRecovery(store).recover(engine))
            assertEquals(TimerStatus.IDLE, engine.state.value.status)
        }
}
