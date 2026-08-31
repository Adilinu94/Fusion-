package com.dropsync.domain.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

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

    // --- Gate: MixConfidence ---

    @Test
    fun `klarer beat passiert das bpm-gate`() {
        val tempo = TempoAccumulator(sampleRateHz = 44_100)
        repeat(44_100 * 15) { index ->
            val positionInBeat = index % (44_100 / 2)
            tempo.accept(if (positionInBeat < 44_100 / 20) 1.0 else 0.0)
        }
        val estimate = requireNotNull(tempo.finishEstimate())

        assertEquals(
            estimate.bpm,
            MixConfidence.acceptBpm(estimate.bpm, estimate.confidence),
        )
    }

    @Test
    fun `weisses rauschen liefert kein bpm durch das gate`() {
        // Der Akkumulator liefert einen Wert (das ist das Problem);
        // erst das Gate erkennt ihn als unbrauchbar.
        val random = Random(seed = 7)
        val tempo = TempoAccumulator(sampleRateHz = 44_100)
        repeat(44_100 * 15) { tempo.accept(random.nextDouble() * 2 - 1) }
        val estimate = requireNotNull(tempo.finishEstimate())

        assertNotNull("Akkumulator liefert weiterhin einen Rohwert", estimate.bpm)
        assertNull(
            "Rauschen darf nicht als BPM durchkommen (confidence=${estimate.confidence})",
            MixConfidence.acceptBpm(estimate.bpm, estimate.confidence),
        )
    }

    @Test
    fun `dreiklang passiert das key-gate`() {
        val chroma = ChromaAccumulator(sampleRateHz = 44_100)
        val frequencies = listOf(220.0, 261.63, 329.63) // A-Moll
        repeat(44_100 * 12) { index ->
            chroma.accept(frequencies.sumOf { sin(2 * PI * it * index / 44_100) } / 3.0)
        }
        val estimate = requireNotNull(chroma.finishEstimate())

        assertEquals(
            estimate.camelotKey,
            MixConfidence.acceptKey(estimate.camelotKey, estimate.confidence),
        )
    }

    @Test
    fun `weisses rauschen liefert keinen key durch das gate`() {
        val random = Random(seed = 7)
        val chroma = ChromaAccumulator(sampleRateHz = 44_100)
        repeat(44_100 * 12) { chroma.accept(random.nextDouble() * 2 - 1) }
        val estimate = requireNotNull(chroma.finishEstimate())

        assertNull(
            "Rauschen darf nicht als Tonart durchkommen (confidence=${estimate.confidence})",
            MixConfidence.acceptKey(estimate.camelotKey, estimate.confidence),
        )
    }

    @Test
    fun `fehlende konfidenz gilt als unsicher`() {
        // Alt-Zeilen aus DB v7 (vor bpm_confidence) haben null. Sie duerfen
        // nicht als "sicher" gelten, nur weil die Spalte leer ist.
        assertNull(MixConfidence.acceptBpm(bpm = 128f, confidence = null))
        assertNull(MixConfidence.acceptKey(camelotKey = "8A", confidence = null))
    }

    @Test
    fun `kein wert bleibt null unabhaengig von der konfidenz`() {
        assertNull(MixConfidence.acceptBpm(bpm = null, confidence = 1f))
        assertNull(MixConfidence.acceptKey(camelotKey = null, confidence = 1f))
    }

    @Test
    fun `genau auf der schwelle wird akzeptiert`() {
        assertEquals(
            128f,
            MixConfidence.acceptBpm(128f, MixConfidence.MIN_BPM_CONFIDENCE),
        )
        assertEquals(
            "8A",
            MixConfidence.acceptKey("8A", MixConfidence.MIN_KEY_CONFIDENCE),
        )
    }

    @Test
    fun `chroma-korrelation eines flachen chromagramms ist null`() {
        // Regressionsschutz fuer den Vorzeichenfehler: ohne Zentrierung
        // erreichte ein flaches (rauschartiges) Chromagramm 0,96 und lag
        // damit ueber echten Dreiklaengen. Ein Dauerton auf allen
        // Halbtoenen gleichzeitig ist der Grenzfall.
        val chroma = ChromaAccumulator(sampleRateHz = 44_100)
        // Alle 12 Halbtoene der Oktave ueber A3 gleich laut: maximal
        // untonal, obwohl voller Energie.
        val frequencies = (0 until 12).map { 220.0 * Math.pow(2.0, it / 12.0) }
        repeat(44_100 * 12) { index ->
            chroma.accept(frequencies.sumOf { sin(2 * PI * it * index / 44_100) } / 12.0)
        }
        val estimate = requireNotNull(chroma.finishEstimate())

        assertNull(
            "flaches Chromagramm darf keine Tonart ergeben (confidence=${estimate.confidence})",
            MixConfidence.acceptKey(estimate.camelotKey, estimate.confidence),
        )
    }
}
