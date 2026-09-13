package com.dropsync.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** C4: Ducking-Stufen sind ein fester, persistierter Wertebereich (Schritt 8.4). */
class DuckingPercentTest {
    @Test
    fun `erlaubt sind nur 0 50 und 100`() {
        assertTrue(DuckingPercent.isValid(DuckingPercent.NONE))
        assertTrue(DuckingPercent.isValid(DuckingPercent.HALF))
        assertTrue(DuckingPercent.isValid(DuckingPercent.FULL))
        assertEquals(setOf(0, 50, 100), DuckingPercent.allowed)
    }

    @Test
    fun `andere Werte werden abgelehnt`() {
        assertFalse(DuckingPercent.isValid(-1))
        assertFalse(DuckingPercent.isValid(25))
        assertFalse(DuckingPercent.isValid(99))
        assertFalse(DuckingPercent.isValid(101))
    }
}
