package com.dropsync.feature.progress

import org.junit.Assert.assertEquals
import org.junit.Test

/** C2: Breakpoint-Entscheidung des Dashboard-Grids (pure Funktion). */
class DashboardColumnCountTest {
    @Test
    fun `Telefonbreite nutzt zwei Spalten`() {
        assertEquals(2, dashboardColumnCount(isExpanded = false, fontScale = 1f))
    }

    @Test
    fun `breites Fenster nutzt drei Spalten`() {
        assertEquals(3, dashboardColumnCount(isExpanded = true, fontScale = 1f))
    }

    @Test
    fun `doppelte Systemschrift erzwingt eine Spalte`() {
        assertEquals(1, dashboardColumnCount(isExpanded = false, fontScale = 2f))
        assertEquals(1, dashboardColumnCount(isExpanded = true, fontScale = 2f))
    }

    @Test
    fun `genau einfache Schrift bleibt zweispaltig`() {
        assertEquals(2, dashboardColumnCount(isExpanded = false, fontScale = 1.5f))
    }
}
