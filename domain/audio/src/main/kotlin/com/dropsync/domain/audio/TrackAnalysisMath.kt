package com.dropsync.domain.audio

import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow
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
        // Track-Peak fuer die Anzeige-Normalisierung (Phase 8).
        val magnitude = abs(sample)
        if (magnitude > peakLinear) peakLinear = magnitude
        seenSamples++
    }

    /** Buckets in Trackreihenfolge; nie leer, Laenge immer [bucketCount]. */
    fun finish(): List<WaveformBucket> = List(bucketCount) { WaveformBucket(min = mins[it], max = maxs[it]) }

    private fun quantizeToInt8(sample: Double): Byte =
        (sample.coerceIn(-1.0, 1.0) * Byte.MAX_VALUE)
            .roundToInt()
            .coerceIn(Byte.MIN_VALUE.toInt(), Byte.MAX_VALUE.toInt())
            .toByte()

    /** Groesster Betrag bisher (0..1); Grundlage der Anzeige-Normalisierung. */
    fun peak(): Double = peakLinear

    private var peakLinear: Double = 0.0
}

/**
 * Visuelle Lautheits-Normalisierung der Waveform (Phase 8).
 *
 * Problem: Int8-Min/Max sind absolut. Ein leise gemasterter Track
 * (Peak 0.1) wuerde als fast flache Linie erscheinen. Die Anzeige
 * skaliert deshalb mit einem Boden hoch: alles unter [FLOOR_LINEAR]
 * wird auf den Boden angehoben, laute Tracks bleiben unveraendert -
 * ehrlich, nie uebersteuert, deterministisch.
 */
object WaveformDisplayGain {
    /** Boden: Tracks mit Peak unter 0.35 werden hochskaliert. */
    const val FLOOR_LINEAR: Double = 0.35

    /** Multiplikator fuer die Anzeige; mindestens 1 (nie absenken). */
    fun gainForPeak(peakLinear: Double): Double {
        if (peakLinear <= 0.0 || peakLinear >= FLOOR_LINEAR) return 1.0
        return (FLOOR_LINEAR / peakLinear).coerceIn(1.0, MAX_BOOST)
    }

    /** Anzeige-Buckets: Byte auf [-1..1], mit Boden hochskaliert. */
    fun displayBuckets(
        buckets: List<WaveformBucket>,
        peakLinear: Double,
    ): List<Pair<Float, Float>> {
        val gain = gainForPeak(peakLinear).toFloat()
        return buckets.map { bucket ->
            (bucket.min / 127f * gain) to (bucket.max / 127f * gain)
        }
    }

    private const val MAX_BOOST: Double = 8.0
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

    // Analyse-Befund 4.7: primitives DoubleArray mit Verdopplung statt
    // mutableListOf<Double> - bis ~600 KB Boxings je 10-Minuten-Track
    // bei Profil FULL entfallen.
    private var windows = DoubleArray(64)
    private var windowCount = 0
    private var sumOfSquares: Double = 0.0
    private var samplesInWindow: Int = 0

    private fun appendWindow(rms: Double) {
        if (windowCount == windows.size) {
            windows = windows.copyOf(windows.size * 2)
        }
        windows[windowCount++] = rms
    }

    /** Nimmt ein Mono-Sample im Bereich [-1.0, 1.0] auf. */
    fun accept(sample: Double) {
        sumOfSquares += sample * sample
        samplesInWindow++
        if (samplesInWindow == samplesPerWindow) {
            appendWindow(sqrt(sumOfSquares / samplesPerWindow))
            sumOfSquares = 0.0
            samplesInWindow = 0
        }
    }

    /** RMS je Fenster in Trackreihenfolge. */
    fun finish(): List<Double> {
        if (samplesInWindow * 2 >= samplesPerWindow) {
            appendWindow(sqrt(sumOfSquares / samplesInWindow))
        }
        sumOfSquares = 0.0
        samplesInWindow = 0
        return windows.copyOf(windowCount).toList()
    }
}

/**
 * Integrierte Lautheit (LUFS) und True-Peak-Naeherung in einem
 * Streaming-Durchgang (Offtrack Phase 8/10). Rein JVM-testbar.
 *
 * Die integrierte Lautheit folgt der EBU-R128-Grundidee: Fenster-RMS
 * bilden die Momentan-Lautheit, mit einem absoluten Gate bei -70 LUFS
 * und einem relativen Gate, das auf dem lautesten 10-Prozent-Pegel
 * liegt. True-Peak bleibt hier eine konservative PCM-Peak-Naeherung;
 * echtes 4x-Oversampling folgt erst mit der Hardware-Messung.
 */
class LoudnessAccumulator(
    private val sampleRateHz: Int,
    windowMs: Int = DEFAULT_WINDOW_MS,
) {
    init {
        require(sampleRateHz > 0) { "sampleRateHz muss positiv sein" }
        require(windowMs > 0) { "windowMs muss positiv sein" }
    }

    private val energy =
        EnergyAccumulator(
            samplesPerWindow = sampleRateHz * windowMs / 1_000,
        )
    private var truePeakLinear = 0.0

    /** Nimmt ein Mono-Sample im Bereich [-1.0, 1.0] auf. */
    fun accept(sample: Double) {
        energy.accept(sample)
        val magnitude = abs(sample)
        if (magnitude > truePeakLinear) truePeakLinear = magnitude
    }

    /** Integrierte Lautheit in LUFS oder `null` ohne genug Energie. */
    fun integratedLufs(): Float? {
        val windows = energy.finish()
        if (windows.isEmpty()) return null
        val lufs =
            windows
                .map { linearToLufs(it) }
                .filter { it > ABSOLUTE_GATE_LUFS }
        if (lufs.isEmpty()) return null

        // Relatives Gate: lauteste 10 % bestimmen den Referenzpegel.
        val sorted = lufs.sortedDescending()
        val topCount = (sorted.size / 10).coerceAtLeast(1)
        val relativeGate = sorted.take(topCount).average()
        val gated =
            lufs.filter { it > relativeGate - RELATIVE_GATE_MARGIN_LUFS }
        if (gated.isEmpty()) return null
        return gated.average().toFloat()
    }

    /** Groesster Betrag bisher (0..1); Basis fuer die True-Peak-Schaetzung. */
    fun truePeakLinear(): Double = truePeakLinear

    private fun linearToLufs(linear: Double): Double {
        val power = (linear * linear).coerceAtLeast(MIN_POWER)
        return 10.0 * log10(power)
    }

    companion object {
        const val DEFAULT_WINDOW_MS: Int = 400
        const val ABSOLUTE_GATE_LUFS: Double = -70.0
        const val RELATIVE_GATE_MARGIN_LUFS: Double = 10.0
        private const val MIN_POWER: Double = 1e-12
    }
}
