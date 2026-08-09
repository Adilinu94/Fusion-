package com.dropsync.domain.audio

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin

/**
 * Durchsatz- und Stabilitaetswaechter der DSP-Kernschleife (Plan Phase 7).
 *
 * Das Planziel "< 5 % CPU bei 32 Baendern/48 kHz auf Mittelklasse" laesst
 * sich nur auf echter Hardware exakt messen. Dieser JVM-Test prueft die
 * dafuer entscheidende Invariante geraeteunabhaengig: die reine
 * Filtermathematik muss ein Vielfaches schneller als Echtzeit laufen,
 * sonst kann die Wiedergabe grundsaetzlich nicht mithalten. Er faengt
 * damit katastrophale Regressionen (z. B. versehentliche Allokationen je
 * Sample) zuverlaessig ab.
 */
class DspPerformanceTest {
    private val sampleRateHz = 48_000.0
    private val channels = 2
    private val bandCount = EqSettings.MAX_BANDS

    /** 32 log-verteilte Peak-Baender (20 Hz..20 kHz) mit wechselndem Gain. */
    private fun buildBands(): List<EqBand> =
        (0 until bandCount).map { i ->
            val fraction = i.toDouble() / (bandCount - 1)
            EqBand(
                frequencyHz = 20.0 * 1_000.0.pow(fraction),
                gainDb = if (i % 2 == 0) 4.0 else -4.0,
                q = 2.0,
                type = BiquadType.PEAK,
            )
        }

    /** Baut je Kanal eine 32-fache Biquad-Kaskade (wie in `:data:audio`). */
    private fun buildCascade(bands: List<EqBand>): Array<Array<BiquadFilter>> =
        Array(channels) {
            Array(bandCount) { b ->
                BiquadFilter(
                    BiquadCoefficients.of(
                        bands[b].type,
                        bands[b].frequencyHz,
                        sampleRateHz,
                        bands[b].gainDb,
                        bands[b].q,
                    ),
                )
            }
        }

    private fun sineBuffer(frames: Int): DoubleArray {
        val samples = DoubleArray(frames * channels)
        var n = 0
        while (n < frames) {
            val value = sin(2.0 * PI * 440.0 * n / sampleRateHz)
            samples[n * channels] = value
            samples[n * channels + 1] = value
            n++
        }
        return samples
    }

    private fun process(
        cascade: Array<Array<BiquadFilter>>,
        samples: DoubleArray,
    ) {
        val count = samples.size
        for (ch in 0 until channels) {
            for (band in cascade[ch]) {
                band.processInterleaved(samples, count, offset = ch, stride = channels)
            }
        }
    }

    @Test
    fun `32-band-eq verarbeitet 30s stereo deutlich schneller als echtzeit`() {
        val seconds = 30
        val frames = sampleRateHz.toInt() * seconds
        val bands = buildBands()

        // Aufwaermen: JIT kompilieren lassen, danach mit frischem Zustand messen.
        process(buildCascade(bands), sineBuffer(sampleRateHz.toInt()))

        val samples = sineBuffer(frames)
        val cascade = buildCascade(bands)
        val startNs = System.nanoTime()
        process(cascade, samples)
        val elapsedMs = (System.nanoTime() - startNs) / 1_000_000.0

        val audioDurationMs = seconds * 1_000.0
        val realtimeFactor = audioDurationMs / elapsedMs
        // Mindestens doppelte Echtzeit: sehr robust (real ~30-60x), faengt
        // aber jede grobe Regression ab, die den Durchsatz einbrechen laesst.
        assertTrue(
            "EQ-Durchsatz zu niedrig: ${"%.0f".format(elapsedMs)} ms fuer ${seconds}s Audio " +
                "(Echtzeitfaktor ${"%.1f".format(realtimeFactor)}x)",
            elapsedMs < audioDurationMs / 2.0,
        )
    }

    @Test
    fun `32-band-eq bleibt numerisch stabil`() {
        val frames = sampleRateHz.toInt() // 1 s
        val samples = sineBuffer(frames)
        process(buildCascade(buildBands()), samples)

        // Keine NaN/Inf und beschraenkte Amplitude trotz 32 aktiver Baender.
        var maxAbs = 0.0
        for (value in samples) {
            assertTrue("Ausgabe enthaelt NaN/Inf", value.isFinite())
            val magnitude = if (value < 0) -value else value
            if (magnitude > maxAbs) maxAbs = magnitude
        }
        assertTrue("Ausgabe unrealistisch laut: $maxAbs", maxAbs < 32.0)
    }
}
