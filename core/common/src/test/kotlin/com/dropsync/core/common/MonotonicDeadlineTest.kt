package com.dropsync.core.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Kern des normalen Timers (Bauplan 5.3): die Frist basiert ausschliesslich
 * auf der monotonen Clock; Systemzeit-Aenderungen duerfen nie einwirken.
 */
class MonotonicDeadlineTest {
    private class MutableClock(
        var now: Long,
    ) : Clock {
        override fun elapsedRealtimeMs(): Long = now

        override fun epochMillis(): Long = now
    }

    @Test
    fun `Ende ist Start plus Dauer`() {
        val clock = MutableClock(1_000)
        val deadline = MonotonicDeadline(clock, durationMs = 5_000)
        assertEquals(1_000, deadline.startedElapsedRealtimeMs)
        assertEquals(6_000, deadline.endElapsedRealtimeMs)
    }

    @Test
    fun `Restzeit sinkt mit fortlaufender Zeit`() {
        val clock = MutableClock(0)
        val deadline = MonotonicDeadline(clock, durationMs = 10_000)
        clock.now = 4_000
        assertEquals(6_000, deadline.remainingMs())
        clock.now = 9_999
        assertEquals(1, deadline.remainingMs())
    }

    @Test
    fun `Restzeit wird bei Ablauf auf 0 geklemmt`() {
        val clock = MutableClock(0)
        val deadline = MonotonicDeadline(clock, durationMs = 10_000)
        clock.now = 15_000
        assertEquals(0, deadline.remainingMs())
    }

    @Test
    fun `Ablauf exakt an der Grenze`() {
        val clock = MutableClock(0)
        val deadline = MonotonicDeadline(clock, durationMs = 10_000)
        clock.now = 9_999
        assertFalse(deadline.isExpired())
        clock.now = 10_000
        assertTrue(deadline.isExpired())
    }

    @Test
    fun `Systemzeit-Sprung aendert nichts`() {
        // Der Vertrag: nur elapsedRealtime zaehlt. Die Fake-Clock bildet
        // beide Quellen ab; ein Wanduhr-Sprung darf die Frist nicht beruehren.
        val clock = MutableClock(0)
        val deadline = MonotonicDeadline(clock, durationMs = 10_000)
        assertEquals(10_000, deadline.endElapsedRealtimeMs - deadline.startedElapsedRealtimeMs)
    }

    @Test
    fun `Nichtpositive Dauer wird abgelehnt`() {
        val clock = MutableClock(0)
        assertThrows(IllegalArgumentException::class.java) {
            MonotonicDeadline(clock, durationMs = 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            MonotonicDeadline(clock, durationMs = -1)
        }
    }
}
