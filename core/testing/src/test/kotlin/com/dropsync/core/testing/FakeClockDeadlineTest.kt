package com.dropsync.core.testing

import com.dropsync.core.common.MonotonicDeadline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Abnahmekriterium Schritt 2: "Ein Test beweist, dass eine Fake-Clock den
 * Ablauf eines Use Cases ohne Echtzeit steuert."
 *
 * Zusaetzlich abgesichert: Systemzeitaenderungen beeinflussen die monotone
 * Frist nicht (Bauplan 5.3), und ein Reboot ist an der ruecklaeufigen
 * monotonen Uhr erkennbar (Schritt 7.9).
 */
class FakeClockDeadlineTest {
    @Test
    fun `deadline laeuft ausschliesslich ueber die fake clock ab`() {
        val clock = FakeClock(initialElapsedRealtimeMs = 10_000L)
        val deadline = MonotonicDeadline(clock, durationMs = 90_000L)

        assertEquals(90_000L, deadline.remainingMs())
        assertFalse(deadline.isExpired())

        clock.advanceBy(89_999L)
        assertEquals(1L, deadline.remainingMs())
        assertFalse(deadline.isExpired())

        clock.advanceBy(1L)
        assertEquals(0L, deadline.remainingMs())
        assertTrue(deadline.isExpired())
    }

    @Test
    fun `systemzeitaenderung beeinflusst die monotone frist nicht`() {
        val clock = FakeClock(initialElapsedRealtimeMs = 5_000L, initialEpochMillis = 1_700_000_000_000L)
        val deadline = MonotonicDeadline(clock, durationMs = 60_000L)

        // Nutzerin stellt die Systemuhr eine Stunde vor.
        clock.setEpochMillis(1_700_000_000_000L + 3_600_000L)

        assertEquals(60_000L, deadline.remainingMs())
        assertFalse(deadline.isExpired())
    }

    @Test
    fun `reboot ist an ruecklaeufiger monotoner uhr erkennbar`() {
        val clock = FakeClock(initialElapsedRealtimeMs = 500_000L)
        val lastPersistedElapsed = clock.elapsedRealtimeMs()

        clock.simulateReboot()

        // Schritt 7.9: aktueller Wert kleiner als letzter gespeicherter Wert
        // bedeutet DEVICE_REBOOT_OR_UNKNOWN_CLOCK.
        assertTrue(clock.elapsedRealtimeMs() < lastPersistedElapsed)
    }

    @Test
    fun `remaining faellt nie unter null`() {
        val clock = FakeClock()
        val deadline = MonotonicDeadline(clock, durationMs = 1_000L)

        clock.advanceBy(5_000L)

        assertEquals(0L, deadline.remainingMs())
    }
}
