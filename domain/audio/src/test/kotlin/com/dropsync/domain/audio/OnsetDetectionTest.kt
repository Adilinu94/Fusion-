package com.dropsync.domain.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Verifikation Marker/Waveform-Plan Phase 5: Novelty-Funktion und
 * Peak-Picking gegen synthetische Fixtures (steiler Sprung an bekannter
 * Position, Stille, gleichmaessiges Rauschen ohne Kandidaten).
 */
class OnsetDetectionTest {
    private val windowMs = 25L

    @Test
    fun `steiler Sprung an bekannter Position wird gefunden`() {
        // 200 Fenster leise (0.05), ab Fenster 120 laut (0.9).
        val energy = List(120) { 0.05 } + List(80) { 0.9 }

        val onsets = OnsetDetection.detectOnsets(energy, windowMs)

        assertEquals(1, onsets.size)
        assertEquals(120 * windowMs, onsets.single())
    }

    @Test
    fun `stille liefert keine Kandidaten`() {
        val onsets = OnsetDetection.detectOnsets(List(400) { 0.0 }, windowMs)

        assertTrue(onsets.isEmpty())
    }

    @Test
    fun `gleichmaessiges Rauschen liefert keine Kandidaten`() {
        // Deterministisches Rauschen um 0.5 mit kleiner Amplitude: keine
        // Novelty ragt ueber Mittel + k * Stdabw des eigenen Umfelds.
        val random = Random(seed = 42)
        val energy = List(400) { 0.5 + random.nextDouble(-0.02, 0.02) }

        val onsets = OnsetDetection.detectOnsets(energy, windowMs)

        assertTrue("unerwartete Kandidaten: $onsets", onsets.isEmpty())
    }

    @Test
    fun `mindestabstand verhindert Kandidaten je Beat`() {
        // Zwei Spruenge im Abstand von 40 Fenstern (1 s) < 5 s Mindestabstand:
        // nur der staerkere zaehlt.
        val energy =
            buildList {
                repeat(100) { add(0.05) }
                repeat(40) { add(0.4) } // schwaecherer Sprung (+0.35) bei Fenster 100
                repeat(260) { add(0.95) } // staerkerer Sprung (+0.55) bei Fenster 140
            }

        val onsets = OnsetDetection.detectOnsets(energy, windowMs)

        assertEquals(1, onsets.size)
        assertEquals(140 * windowMs, onsets.single())
    }

    @Test
    fun `hoechstens maxCandidates Kandidaten zeitlich sortiert`() {
        // Fuenf deutliche Spruenge im Abstand von 400 Fenstern (10 s).
        val energy =
            buildList {
                var level = 0.05
                repeat(5) {
                    repeat(400) { add(level) }
                    level += 0.18
                }
            }

        val onsets = OnsetDetection.detectOnsets(energy, windowMs, maxCandidates = 3)

        assertEquals(3, onsets.size)
        assertEquals(onsets.sorted(), onsets)
        onsets.forEach { onset ->
            // Jeder Kandidat liegt auf einer der Sprungpositionen.
            assertEquals(0L, onset % (400 * windowMs))
        }
    }

    @Test
    fun `leere oder zu kurze Eingabe liefert keine Kandidaten`() {
        assertTrue(OnsetDetection.detectOnsets(emptyList(), windowMs).isEmpty())
        assertTrue(OnsetDetection.detectOnsets(listOf(0.5), windowMs).isEmpty())
    }
}
