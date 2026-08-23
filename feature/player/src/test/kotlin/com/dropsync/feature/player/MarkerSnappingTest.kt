package com.dropsync.feature.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Beat-Snap-Fenster und Randfaelle des Marker-Snappings. */
class MarkerSnappingTest {
    @Test
    fun `Position nahe Beat snappt auf den Beat`() {
        // 120 BPM => Beat alle 500 ms; 1010 ms liegt 10 ms neben Beat 2 (1000).
        assertEquals(1000L, MarkerSnapping.snapToBeat(1010L, 120f))
    }

    @Test
    fun `Position ausserhalb des Fensters bleibt frei`() {
        // 60 BPM => Beat alle 1000 ms; 400 ms neben Beat 0 => kein Snap
        // (Fenster 250 ms). Bei 120 BPM waere jede Position <= 250 ms vom
        // naechsten Beat entfernt — dort snappt definitionsgemaess alles.
        assertNull(MarkerSnapping.snapToBeat(400L, 60f))
    }

    @Test
    fun `exakte Beat-Position bleibt unverändert`() {
        assertEquals(1500L, MarkerSnapping.snapToBeat(1500L, 120f))
    }

    @Test
    fun `ohne BPM gibt es keinen Snap`() {
        assertNull(MarkerSnapping.snapToBeat(1010L, null))
    }

    @Test
    fun `unsinnige BPM-Werte werden abgewiesen`() {
        assertNull(MarkerSnapping.snapToBeat(1010L, 0f))
        assertNull(MarkerSnapping.snapToBeat(1010L, 500f))
    }

    @Test
    fun `Snap respektiert den Trackanfang`() {
        // Beat -1 bzw. 0: 30 ms liegen im Fenster um 0 => snap auf 0.
        assertEquals(0L, MarkerSnapping.snapToBeat(30L, 120f))
    }
}
