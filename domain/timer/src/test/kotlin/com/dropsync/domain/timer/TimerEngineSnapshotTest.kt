package com.dropsync.domain.timer

import com.dropsync.core.testing.FakeClock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Zeichnet keine Ausgaben auf; snapshot/restore pruefen nur den Zustand. */
private class NoCueOutput : CueOutput {
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
 * Tests fuer den Kill-Fallback (Testinfrastruktur-Umbauplan Schritt 2, 5b):
 * `TimerEngine.snapshot()`/`restore()` muessen einen laufenden NORMAL/REST-
 * Timer nach einem simulierten App-/Service-Kill verlustfrei rehydrieren.
 */
class TimerEngineSnapshotTest {
    private val clock = FakeClock(initialElapsedRealtimeMs = 100_000, initialEpochMillis = 1_000)
    private val cues = NoCueOutput()
    private val engine = TimerEngine(clock, cues) { "session-1" }

    @Test
    fun `snapshot ist null bei idle`() {
        assertNull(engine.snapshot())
    }

    @Test
    fun `snapshot ist null bei dropsync`() {
        engine.startDropSync(requestedDurationMs = 60_000, markerPositionMs = 120_000)
        assertNull(engine.snapshot())
    }

    @Test
    fun `laufender timer wird gesnapshottet und identisch rehydriert`() {
        engine.start(TimerMode.REST, durationMs = 90_000)
        clock.advanceBy(30_000) // 60s Rest
        engine.evaluate()

        val snap = engine.snapshot()
        assertNotNull(snap)
        assertEquals(TimerStatus.RUNNING, snap!!.status)
        assertEquals(TimerMode.REST, snap.session.mode)

        // Kill: neue Engine auf derselben (monoton weiterlaufenden) Uhr.
        val engine2 = TimerEngine(clock, cues) { "session-2" }
        clock.advanceBy(10_000) // waerehrend des Kill vergehen 10s
        assertTrue(engine2.restore(snap))

        // Restzeit muss die Kill-Dauer reflektieren: 60s - 10s = 50s.
        engine2.evaluate()
        assertEquals(TimerStatus.RUNNING, engine2.state.value.status)
        assertEquals(50_000, engine2.state.value.remainingMs)
    }

    @Test
    fun `pausierter timer bleibt nach restore pausiert mit eingefrorener restzeit`() {
        engine.start(TimerMode.NORMAL, durationMs = 60_000)
        clock.advanceBy(15_000)
        engine.pause() // friert 45s ein

        val snap = engine.snapshot()!!
        assertEquals(TimerStatus.PAUSED, snap.status)
        assertEquals(45_000L, snap.pausedRemainingMs)

        val engine2 = TimerEngine(clock, cues) { "session-2" }
        clock.advanceBy(120_000) // Pause ueberlebt beliebig lange Kill-Zeit
        assertTrue(engine2.restore(snap))
        assertEquals(TimerStatus.PAUSED, engine2.state.value.status)
        assertEquals(45_000, engine2.state.value.remainingMs)
    }

    @Test
    fun `restore lehnt ab wenn bereits aktive sitzung laeuft`() {
        engine.start(TimerMode.REST, durationMs = 30_000)
        val snap = engine.snapshot()!!

        val engine2 = TimerEngine(clock, cues) { "s2" }
        engine2.start(TimerMode.REST, durationMs = 10_000)
        assertFalse(engine2.restore(snap)) // Konflikt: laeuft schon
    }

    @Test
    fun `restore lehnt abgelaufenes snapshot nicht ab sondern schliesst ab`() {
        engine.start(TimerMode.REST, durationMs = 20_000)
        val snap = engine.snapshot()!!

        val engine2 = TimerEngine(clock, cues) { "s2" }
        clock.advanceBy(25_000) // Timer waere waerend des Kill abgelaufen
        assertTrue(engine2.restore(snap))
        engine2.evaluate() // monotoner Abschluss
        assertEquals(TimerStatus.COMPLETED, engine2.state.value.status)
    }

    @Test
    fun `wanduhrsprung verfaelscht rehydrierten timer nicht`() {
        engine.start(TimerMode.REST, durationMs = 60_000)
        clock.advanceBy(10_000)
        val snap = engine.snapshot()!!

        val engine2 = TimerEngine(clock, cues) { "s2" }
        clock.setEpochMillis(9_999_999) // Systemzeit weit veraendert
        assertTrue(engine2.restore(snap))
        engine2.evaluate()
        // Monotone Uhr unberuehrt: Rest bleibt 50s.
        assertEquals(50_000, engine2.state.value.remainingMs)
    }
}
