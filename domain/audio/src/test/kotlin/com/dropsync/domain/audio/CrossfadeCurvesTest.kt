package com.dropsync.domain.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Equal-Power-Eigenschaften der Crossfade-Kurven (Plan Phase 4). */
class CrossfadeCurvesTest {
    @Test
    fun `randwerte sind exakt`() {
        assertEquals(0.0, CrossfadeCurves.fadeInGain(0.0), 1e-12)
        assertEquals(1.0, CrossfadeCurves.fadeInGain(1.0), 1e-12)
        assertEquals(1.0, CrossfadeCurves.fadeOutGain(0.0), 1e-12)
        assertEquals(0.0, CrossfadeCurves.fadeOutGain(1.0), 1e-9)
    }

    @Test
    fun `summenleistung ist ueber den gesamten verlauf konstant`() {
        var t = 0.0
        while (t <= 1.0) {
            val inGain = CrossfadeCurves.fadeInGain(t)
            val outGain = CrossfadeCurves.fadeOutGain(t)
            assertEquals("t=$t", 1.0, inGain * inGain + outGain * outGain, 1e-9)
            t += 0.01
        }
    }

    @Test
    fun `kurven sind monoton und eingaben werden begrenzt`() {
        var previousIn = -1.0
        var previousOut = 2.0
        var t = 0.0
        while (t <= 1.0) {
            val inGain = CrossfadeCurves.fadeInGain(t)
            val outGain = CrossfadeCurves.fadeOutGain(t)
            assertTrue(inGain >= previousIn)
            assertTrue(outGain <= previousOut)
            previousIn = inGain
            previousOut = outGain
            t += 0.05
        }
        // Ausserhalb 0..1 wird geklemmt statt zu ueberschwingen.
        assertEquals(0.0, CrossfadeCurves.fadeInGain(-3.0), 1e-12)
        assertEquals(1.0, CrossfadeCurves.fadeInGain(9.0), 1e-12)
    }
}
