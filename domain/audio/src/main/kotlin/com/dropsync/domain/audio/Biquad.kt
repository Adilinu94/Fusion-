package com.dropsync.domain.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** Filtertypen des parametrischen EQ (Plan Phase 2). */
enum class BiquadType {
    PEAK,
    LOW_SHELF,
    HIGH_SHELF,
    LOW_PASS,
    HIGH_PASS,
    NOTCH,
}

/** Normalisierte Biquad-Koeffizienten (a0 = 1). */
data class BiquadCoefficients(
    val b0: Double,
    val b1: Double,
    val b2: Double,
    val a1: Double,
    val a2: Double,
) {
    companion object {
        val IDENTITY = BiquadCoefficients(1.0, 0.0, 0.0, 0.0, 0.0)

        /**
         * Koeffizienten nach dem RBJ Audio-EQ-Cookbook. [gainDb] wirkt nur
         * bei PEAK/Shelf; [q] steuert Bandbreite bzw. Flankensteilheit.
         */
        fun of(
            type: BiquadType,
            frequencyHz: Double,
            sampleRateHz: Double,
            gainDb: Double,
            q: Double,
        ): BiquadCoefficients {
            // Oberhalb der Nyquistfrequenz ist der Filter nicht definiert.
            if (frequencyHz <= 0.0 || frequencyHz >= sampleRateHz / 2.0) return IDENTITY
            val a = 10.0.pow(gainDb / 40.0)
            val w0 = 2.0 * PI * frequencyHz / sampleRateHz
            val cosW0 = cos(w0)
            val sinW0 = sin(w0)
            val alpha = sinW0 / (2.0 * q.coerceAtLeast(0.01))

            val b0: Double
            val b1: Double
            val b2: Double
            val a0: Double
            val a1: Double
            val a2: Double
            when (type) {
                BiquadType.PEAK -> {
                    b0 = 1.0 + alpha * a
                    b1 = -2.0 * cosW0
                    b2 = 1.0 - alpha * a
                    a0 = 1.0 + alpha / a
                    a1 = -2.0 * cosW0
                    a2 = 1.0 - alpha / a
                }

                BiquadType.LOW_SHELF -> {
                    val sqrtA = sqrt(a)
                    b0 = a * ((a + 1) - (a - 1) * cosW0 + 2.0 * sqrtA * alpha)
                    b1 = 2.0 * a * ((a - 1) - (a + 1) * cosW0)
                    b2 = a * ((a + 1) - (a - 1) * cosW0 - 2.0 * sqrtA * alpha)
                    a0 = (a + 1) + (a - 1) * cosW0 + 2.0 * sqrtA * alpha
                    a1 = -2.0 * ((a - 1) + (a + 1) * cosW0)
                    a2 = (a + 1) + (a - 1) * cosW0 - 2.0 * sqrtA * alpha
                }

                BiquadType.HIGH_SHELF -> {
                    val sqrtA = sqrt(a)
                    b0 = a * ((a + 1) + (a - 1) * cosW0 + 2.0 * sqrtA * alpha)
                    b1 = -2.0 * a * ((a - 1) + (a + 1) * cosW0)
                    b2 = a * ((a + 1) + (a - 1) * cosW0 - 2.0 * sqrtA * alpha)
                    a0 = (a + 1) - (a - 1) * cosW0 + 2.0 * sqrtA * alpha
                    a1 = 2.0 * ((a - 1) - (a + 1) * cosW0)
                    a2 = (a + 1) - (a - 1) * cosW0 - 2.0 * sqrtA * alpha
                }

                BiquadType.LOW_PASS -> {
                    b0 = (1.0 - cosW0) / 2.0
                    b1 = 1.0 - cosW0
                    b2 = (1.0 - cosW0) / 2.0
                    a0 = 1.0 + alpha
                    a1 = -2.0 * cosW0
                    a2 = 1.0 - alpha
                }

                BiquadType.HIGH_PASS -> {
                    b0 = (1.0 + cosW0) / 2.0
                    b1 = -(1.0 + cosW0)
                    b2 = (1.0 + cosW0) / 2.0
                    a0 = 1.0 + alpha
                    a1 = -2.0 * cosW0
                    a2 = 1.0 - alpha
                }

                BiquadType.NOTCH -> {
                    b0 = 1.0
                    b1 = -2.0 * cosW0
                    b2 = 1.0
                    a0 = 1.0 + alpha
                    a1 = -2.0 * cosW0
                    a2 = 1.0 - alpha
                }
            }
            return BiquadCoefficients(b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0)
        }
    }
}

/**
 * Streamender Biquad (transponierte Direktform II) fuer einen Kanal;
 * verarbeitet interleaved Puffer ueber [offset]/[stride]. Koeffizienten
 * sind zur Laufzeit austauschbar; der Zustand bleibt dabei erhalten
 * (kleine Uebergangsfehler sind unhoerbar gegenueber einem Reset-Knack).
 */
class BiquadFilter(
    initialCoefficients: BiquadCoefficients,
) {
    @Volatile
    var coefficients: BiquadCoefficients = initialCoefficients

    private var z1 = 0.0
    private var z2 = 0.0

    fun reset() {
        z1 = 0.0
        z2 = 0.0
    }

    fun processInterleaved(
        samples: DoubleArray,
        count: Int,
        offset: Int,
        stride: Int,
    ) {
        val c = coefficients
        var i = offset
        while (i < count) {
            val input = samples[i]
            val output = c.b0 * input + z1
            z1 = c.b1 * input - c.a1 * output + z2
            z2 = c.b2 * input - c.a2 * output
            samples[i] = output
            i += stride
        }
    }

    /** Frequenzgang in dB an [frequencyHz] (fuer Tests und UI-Kurven). */
    companion object {
        fun responseDb(
            coefficients: BiquadCoefficients,
            frequencyHz: Double,
            sampleRateHz: Double,
        ): Double {
            val w = 2.0 * PI * frequencyHz / sampleRateHz
            // |H(e^jw)|^2 ueber Real-/Imaginaerteile von Zaehler und Nenner.
            val c = coefficients
            val cos1 = cos(w)
            val sin1 = sin(w)
            val cos2 = cos(2.0 * w)
            val sin2 = sin(2.0 * w)
            val numRe = c.b0 + c.b1 * cos1 + c.b2 * cos2
            val numIm = -(c.b1 * sin1 + c.b2 * sin2)
            val denRe = 1.0 + c.a1 * cos1 + c.a2 * cos2
            val denIm = -(c.a1 * sin1 + c.a2 * sin2)
            val magnitude =
                sqrt((numRe * numRe + numIm * numIm) / (denRe * denRe + denIm * denIm))
            return AudioMath.linearToDb(magnitude)
        }
    }
}
