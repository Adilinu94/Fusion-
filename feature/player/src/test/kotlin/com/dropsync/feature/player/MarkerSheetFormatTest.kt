package com.dropsync.feature.player

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * P2-21 (UI-Handbuch 14.4): Die Markerzeit im Sheet zeigt Millisekunden
 * ("01:18.420"), damit die Feinkorrektur in 10-ms-Schritten sichtbar wird.
 */
class MarkerSheetFormatTest {
    @Test
    fun `markerzeit zeigt millisekunden`() {
        assertEquals("01:18.420", formatMarkerTimeMs(78_420L))
        assertEquals("00:00.000", formatMarkerTimeMs(0L))
        assertEquals("03:00.005", formatMarkerTimeMs(180_005L))
    }

    @Test
    fun `markerzeit traegt stunden nur bei bedarf`() {
        assertEquals("1:02:03.004", formatMarkerTimeMs(3_723_004L))
    }

    @Test
    fun `negative werte werden geklemmt`() {
        assertEquals("00:00.000", formatMarkerTimeMs(-5L))
    }
}
