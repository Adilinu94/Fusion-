package com.dropsync.feature.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Beat-Snap-Fenster und Randfaelle des Marker-Snappings. B4/RC-22: Das
 * Raster hat eine gemessene Phase; ohne Offset rastet nichts.
 */
class MarkerSnappingTest {
    @Test
    fun `Position nahe Beat snappt auf den Beat`() {
        // 120 BPM => Beat alle 500 ms; 1010 ms liegt 10 ms neben Beat 2 (1000).
        assertEquals(1000L, MarkerSnapping.snapToBeat(1010L, 120f, downbeatOffsetMs = 0L))
    }

    @Test
    fun `Position ausserhalb des Fensters bleibt frei`() {
        // 60 BPM => Beat alle 1000 ms; 400 ms neben Beat 0 => kein Snap
        // (Fenster 250 ms). Bei 120 BPM waere jede Position <= 250 ms vom
        // naechsten Beat entfernt — dort snappt definitionsgemaess alles.
        assertNull(MarkerSnapping.snapToBeat(400L, 60f, downbeatOffsetMs = 0L))
    }

    @Test
    fun `exakte Beat-Position bleibt unverändert`() {
        assertEquals(1500L, MarkerSnapping.snapToBeat(1500L, 120f, downbeatOffsetMs = 0L))
    }

    @Test
    fun `ohne BPM gibt es keinen Snap`() {
        assertNull(MarkerSnapping.snapToBeat(1010L, null, downbeatOffsetMs = 0L))
    }

    @Test
    fun `unsinnige BPM-Werte werden abgewiesen`() {
        assertNull(MarkerSnapping.snapToBeat(1010L, 0f, downbeatOffsetMs = 0L))
        assertNull(MarkerSnapping.snapToBeat(1010L, 500f, downbeatOffsetMs = 0L))
    }

    @Test
    fun `Snap respektiert den Trackanfang`() {
        // Beat -1 bzw. 0: 30 ms liegen im Fenster um 0 => snap auf 0.
        assertEquals(0L, MarkerSnapping.snapToBeat(30L, 120f, downbeatOffsetMs = 0L))
    }

    @Test
    fun `ohne gemessenen Offset gibt es keinen Snap`() {
        // Ein geratenes 0-ms-Raster kann die Position um bis zu einen
        // halben Beat verschieben — kein Snap ist besser als ein falsches.
        assertNull(MarkerSnapping.snapToBeat(1010L, 120f, downbeatOffsetMs = null))
    }

    @Test
    fun `Downbeat-Offset verschiebt das Raster`() {
        // 120 BPM, gemessene Phase 120 ms: Raster bei 120, 620, 1120 ...
        assertEquals(120L, MarkerSnapping.snapToBeat(130L, 120f, downbeatOffsetMs = 120L))
        // Gegenprobe ohne Offset: dasselbe Ereignis rastet auf 0.
        assertEquals(0L, MarkerSnapping.snapToBeat(130L, 120f, downbeatOffsetMs = 0L))
    }

    @Test
    fun `negativer Offset wird auf 0 geklemmt`() {
        assertEquals(0L, MarkerSnapping.snapToBeat(30L, 120f, downbeatOffsetMs = -500L))
    }

    @Test
    fun `Fenster ist hoechstens ein halber Beat`() {
        // 150 BPM => Beat 400 ms, halber Beat 200 ms < SNAP_WINDOW_MS.
        // Keine Rastung verschiebt weiter als einen halben Beat.
        for (position in 0L..800L step 10L) {
            val snapped = MarkerSnapping.snapToBeat(position, 150f, downbeatOffsetMs = 0L)
            assertNotNull("Position $position sollte im halben Beat rasten", snapped)
            assertTrue(
                "Verschiebung zu gross: $position -> $snapped",
                abs(requireNotNull(snapped) - position) <= 200L,
            )
        }
    }
}
