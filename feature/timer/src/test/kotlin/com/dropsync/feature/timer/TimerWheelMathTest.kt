package com.dropsync.feature.timer

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * B2-Rechenlogik des Stellrads: Zerlegung und Klemmung sind pure Funktionen
 * und damit ohne Compose testbar.
 */
class TimerWheelMathTest {
    @Test
    fun `split zerlegt 90 Sekunden in 0 1 30`() {
        assertEquals(Triple(0, 1, 30), splitTimerSeconds(90))
    }

    @Test
    fun `split zerlegt 3661 Sekunden in 1 1 1`() {
        assertEquals(Triple(1, 1, 1), splitTimerSeconds(3_661))
    }

    @Test
    fun `split klemmt negative und zu grosse Werte`() {
        assertEquals(Triple(0, 0, 0), splitTimerSeconds(-5))
        assertEquals(Triple(23, 59, 59), splitTimerSeconds(99_999))
    }

    @Test
    fun `shift rechnet Minuten und Stunden hoch und runter`() {
        assertEquals(150, shiftTimerSeconds(90, 60))
        assertEquals(30, shiftTimerSeconds(90, -60))
        assertEquals(3_690, shiftTimerSeconds(90, 3_600))
    }

    @Test
    fun `shift klemmt an 0 und 23 59 59`() {
        assertEquals(0, shiftTimerSeconds(0, -60))
        assertEquals(0, shiftTimerSeconds(0, -3_600))
        assertEquals(MAX_TIMER_SECONDS, shiftTimerSeconds(MAX_TIMER_SECONDS, 15))
        assertEquals(MAX_TIMER_SECONDS, shiftTimerSeconds(MAX_TIMER_SECONDS, 3_600))
        assertEquals(MAX_TIMER_SECONDS, MAX_TIMER_SECONDS)
    }
}
