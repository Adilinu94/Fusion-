package com.dropsync.domain.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * RMS-Energie des Bassbereichs (Tiefpass, Standard 150 Hz) in festen Fenstern - paralleles
 * Gegenstueck zum [EnergyAccumulator] mit GLEICHER Fensterlaenge, damit beide Reihen
 * index-gleich sind.
 *
 * Wozu? Viele Drops entstehen nicht durch einen Lautstaerke-Sprung des Gesamtsignals,
 * sondern dadurch, dass der Tiefbass im Build-up herausgefiltert wird und beim Drop
 * zurueckkehrt, waehrend Riser und Pads die Gesamtlautstaerke schon oben halten. Die
 * Fullband-RMS sieht davon fast nichts; der Bassverlauf zeigt es deutlich
 * ([DropDetection]).
 *
 * Filter: zwei kaskadierte Biquad-Tiefpaesse 2. Ordnung (Butterworth, Q = 1/sqrt 2),
 * zusammen 24 dB/Oktave. Rein arithmetisch, keine Allokation je Sample.
 */
class BassEnergyAccumulator(
    sampleRateHz: Int,
    samplesPerWindow: Int,
    cutoffHz: Double = DEFAULT_CUTOFF_HZ,
) {
    private val stage1 = LowPassBiquad(sampleRateHz, cutoffHz)
    private val stage2 = LowPassBiquad(sampleRateHz, cutoffHz)
    private val energy = EnergyAccumulator(samplesPerWindow)

    fun accept(sample: Double) {
        energy.accept(stage2.process(stage1.process(sample)))
    }

    fun finish(): List<Double> = energy.finish()

    private class LowPassBiquad(
        sampleRateHz: Int,
        cutoffHz: Double,
    ) {
        private val b0: Double
        private val b1: Double
        private val b2: Double
        private val a1: Double
        private val a2: Double
        private var x1 = 0.0
        private var x2 = 0.0
        private var y1 = 0.0
        private var y2 = 0.0

        init {
            require(sampleRateHz > 0) { "sampleRateHz muss positiv sein" }
            // Cutoff muss unter Nyquist liegen, sonst ist der Biquad instabil.
            val fc = cutoffHz.coerceIn(1.0, sampleRateHz / 2.0 - 1.0)
            val w0 = 2.0 * PI * fc / sampleRateHz
            val alpha = sin(w0) / (2.0 * Q)
            val cosW0 = cos(w0)
            val a0 = 1.0 + alpha
            b0 = (1.0 - cosW0) / 2.0 / a0
            b1 = (1.0 - cosW0) / a0
            b2 = b0
            a1 = -2.0 * cosW0 / a0
            a2 = (1.0 - alpha) / a0
        }

        fun process(x: Double): Double {
            val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
            x2 = x1
            x1 = x
            y2 = y1
            y1 = y
            return y
        }
    }

    companion object {
        const val DEFAULT_CUTOFF_HZ: Double = 150.0
        private const val Q = 0.7071067811865476
    }
}
