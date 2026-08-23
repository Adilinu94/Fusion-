package com.dropsync.domain.audio

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

/**
 * Verifikation Mix-Phase 1: Tempo- und Tonart-Schaetzung gegen
 * synthetische Signale. Oktavfehler (2x/0.5x) sind im Tempo-Fenster
 * ausdruecklich erlaubt; die Tests pruefen deshalb auf harmonisch
 * verwandte Werte statt auf exakte BPM.
 */
class MixAnalysisTest {
    // --- TempoAccumulator ---

    @Test
    fun `regelmaessige Impulse ergeben ein Tempo im gueltigen Fenster`() {
        // 120 BPM => Intervall 500 ms => 20 Fenster a 25 ms.
        val bpm = tempoFromImpulses(intervalWindows = 20, impulseCount = 40)

        assertNotNull(bpm)
        // 120 BPM oder Oktav-Harmonische (60/240) erwartet; Toleranz fuer
        // Fensterquantisierung.
        val folded = foldToRange(bpm!!.toDouble())
        assertTrue("unerwartetes Tempo: $bpm", kotlin.math.abs(folded - 120.0) <= 2.0)
    }

    @Test
    fun `halbes Tempo wird als Oktave akzeptiert`() {
        // 60 BPM => Intervall 1000 ms => 40 Fenster. Im 60-200-Fenster
        // kann das als 120 BPM gefaltet erscheinen - beides ist korrekt.
        val bpm = tempoFromImpulses(intervalWindows = 40, impulseCount = 20)

        assertNotNull(bpm)
        val folded = foldToRange(bpm!!.toDouble())
        assertTrue(
            "unerwartetes Tempo: $bpm",
            kotlin.math.abs(folded - 120.0) <= 2.0 || kotlin.math.abs(folded - 60.0) <= 2.0,
        )
    }

    @Test
    fun `stille liefert kein Tempo`() {
        val accumulator = TempoAccumulator(sampleRateHz = 8_000)
        repeat(8_000) { accumulator.accept(0.0) }

        assertNull(accumulator.finish())
    }

    @Test
    fun `zu kurze Eingabe liefert kein Tempo`() {
        val accumulator = TempoAccumulator(sampleRateHz = 8_000)
        repeat(100) { accumulator.accept(0.5) }

        assertNull(accumulator.finish())
    }

    // --- ChromaAccumulator ---

    @Test
    fun `klarer Sinuston liefert eine Camelot-Tonart`() {
        val accumulator = ChromaAccumulator(sampleRateHz = 8_000)
        // A4 = 440 Hz, 8 Sekunden => 8 Fenster (Fenster 1024, Decimation 5).
        val samples = 8 * 8_000
        for (i in 0 until samples) {
            accumulator.accept(sin(2.0 * PI * 440.0 * i / 8_000))
        }

        val key = accumulator.finish()

        assertNotNull(key)
        assertTrue("unerwartete Tonart: $key", key!!.matches(Regex("\\d+[AB]")))
    }

    @Test
    fun `stille liefert keine Tonart`() {
        val accumulator = ChromaAccumulator(sampleRateHz = 8_000)
        repeat(8_000) { accumulator.accept(0.0) }

        assertNull(accumulator.finish())
    }

    @Test
    fun `zu kurze Eingabe liefert keine Tonart`() {
        val accumulator = ChromaAccumulator(sampleRateHz = 8_000)
        repeat(500) { accumulator.accept(0.5) }

        assertNull(accumulator.finish())
    }

    // --- Helpers ---

    private fun tempoFromImpulses(
        intervalWindows: Int,
        impulseCount: Int,
    ): Float? {
        val sampleRate = 8_000
        val samplesPerWindow = sampleRate * 25 / 1_000
        val accumulator = TempoAccumulator(sampleRateHz = sampleRate, windowMs = 25)

        repeat(impulseCount) { impulse ->
            // Kurzer lauter Impuls am Fensteranfang.
            repeat(50) { accumulator.accept(1.0) }
            // Dann Stille bis zum naechsten Impuls.
            val silenceSamples = intervalWindows * samplesPerWindow - 50
            repeat(silenceSamples) { accumulator.accept(0.05) }
        }

        return accumulator.finish()
    }

    private fun foldToRange(bpm: Double): Double {
        var folded = bpm
        while (folded < TempoAccumulator.MIN_BPM) folded *= 2.0
        while (folded > TempoAccumulator.MAX_BPM) folded /= 2.0
        return folded
    }
}
