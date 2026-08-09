package com.dropsync.domain.audio

import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Streaming-Akkumulatoren des Analysedurchgangs (Marker/Waveform-Plan
 * Phase 2): ein Durchgang ueber den Mono-Downmix liefert Waveform-Peaks
 * und Kurzzeit-Energie zugleich. Reine JVM-Mathematik, deterministisch
 * testbar gegen synthetische PCM-Fixtures.
 *
 * Bildet Min/Max-Buckets ueber [totalSamples] Mono-Samples. Weicht die
 * tatsaechliche Samplezahl vom Schaetzwert ab (Decoder runden Dauer),
 * landen Ueberzaehlige im letzten Bucket; fehlende Buckets bleiben still
 * (0/0) statt zu raten.
 */
class WaveformAccumulator(
    totalSamples: Long,
    private val bucketCount: Int,
) {
    init {
        require(bucketCount > 0) { "bucketCount muss positiv sein" }
    }

    private val samplesPerBucket: Long = (totalSamples / bucketCount).coerceAtLeast(1L)
    private val mins = ByteArray(bucketCount)
    private val maxs = ByteArray(bucketCount)
    private val touched = BooleanArray(bucketCount)
    private var seenSamples: Long = 0

    /** Nimmt ein Mono-Sample im Bereich [-1.0, 1.0] auf. */
    fun accept(sample: Double) {
        val bucketIndex =
            (seenSamples / samplesPerBucket)
                .coerceAtMost((bucketCount - 1).toLong())
                .toInt()
        val quantized = quantizeToInt8(sample)
        if (!touched[bucketIndex]) {
            touched[bucketIndex] = true
            mins[bucketIndex] = quantized
            maxs[bucketIndex] = quantized
        } else {
            if (quantized < mins[bucketIndex]) mins[bucketIndex] = quantized
            if (quantized > maxs[bucketIndex]) maxs[bucketIndex] = quantized
        }
        seenSamples++
    }

    /** Buckets in Trackreihenfolge; nie leer, Laenge immer [bucketCount]. */
    fun finish(): List<WaveformBucket> = List(bucketCount) { WaveformBucket(min = mins[it], max = maxs[it]) }

    private fun quantizeToInt8(sample: Double): Byte =
        (sample.coerceIn(-1.0, 1.0) * Byte.MAX_VALUE)
            .roundToInt()
            .coerceIn(Byte.MIN_VALUE.toInt(), Byte.MAX_VALUE.toInt())
            .toByte()
}

/**
 * Kurzzeit-Energie (RMS) in festen Fenstern (~20-50 ms), Grundlage der
 * Onset-Erkennung in Phase 5. Ein angefangenes Restfenster am Trackende
 * wird mitgezaehlt, sobald es mindestens halb gefuellt ist.
 */
class EnergyAccumulator(
    private val samplesPerWindow: Int,
) {
    init {
        require(samplesPerWindow > 0) { "samplesPerWindow muss positiv sein" }
    }

    private val windows = mutableListOf<Double>()
    private var sumOfSquares: Double = 0.0
    private var samplesInWindow: Int = 0

    /** Nimmt ein Mono-Sample im Bereich [-1.0, 1.0] auf. */
    fun accept(sample: Double) {
        sumOfSquares += sample * sample
        samplesInWindow++
        if (samplesInWindow == samplesPerWindow) {
            windows += sqrt(sumOfSquares / samplesPerWindow)
            sumOfSquares = 0.0
            samplesInWindow = 0
        }
    }

    /** RMS je Fenster in Trackreihenfolge. */
    fun finish(): List<Double> {
        if (samplesInWindow * 2 >= samplesPerWindow) {
            windows += sqrt(sumOfSquares / samplesInWindow)
        }
        sumOfSquares = 0.0
        samplesInWindow = 0
        return windows.toList()
    }
}
