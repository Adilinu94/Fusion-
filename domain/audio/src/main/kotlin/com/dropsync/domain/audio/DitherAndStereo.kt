package com.dropsync.domain.audio

import java.util.Random

/** Dithering-Optionen beim Reduzieren auf 16 Bit (Plan Phase 2). */
enum class DitherMode {
    OFF,

    /** Dreieckfoermiges Rauschen (2 LSB Spitze-Spitze), Standardwahl. */
    TPDF,

    /** TPDF plus Rauschformung erster Ordnung (hochtonlastig). */
    SHAPED,
}

/**
 * TPDF-Dithergenerator in LSB-Einheiten; deterministisch per [seed]
 * fuer reproduzierbare Tests. SHAPED verschiebt den Quantisierungs-
 * fehler per Fehlerrueckfuehrung erster Ordnung nach oben.
 */
class DitherGenerator(
    private val mode: DitherMode,
    seed: Long = 0x5EED,
) {
    private val random = Random(seed)
    private var lastError = 0.0

    /** Rauschwert in LSB, zum skalierten Sample zu addieren. */
    fun next(): Double =
        when (mode) {
            DitherMode.OFF -> 0.0
            DitherMode.TPDF -> random.nextDouble() - random.nextDouble()
            DitherMode.SHAPED -> random.nextDouble() - random.nextDouble() - lastError
        }

    /** Meldet den Quantisierungsfehler des letzten Samples (nur SHAPED). */
    fun feedback(error: Double) {
        if (mode == DitherMode.SHAPED) {
            lastError = error
        }
    }
}

/**
 * Mid/Side-Stereoverbreiterung (Plan Phase 2): 100 % ist neutral,
 * 0 % ist Mono, 200 % verdoppelt den Seitenanteil. Wirkt nur auf
 * Stereomaterial; andere Kanalzahlen bleiben unveraendert.
 */
object StereoMatrix {
    const val MIN_WIDTH_PERCENT: Int = 0
    const val NEUTRAL_WIDTH_PERCENT: Int = 100
    const val MAX_WIDTH_PERCENT: Int = 200

    fun process(
        samples: DoubleArray,
        count: Int,
        channelCount: Int,
        widthPercent: Int,
    ) {
        if (channelCount != 2 || widthPercent == NEUTRAL_WIDTH_PERCENT) return
        val sideGain =
            widthPercent
                .coerceIn(MIN_WIDTH_PERCENT, MAX_WIDTH_PERCENT)
                .toDouble() / NEUTRAL_WIDTH_PERCENT
        var i = 0
        while (i + 1 < count) {
            val mid = (samples[i] + samples[i + 1]) / 2.0
            val side = (samples[i] - samples[i + 1]) / 2.0 * sideGain
            samples[i] = mid + side
            samples[i + 1] = mid - side
            i += 2
        }
    }
}
