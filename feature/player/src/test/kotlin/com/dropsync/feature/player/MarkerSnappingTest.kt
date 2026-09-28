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
        // 120 BPM => Beat 500 ms, Fenster minOf(150, 125) = 125 ms.
        // Gemessene Phase 120 ms: Raster bei 120, 620, 1120 ...
        assertEquals(120L, MarkerSnapping.snapToBeat(130L, 120f, downbeatOffsetMs = 120L))
        // Gegenprobe ohne Offset: dasselbe Ereignis rastet auf 0.
        //
        // 2026-09-27 (Befund 10.3): hier stand frueher `130L`, also 130 ms
        // Abstand zum Beat bei 0. Das liegt **ausserhalb** des neuen
        // Fensters (125 ms) und rastet deshalb nicht mehr. Der Test
        // beschrieb damit das alte Verhalten ("Fenster = halber Beat")
        // als gewuenschte Eigenschaft. Auf 120 ms geaendert, damit er
        // prueft, dass der **Offset** das Raster verschiebt — nicht die
        // Fensterbreite.
        assertEquals(0L, MarkerSnapping.snapToBeat(120L, 120f, downbeatOffsetMs = 0L))
        // Und die Gegenprobe bleibt: 130 ms sind zu weit weg, es rastet nicht.
        assertNull(MarkerSnapping.snapToBeat(130L, 120f, downbeatOffsetMs = 0L))
    }

    @Test
    fun `negativer Offset wird auf 0 geklemmt`() {
        assertEquals(0L, MarkerSnapping.snapToBeat(30L, 120f, downbeatOffsetMs = -500L))
    }

    /**
     * 2026-09-27, Befund 10.3: der Test pruefte, dass **jede** Position
     * innerhalb eines halben Beats rastet, und feierte das als
     * Begrenzung. Das war das Gegenteil: bei 150 BPM waren das 200 ms,
     * und bei 120 BPM sogar 250 ms — **jede** Position im Umkreis rastete.
     * Das ist keine Bemessung, das ist erzwungene Rasterung, und sie
     * widprach dem eigenen KDoc ("nie gewaltsam").
     *
     * Jetzt gilt `minOf(150, beatMs / 4)`. Der Test prueft die **echte**
     * Zusicherung: wenn ueberhaupt geraestet wird, dann nur ehrlich nahe
     * am Beat.
     */
    @Test
    fun `rasten nur ehrlich nahe am Beat`() {
        for (bpm in intArrayOf(90, 120, 128, 150, 174)) {
            val beatMs = 60_000L / bpm
            val window = minOf(MarkerSnapping.SNAP_WINDOW_MS, beatMs / 4)
            for (position in 0L..(beatMs * 2) step 5L) {
                val snapped = MarkerSnapping.snapToBeat(position, bpm.toFloat(), downbeatOffsetMs = 0L)
                if (snapped == null) continue
                val shift = abs(snapped - position)
                assertTrue(
                    "Rastung zu weit bei $bpm BPM: $position -> $snapped (Fenster $window)",
                    shift <= window,
                )
            }
        }
    }

    /**
     * Gegenprobe zum vorigen Test: bei schnellen Tempi rastet **nicht**
     * jede Position. Bei 174 BPM ist das Fenster 86 ms, bei 120 BPM
     * 125 ms — ein Drittel des Beats. Der Nutzer muss den Marker frei
     * setzen koennen.
     */
    @Test
    fun `schnelle Tempi rasten nicht jede Position`() {
        var snappedCount = 0
        var totalCount = 0
        for (position in 0L..352L step 1L) {
            totalCount++
            if (MarkerSnapping.snapToBeat(position, 174f, downbeatOffsetMs = 0L) != null) {
                snappedCount++
            }
        }
        // 352 ms bei 174 BPM = 345-ms-Beat, also gut ein Beat. Das Fenster
        // ist 86 ms je Seite, also 172 ms von 352 ms = 49 Prozent. Deutlich
        // unter "jede Position" (100 Prozent), deutlich ueber 0.
        assertTrue(
            "Erwartet ~50 % Rasterung, war $snappedCount von $totalCount",
            snappedCount in (totalCount * 3 / 10)..(totalCount * 7 / 10),
        )
    }

    /**
     * 2026-09-27, Befund 10.3: vor dem Raster-Offset gibt es keinen Beat.
     * `roundToInt()` rundet dort away-from-zero und wuerde eine Position
     * von 5 ms auf den Beat bei 140 ms ziehen — ein Snap auf eine Stelle,
     * an der nie etwas war.
     */
    @Test
    fun `position vor dem raster-offset rastet nicht`() {
        assertNull(
            MarkerSnapping.snapToBeat(5L, 128f, downbeatOffsetMs = 140L),
        )
    }
}
