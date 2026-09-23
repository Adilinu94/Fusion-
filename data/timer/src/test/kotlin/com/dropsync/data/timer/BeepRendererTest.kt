package com.dropsync.data.timer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Vorgerenderte Countdown-Pieps (Design Phase 7): Dauer, Amplitude,
 * Huellkurve gegen Knacksen und die Frequenz-Sprungweite 880 -> 1760 Hz.
 * Der Abspiel-Pfad (AudioTrack + Release-Thread) bleibt bewusst
 * ungetestet — der Thread ist ohne echten Audio-Hardware-Pfad nicht
 * deterministisch.
 */
class BeepRendererTest {
    private fun shortBeep() = CountdownBeepPlayer.renderBeep(880.0, 120, SAMPLE_RATE)

    private fun goBeep() = CountdownBeepPlayer.renderBeep(1_760.0, 400, SAMPLE_RATE)

    /** Frequenz-Schaetzung ueber Vorzeichenwechsel (Nulldurchgaenge). */
    private fun estimateFrequencyHz(
        pcm: ShortArray,
        sampleRate: Int = SAMPLE_RATE,
    ): Double {
        val ramp = (pcm.size * 0.05).toInt().coerceAtLeast(1)
        var crossings = 0
        for (i in (ramp + 1) until pcm.size - ramp) {
            val previous = pcm[i - 1].toInt()
            val current = pcm[i].toInt()
            val rising = previous < 0 && current >= 0
            val falling = previous >= 0 && current < 0
            if (rising || falling) crossings++
        }
        val samples = (pcm.size - ramp) - (ramp + 1)
        return crossings * sampleRate / 2.0 / samples
    }

    @Test
    fun `Beeps haben die exakte Dauer in Samples`() {
        // 44100 Hz * 120 ms / 1000 = 5292; * 400 ms / 1000 = 17640.
        assertEquals(5_292, shortBeep().size)
        assertEquals(17_640, goBeep().size)
    }

    @Test
    fun `Huellkurve startet und endet bei null gegen Knacksen`() {
        val pcm = shortBeep()
        assertEquals(0, pcm.first().toInt())
        assertEquals(0, pcm.last().toInt())

        val middleMax = pcm.drop(265).maxOf { abs(it.toInt()) }
        // Sample 10 liegt in der 5%-Rampe (264 Samples) => ~4% Amplitude.
        assertTrue(abs(pcm[10].toInt()) < middleMax / 5)
        assertTrue(abs(pcm[pcm.size - 11].toInt()) < middleMax / 5)
    }

    @Test
    fun `Amplitude bleibt unter der vorgesehenen Grenze und faellt in der Mitte voll aus`() {
        val max = shortBeep().maxOf { abs(it.toInt()) }
        // 0.6 * Short.MAX_VALUE = 19660.
        assertTrue("max=$max ueberschreitet die Amplitude 0.6", max <= 19_660)
        assertTrue("max=$max erreicht die Mitte nicht", max > 16_000)
    }

    @Test
    fun `shortBeep piept mit 880 Hz`() {
        val frequency = estimateFrequencyHz(shortBeep())
        assertTrue("Frequenz $frequency Hz ausserhalb der Toleranz um 880", abs(frequency - 880.0) < 40.0)
    }

    @Test
    fun `goBeep piept eine Oktave hoeher mit 1760 Hz`() {
        val frequency = estimateFrequencyHz(goBeep())
        assertTrue("Frequenz $frequency Hz ausserhalb der Toleranz um 1760", abs(frequency - 1_760.0) < 60.0)
    }

    @Test
    fun `unterschiedliche Frequenzen trennen Go von Countdown-Beep`() {
        val shortHz = estimateFrequencyHz(shortBeep())
        val goHz = estimateFrequencyHz(goBeep())
        assertTrue("Go muss klar ueber dem Countdown-Beep liegen", goHz > shortHz * 1.7)
    }

    private companion object {
        const val SAMPLE_RATE = 44_100
    }
}
