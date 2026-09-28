package com.dropsync.domain.audio

import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.tanh

/**
 * Pegel- und Hilfsmathematik der DSP-Kette (ADR-0005). Alle Stufen
 * rechnen intern in 64-Bit-Double; Samples sind auf [-1.0, 1.0] normiert.
 */
object AudioMath {
    /** Kleinster darstellbarer Pegel fuer dB-Umrechnungen. */
    const val MIN_DB: Double = -120.0

    /** dB nach linearem Faktor (0 dB = 1.0). */
    fun dbToLinear(db: Double): Double = 10.0.pow(db / 20.0)

    /** Linearer Faktor nach dB; 0 und negative Werte fallen auf [MIN_DB]. */
    fun linearToDb(linear: Double): Double = if (linear <= 0.0) MIN_DB else (20.0 * log10(linear)).coerceAtLeast(MIN_DB)

    /**
     * Hartes Clipping auf den gueltigen Samplebereich.
     *
     * **NaN-sicher (2026-09-27, Befund 4.3):** `Double.coerceIn` gibt bei
     * `NaN` `NaN` zurueck — Kotlin vergleicht mit `<` und `>`, und `NaN`
     * ist mit beiden false. Ein defekter Stream liefert deshalb bis zum
     * Titelende digitale Stille (`roundToInt(NaN) == 0`), was als
     * "Encoder kaputt" fehldiagnostiziert wird. `Infinity` faellt mit
     * `coerceIn` immerhin korrekt auf ±1.0, wird hier aber mitbehandelt,
     * damit der Vertrag "liefert immer einen endlichen Wert" gilt.
     */
    fun clampSample(sample: Double): Double =
        if (sample.isNaN()) {
            0.0
        } else {
            sample.coerceIn(-1.0, 1.0)
        }

    /**
     * Weiches Begrenzen oberhalb von [threshold] (tanh-Saettigung,
     * monoton, naehert sich 1.0): verhindert hoerbares Hartclipping
     * nach Preamp-/EQ-Anhebung.
     *
     * NaN-sicher wie [clampSample]: `abs(NaN) <= threshold` ist false,
     * `tanh(NaN)` ist `NaN`, also wanderte der Wert unveraendert durch.
     */
    fun softClip(
        sample: Double,
        threshold: Double = 0.89,
    ): Double {
        if (sample.isNaN()) return 0.0
        val magnitude = abs(sample)
        if (magnitude <= threshold) return sample
        val sign = if (sample < 0) -1.0 else 1.0
        val excess = (magnitude - threshold) / (1.0 - threshold)
        return sign * (threshold + (1.0 - threshold) * tanh(excess))
    }

    /** Ersetzt nicht-finite Werte durch [replacement] (0.0 = Stille). */
    fun sanitize(sample: Double): Double = if (sample.isFinite()) sample else 0.0
}
