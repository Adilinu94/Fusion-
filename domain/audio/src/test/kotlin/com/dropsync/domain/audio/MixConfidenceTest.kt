package com.dropsync.domain.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Offtrack Phase 8: BPM/Key-Confidence bleibt 0..1 und konsistent. */
class MixConfidenceTest {
    @Test
    fun `tempo estimate konfidenz liegt zwischen null und eins`() {
        val tempo = TempoAccumulator(sampleRateHz = 44_100)
        // 12 s klarer 120-BPM-Beat-Train: alle 500 ms ein 50-ms-Burst.
        repeat(44_100 * 12) { index ->
            val positionInBeat = index % (44_100 / 2)
            val sample = if (positionInBeat < 44_100 / 20) 1.0 else 0.0
            tempo.accept(sample)
        }
        val estimate = tempo.finishEstimate()
        assertNotNull(estimate)
        assertTrue(estimate!!.confidence in 0f..1f)
        // finish() bleibt die Rueckwaertskompatibilitaet zu finishEstimate().
        assertEquals(estimate.bpm, tempo.finish()!!, 1e-4f)
    }

    @Test
    fun `zu wenig signal liefert keinen estimate`() {
        val tempo = TempoAccumulator(sampleRateHz = 44_100)
        repeat(100) { tempo.accept(0.1) }
        assertNull(tempo.finishEstimate())
    }
}
