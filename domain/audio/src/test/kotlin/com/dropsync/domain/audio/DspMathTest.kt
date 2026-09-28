package com.dropsync.domain.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sin

/** Referenzpruefungen der Filtermathematik (Plan Phase 2, RBJ-Cookbook). */
class BiquadTest {
    @Test
    fun `peak filter hebt die mittenfrequenz um den gain an`() {
        val coefficients =
            BiquadCoefficients.of(BiquadType.PEAK, 1_000.0, 48_000.0, gainDb = 6.0, q = 1.0)
        assertEquals(6.0, BiquadFilter.responseDb(coefficients, 1_000.0, 48_000.0), 0.05)
        // Weit entfernt vom Band bleibt der Pegel praktisch unveraendert.
        assertEquals(0.0, BiquadFilter.responseDb(coefficients, 20.0, 48_000.0), 0.2)
        assertEquals(0.0, BiquadFilter.responseDb(coefficients, 20_000.0, 48_000.0), 0.2)
    }

    @Test
    fun `low shelf hebt tiefen und laesst hoehen unveraendert`() {
        val coefficients =
            BiquadCoefficients.of(BiquadType.LOW_SHELF, 100.0, 48_000.0, gainDb = 9.0, q = 0.707)
        assertEquals(9.0, BiquadFilter.responseDb(coefficients, 10.0, 48_000.0), 0.3)
        assertEquals(0.0, BiquadFilter.responseDb(coefficients, 10_000.0, 48_000.0), 0.2)
    }

    @Test
    fun `high shelf hebt hoehen und laesst baesse unveraendert`() {
        val coefficients =
            BiquadCoefficients.of(BiquadType.HIGH_SHELF, 8_000.0, 48_000.0, gainDb = -6.0, q = 0.707)
        assertEquals(-6.0, BiquadFilter.responseDb(coefficients, 20_000.0, 48_000.0), 0.3)
        assertEquals(0.0, BiquadFilter.responseDb(coefficients, 50.0, 48_000.0), 0.2)
    }

    @Test
    fun `notch loescht die mittenfrequenz`() {
        val coefficients =
            BiquadCoefficients.of(BiquadType.NOTCH, 1_000.0, 48_000.0, gainDb = 0.0, q = 4.0)
        assertTrue(BiquadFilter.responseDb(coefficients, 1_000.0, 48_000.0) < -30.0)
        assertEquals(0.0, BiquadFilter.responseDb(coefficients, 4_000.0, 48_000.0), 0.5)
    }

    @Test
    fun `frequenzen ausserhalb nyquist ergeben identitaet`() {
        assertEquals(
            BiquadCoefficients.IDENTITY,
            BiquadCoefficients.of(BiquadType.PEAK, 30_000.0, 48_000.0, 6.0, 1.0),
        )
        assertEquals(
            BiquadCoefficients.IDENTITY,
            BiquadCoefficients.of(BiquadType.PEAK, 0.0, 48_000.0, 6.0, 1.0),
        )
    }

    @Test
    fun `streamender filter daempft einen sinus im sperrband messbar`() {
        // 6-kHz-Sinus durch Low-Pass bei 1 kHz: deutliche Daempfung.
        val coefficients =
            BiquadCoefficients.of(BiquadType.LOW_PASS, 1_000.0, 48_000.0, 0.0, 0.707)
        val filter = BiquadFilter(coefficients)
        val samples = DoubleArray(4_800) { sin(2.0 * Math.PI * 6_000.0 * it / 48_000.0) }
        filter.processInterleaved(samples, samples.size, offset = 0, stride = 1)
        // Peak der zweiten Haelfte (eingeschwungen) messen.
        var peak = 0.0
        for (i in samples.size / 2 until samples.size) {
            peak = maxOf(peak, abs(samples[i]))
        }
        assertTrue("Erwartet > 20 dB Daempfung, Peak war $peak", peak < 0.1)
    }
}

class StereoMatrixTest {
    @Test
    fun `breite 0 ergibt mono`() {
        val samples = doubleArrayOf(1.0, -1.0, 0.5, 0.1)
        StereoMatrix.process(samples, samples.size, channelCount = 2, widthPercent = 0)
        assertEquals(samples[0], samples[1], 1e-12)
        assertEquals(samples[2], samples[3], 1e-12)
    }

    @Test
    fun `breite 100 ist neutral und 200 verdoppelt den seitenanteil`() {
        val neutral = doubleArrayOf(0.8, 0.2)
        StereoMatrix.process(neutral, 2, 2, 100)
        assertEquals(0.8, neutral[0], 1e-12)
        assertEquals(0.2, neutral[1], 1e-12)

        val wide = doubleArrayOf(0.8, 0.2) // mid 0.5, side 0.3.
        StereoMatrix.process(wide, 2, 2, 200)
        assertEquals(1.1, wide[0], 1e-12)
        assertEquals(-0.1, wide[1], 1e-12)
    }

    @Test
    fun `monomaterial bleibt unveraendert`() {
        val samples = doubleArrayOf(0.4, 0.5)
        StereoMatrix.process(samples, 2, channelCount = 1, widthPercent = 200)
        assertEquals(0.4, samples[0], 0.0)
        assertEquals(0.5, samples[1], 0.0)
    }
}

class DitherGeneratorTest {
    @Test
    fun `tpdf liegt im dreiecksbereich mit mittelwert nahe null`() {
        val generator = DitherGenerator(DitherMode.TPDF, seed = 42)
        var sum = 0.0
        repeat(100_000) {
            val value = generator.next()
            assertTrue(value > -2.0 && value < 2.0)
            sum += value
        }
        assertEquals(0.0, sum / 100_000, 0.02)
    }

    @Test
    fun `off liefert exakt null`() {
        val generator = DitherGenerator(DitherMode.OFF)
        repeat(100) { assertEquals(0.0, generator.next(), 0.0) }
    }
}

class EqBandsCodecTest {
    @Test
    fun `encode decode roundtrip erhaelt alle felder`() {
        val bands =
            listOf(
                EqBand(31.5, -3.0, 1.41, BiquadType.PEAK),
                EqBand(8_000.0, 4.5, 0.707, BiquadType.HIGH_SHELF),
            )
        assertEquals(bands, EqBandsCodec.decode(EqBandsCodec.encode(bands)))
    }

    @Test
    fun `defekte eingaben liefern null`() {
        assertNull(EqBandsCodec.decode(""))
        assertNull(EqBandsCodec.decode("abc"))
        assertNull(EqBandsCodec.decode("100:0:1:PEAK|kaputt"))
        assertNull(EqBandsCodec.decode("100:0:1:UNBEKANNT"))
    }
}

class StreamingResamplerTest {
    @Test
    fun `upsampling verdoppelt die framezahl ungefaehr`() {
        val resampler = StreamingResampler(48_000, 96_000, 1, ResamplerQuality.SINC)
        val input = DoubleArray(4_800) { sin(2.0 * Math.PI * 440.0 * it / 48_000.0) }
        val output = DoubleArray(resampler.maxOutputFrames(input.size) * 2)
        val frames = resampler.process(input, input.size, output)
        // Streaming-Latenz (Taps) frisst ein paar Frames am Anfang.
        assertTrue("Erwartet ~9600 Frames, war $frames", frames in 9_500..9_700)
    }

    @Test
    fun `sinus ueberlebt das resampling mit stabiler amplitude`() {
        val resampler = StreamingResampler(44_100, 48_000, 1, ResamplerQuality.SINC)
        val input = DoubleArray(8_820) { 0.5 * sin(2.0 * Math.PI * 1_000.0 * it / 44_100.0) }
        val output = DoubleArray(resampler.maxOutputFrames(input.size) * 2)
        val frames = resampler.process(input, input.size, output)
        var peak = 0.0
        for (i in frames / 2 until frames) {
            peak = maxOf(peak, abs(output[i]))
        }
        assertEquals(0.5, peak, 0.02)
    }

    @Test
    fun `stereo bleibt kanalgetrennt`() {
        val resampler = StreamingResampler(48_000, 96_000, 2, ResamplerQuality.LINEAR)
        // Links konstant 0.5, rechts konstant -0.25.
        val frames = 1_000
        val input = DoubleArray(frames * 2) { if (it % 2 == 0) 0.5 else -0.25 }
        val output = DoubleArray(resampler.maxOutputFrames(frames) * 2)
        val outFrames = resampler.process(input, frames, output)
        assertTrue(outFrames > 0)
        for (i in outFrames / 2 until outFrames) {
            assertEquals(0.5, output[i * 2], 1e-9)
            assertEquals(-0.25, output[i * 2 + 1], 1e-9)
        }
    }
}

class FreeverbTest {
    @Test
    fun `wet 0 ist transparent`() {
        val reverb = Freeverb(48_000, 2)
        reverb.updateSettings(Freeverb.Settings(wet = 0.0))
        val samples = doubleArrayOf(0.5, -0.5, 0.25, -0.25)
        val copy = samples.copyOf()
        reverb.process(samples, samples.size, 2)
        for (i in samples.indices) {
            assertEquals(copy[i], samples[i], 0.0)
        }
    }

    @Test
    fun `impuls erzeugt hoerbaren nachhall`() {
        val reverb = Freeverb(48_000, 1)
        reverb.updateSettings(Freeverb.Settings(roomSize = 0.8, damping = 0.2, wet = 1.0))
        val samples = DoubleArray(48_000)
        samples[0] = 1.0
        reverb.process(samples, samples.size, 1)
        // Nach 0,5 s muss noch Energie im Signal stecken (Hall klingt aus).
        var energyLate = 0.0
        for (i in 24_000 until 48_000) {
            energyLate += samples[i] * samples[i]
        }
        assertTrue("Nachhall fehlt, Energie war $energyLate", energyLate > 1e-6)
    }
}

/**
 * Nicht-finite Werte in der DSP-Kette (2026-09-27, Befund 4.3).
 *
 * Ausgangslage: `Double.coerceIn` gibt bei `NaN` `NaN` zurueck, weil
 * Kotlin mit `<` und `>` vergleicht und `NaN` mit beiden false ist. Ein
 * defekter Stream lieferte deshalb bis zum Titelende digitale Stille
 * (`roundToInt(NaN) == 0`), was als "Encoder kaputt" fehldiagnostiziert
 * wurde. Im Biquad war es schlimmer: einmal `NaN` im Verzoegerungsspeicher
 * und der Filter war fuer den Rest des Titels tot, weil jeder weitere
 * Sample aus `z1` erbt.
 */
class NonFiniteGuardTest {
    @Test
    fun `clampSample macht NaN zu stille statt zu NaN`() {
        assertEquals(0.0, AudioMath.clampSample(Double.NaN), 0.0)
        assertEquals(1.0, AudioMath.clampSample(Double.POSITIVE_INFINITY), 0.0)
        assertEquals(-1.0, AudioMath.clampSample(Double.NEGATIVE_INFINITY), 0.0)
        assertEquals(0.5, AudioMath.clampSample(0.5), 0.0)
    }

    @Test
    fun `softClip macht NaN zu stille`() {
        assertEquals(0.0, AudioMath.softClip(Double.NaN), 0.0)
        assertTrue(AudioMath.softClip(0.5).isFinite())
    }

    @Test
    fun `sanitize ersetzt nicht-endliche werte`() {
        assertEquals(0.0, AudioMath.sanitize(Double.NaN), 0.0)
        assertEquals(0.0, AudioMath.sanitize(Double.POSITIVE_INFINITY), 0.0)
        assertEquals(0.7, AudioMath.sanitize(0.7), 0.0)
    }

    @Test
    fun `biquad heilt seinen zustand nach einem NaN-sample`() {
        val filter =
            BiquadFilter(
                BiquadCoefficients.of(
                    type = BiquadType.PEAK,
                    frequencyHz = 1_000.0,
                    sampleRateHz = 48_000.0,
                    gainDb = 6.0,
                    q = 1.0,
                ),
            )
        // Normaler Betrieb: der Filter arbeitet.
        val warmup = 128
        for (i in 0 until warmup) {
            filter.process(0.5)
        }
        assertTrue(filter.process(0.5).isFinite())

        // Ein defekter Sample darf den Zustand nicht vergiften.
        assertEquals(0.0, filter.process(Double.NaN), 0.0)

        // Und der Filter muss danach wieder nutzbares Material liefern.
        var finite = 0
        for (i in 0 until 64) {
            if (filter.process(0.5).isFinite()) finite++
        }
        assertEquals("Filter blieb nach NaN tot", 64, finite)
    }

    @Test
    fun `interleaved-verarbeitung heilt ihren zustand nach NaN`() {
        val filter =
            BiquadFilter(
                BiquadCoefficients.of(
                    type = BiquadType.PEAK,
                    frequencyHz = 1_000.0,
                    sampleRateHz = 48_000.0,
                    gainDb = 3.0,
                    q = 1.0,
                ),
            )
        val samples = DoubleArray(64) { 0.4 }
        filter.processInterleaved(samples, samples.size, 0, 1)
        assertTrue(samples.all { it.isFinite() })

        // Ein NaN in der Mitte darf die Werte danach nicht zerstoeren.
        samples[10] = Double.NaN
        filter.processInterleaved(samples, samples.size, 0, 1)
        assertTrue("Nach NaN kamen nicht-endliche Werte heraus", samples.all { it.isFinite() })
    }
}
