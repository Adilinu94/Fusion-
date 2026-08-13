package com.dropsync.domain.timer

import com.dropsync.core.testing.FakeClock
import com.dropsync.core.testing.FakeTimerSnapshotStore
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private class SilentCueOutput : CueOutput {
    override fun speak(
        cueSessionId: String,
        secondsRemaining: Int,
    ) = Unit

    override fun haptic(cueSessionId: String) = Unit

    override fun countdownBeep(cueSessionId: String) = Unit

    override fun tone(cueSessionId: String) = Unit

    override fun stopAll(cueSessionId: String) = Unit
}

/**
 * Tests fuer [DefaultRestTimerRecovery] (Kill-Fallback 5b): die Entscheidung,
 * ob bei App-Start ein Timer rehydriert wird, ohne Android-Abhaengigkeit.
 */
class RestTimerRecoveryTest {
    private val clock = FakeClock(initialElapsedRealtimeMs = 50_000, initialEpochMillis = 1_000)
    private val store = FakeTimerSnapshotStore()
    private val recovery = DefaultRestTimerRecovery(store)
    private val engine = TimerEngine(clock, SilentCueOutput()) { "restored" }

    private fun runningSnapshot(durationMs: Long = 60_000): TimerSnapshot {
        val session =
            TimerSession(
                id = "s1",
                mode = TimerMode.REST,
                durationMs = durationMs,
                startedElapsedRealtimeMs = 0L,
                markerPositionMs = null,
                plannedCues = emptyList(),
            )
        return TimerSnapshot(
            session = session,
            status = TimerStatus.RUNNING,
            endElapsedRealtimeMs = clock.elapsedRealtimeMs() + 30_000,
            pausedRemainingMs = null,
        )
    }

    @Test
    fun `kein snapshot bedeutet nichts zu tun`() =
        runTest {
            assertFalse(recovery.recover(engine))
            assertEquals(TimerStatus.IDLE, engine.state.value.status)
        }

    @Test
    fun `vorhandenes snapshot rehydriert den timer und leert den store`() =
        runTest {
            store.save(runningSnapshot())
            assertTrue(recovery.recover(engine))
            assertEquals(TimerStatus.RUNNING, engine.state.value.status)
            // Store wurde bereinigt: zweiter Aufruf findet nichts mehr.
            assertFalse(recovery.recover(TimerEngine(clock, SilentCueOutput()) { "x" }))
        }

    @Test
    fun `dropsync snapshot wird verworfen und nicht rehydriert`() =
        runTest {
            val dropsync =
                runningSnapshot().copy(
                    session = runningSnapshot().session.copy(mode = TimerMode.DROPSYNC),
                )
            store.save(dropsync)
            assertFalse(recovery.recover(engine))
            assertEquals(TimerStatus.IDLE, engine.state.value.status)
            assertTrue(store.clearCount >= 1) // veralteter Zustand bereinigt
        }

    @Test
    fun `recovery bei laufender sitzung lehnt ab`() =
        runTest {
            engine.start(TimerMode.NORMAL, durationMs = 10_000)
            store.save(runningSnapshot())
            assertFalse(recovery.recover(engine)) // Konflikt: Engine nicht IDLE
            assertEquals(
                TimerMode.NORMAL,
                engine.state.value.session!!
                    .mode,
            )
        }
}
