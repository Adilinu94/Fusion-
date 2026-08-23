package com.dropsync.domain.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Schaetzt das Tempo (BPM) aus den Abstaenden zwischen Energie-Anstiegen
 * (Inter-Onset-Intervalle). Ein Oktavfehler (gegenueber dem Referenzwert
 * 2x oder 0.5x) ist akzeptiert: das Ergebnis wird in das Fenster
 * 60-200 BPM gefaltet, harmonisch verwandte Tempi gelten als bestanden.
 *
 * Die Mix-Metadaten haengen additiv am selben Mono-Decode-Durchgang wie
 * [WaveformAccumulator] und liefern eine reine Kotlin/JVM-Schaetzung.
 */
class TempoAccumulator(
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

    /** Nimmt ein Mono-Sample im Bereich [-1.0, 1.0] auf. */
    fun accept(sample: Double) {
        energy.accept(sample)
    }

    /**
     * Liefert das Tempo in BPM oder `null`, wenn nicht genug
     * Inter-Onset-Intervalle vorliegen. Ergebnis ist in [MIN_BPM]..[MAX_BPM]
     * gefaltet (Halb-/Doppeltempo zaehlt als Treffer).
     */
    fun finish(): Float? = finishEstimate()?.bpm

    /**
     * Tempo inklusive Konfidenz (Offtrack Phase 8). [TempoEstimate.bpm]
     * entspricht [finish], die Konfidenz quantifiziert die Eindeutigkeit
     * des staerksten Histogramm-Bins (1.0 = alle Intervalle treffen einen
     * Bin).
     */
    fun finishEstimate(): TempoEstimate? {
        val rms = energy.finish()
        if (rms.size < MIN_WINDOWS) return null

        // Inter-Onset-Intervalle: OnsetDetection mit dichtem Abstand statt
        // der Drop-Logik (5 s), damit einzelne Beats sichtbar werden.
        val onsets =
            OnsetDetection.detectOnsets(
                energyWindows = rms,
                windowDurationMs = DEFAULT_WINDOW_MS.toLong(),
                thresholdWindow = THRESHOLD_WINDOW,
                k = K,
                minSpacingMs = MIN_SPACING_MS,
                maxCandidates = MAX_CANDIDATES,
                minNovelty = MIN_NOVELTY,
            )
        if (onsets.size < MIN_ONSETS) return null

        val intervalsMs =
            onsets
                .zipWithNext()
                .map { (previous, current) -> current - previous }
                .filter { it > 0L }

        if (intervalsMs.isEmpty()) return null
        return histogramEstimate(intervalsMs)
    }

    private fun histogramEstimate(intervalsMs: List<Long>): TempoEstimate? {
        val binCount = MAX_BPM - MIN_BPM + 1
        val histogram = IntArray(binCount)
        intervalsMs.forEach { intervalMs ->
            val rawBpm = 60_000.0 / intervalMs
            val folded = foldToRange(rawBpm)
            val bin = folded.roundToInt() - MIN_BPM
            if (bin in 0 until binCount) {
                histogram[bin]++
            }
        }

        val strongest = histogram.indices.maxByOrNull { histogram[it] } ?: return null
        if (histogram[strongest] == 0) return null
        val total = histogram.sum()
        val confidence = if (total > 0) histogram[strongest].toFloat() / total else 0f
        return TempoEstimate(bpm = (strongest + MIN_BPM).toFloat(), confidence = confidence)
    }

    private fun foldToRange(bpm: Double): Double {
        var folded = bpm
        while (folded < MIN_BPM) folded *= 2.0
        while (folded > MAX_BPM) folded /= 2.0
        return folded
    }

    companion object {
        const val DEFAULT_WINDOW_MS: Int = 25
        const val MIN_BPM: Int = 60
        const val MAX_BPM: Int = 200

        private const val THRESHOLD_WINDOW = 12
        private const val K = 1.5
        private const val MIN_SPACING_MS = 250L
        private const val MAX_CANDIDATES = 1_000
        private const val MIN_NOVELTY = 0.02
        private const val MIN_WINDOWS = 40
        private const val MIN_ONSETS = 8
    }
}

/** Tempo-Schaetzung inklusive Konfidenz 0..1 (Offtrack Phase 8). */
data class TempoEstimate(
    val bpm: Float,
    val confidence: Float,
)

/**
 * Schaetzt die Tonart als Camelot-Notation (z. B. "8A") ueber ein
 * 12-Bin-Chromagramm (Goertzel je Halbton) und Korrelation gegen die
 * Krumhansl-Schmuckler-Dur-/Moll-Profile. Rechenintensiver als die
 * Waveform-Peaks; die Implementierung decimiert den Eingang, damit der
 * Durchsatz-Waechter (schneller als Echtzeit) eingehalten bleibt.
 */
class ChromaAccumulator(
    private val sampleRateHz: Int,
    private val decimation: Int = DEFAULT_DECIMATION,
    windowSamples: Int = DEFAULT_WINDOW_SAMPLES,
) {
    init {
        require(sampleRateHz > 0) { "sampleRateHz muss positiv sein" }
        require(decimation > 0) { "decimation muss positiv sein" }
        require(windowSamples > 0) { "windowSamples muss positiv sein" }
    }

    private val effectiveRateHz: Double = sampleRateHz.toDouble() / decimation
    private val window = DoubleArray(windowSamples)
    private val chroma = DoubleArray(12)
    private var windowIndex = 0
    private var sampleCount = 0L
    private var analyzedWindows = 0

    /** Nimmt ein Mono-Sample im Bereich [-1.0, 1.0] auf. */
    fun accept(sample: Double) {
        if (sampleCount % decimation != 0L) {
            sampleCount++
            return
        }
        sampleCount++
        window[windowIndex] = sample
        windowIndex++
        if (windowIndex == window.size) {
            accumulateWindow()
            windowIndex = 0
        }
    }

    /**
     * Liefert die Camelot-Notation oder `null`, wenn nicht genug Fenster
     * analysiert wurden.
     */
    fun finish(): String? = finishEstimate()?.camelotKey

    /**
     * Tonart inklusive Konfidenz (Offtrack Phase 8). Die Konfidenz ist
     * der Korrelationswert des besten Profils (1.0 = perfekte Ueberein-
     * stimmung, typischerweise deutlich darunter).
     */
    fun finishEstimate(): KeyEstimate? {
        // Ein angefangenes Restfenster mit mindestens halber Fuellung
        // mitnehmen, damit kurze Tracks nicht um die letzten Sekunden
        // beschnitten werden.
        if (windowIndex * 2 >= window.size) {
            accumulateWindow()
        }
        windowIndex = 0

        if (analyzedWindows < MIN_WINDOWS) return null

        val total = chroma.sum().takeIf { it > 0.0 } ?: return null
        val normalized = DoubleArray(12) { chroma[it] / total }

        var bestKey: String? = null
        var bestScore = Double.NEGATIVE_INFINITY
        for (root in 0 until 12) {
            val major = correlation(normalized, MAJOR_PROFILE, root)
            if (major > bestScore) {
                bestScore = major
                bestKey = camelotMajor(root)
            }
            val minor = correlation(normalized, MINOR_PROFILE, root)
            if (minor > bestScore) {
                bestScore = minor
                bestKey = camelotMinor(root)
            }
        }
        val key = bestKey ?: return null
        return KeyEstimate(camelotKey = key, confidence = bestScore.coerceIn(0.0, 1.0).toFloat())
    }

    private fun accumulateWindow() {
        for (pitchClass in 0 until 12) {
            for (octave in OCTAVES) {
                val frequency = pitchClassFrequency(pitchClass, octave)
                if (frequency > effectiveRateHz / 2.0) continue
                chroma[pitchClass] += goertzelPower(window, frequency, effectiveRateHz)
            }
        }
        analyzedWindows++
    }

    private fun goertzelPower(
        samples: DoubleArray,
        frequency: Double,
        sampleRateHz: Double,
    ): Double {
        val omega = 2.0 * PI * frequency / sampleRateHz
        val coefficient = 2.0 * cos(omega)
        var previous = 0.0
        var previousPrevious = 0.0
        for (sample in samples) {
            val current = sample + coefficient * previous - previousPrevious
            previousPrevious = previous
            previous = current
        }
        return previous * previous +
            previousPrevious * previousPrevious -
            coefficient * previous * previousPrevious
    }

    private fun correlation(
        chroma: DoubleArray,
        profile: DoubleArray,
        root: Int,
    ): Double {
        var dot = 0.0
        var profileSumSquares = 0.0
        var chromaSumSquares = 0.0
        for (i in 0 until 12) {
            val profileValue = profile[(i - root + 12) % 12]
            dot += chroma[i] * profileValue
            profileSumSquares += profileValue * profileValue
            chromaSumSquares += chroma[i] * chroma[i]
        }
        val denominator = sqrt(profileSumSquares * chromaSumSquares)
        if (denominator == 0.0) return 0.0
        return dot / denominator
    }

    private fun pitchClassFrequency(
        pitchClass: Int,
        octave: Int,
    ): Double {
        val semitonesFromA4 = pitchClass - 9 + octave * 12
        return REFERENCE_A4_HZ * 2.0.pow(semitonesFromA4 / 12.0)
    }

    private fun camelotMajor(root: Int): String =
        when (root) {
            0 -> "8B"
            1 -> "3B"
            2 -> "10B"
            3 -> "5B"
            4 -> "12B"
            5 -> "7B"
            6 -> "2B"
            7 -> "9B"
            8 -> "4B"
            9 -> "11B"
            10 -> "6B"
            11 -> "1B"
            else -> error("root ausserhalb 0..11")
        }

    private fun camelotMinor(root: Int): String =
        when (root) {
            0 -> "5A"
            1 -> "12A"
            2 -> "7A"
            3 -> "2A"
            4 -> "9A"
            5 -> "4A"
            6 -> "11A"
            7 -> "6A"
            8 -> "1A"
            9 -> "8A"
            10 -> "3A"
            11 -> "10A"
            else -> error("root ausserhalb 0..11")
        }

    companion object {
        const val DEFAULT_DECIMATION: Int = 5
        const val DEFAULT_WINDOW_SAMPLES: Int = 1_024

        private const val REFERENCE_A4_HZ = 440.0
        private val OCTAVES = -1..1
        private const val MIN_WINDOWS = 8

        private val MAJOR_PROFILE =
            doubleArrayOf(6.35, 2.23, 3.48, 2.33, 4.38, 4.09, 2.52, 5.19, 2.39, 3.66, 2.29, 2.88)
        private val MINOR_PROFILE =
            doubleArrayOf(6.33, 2.68, 3.52, 5.38, 2.60, 3.53, 2.54, 4.75, 3.98, 2.69, 3.34, 3.17)
    }
}

/** Tonart-Schaetzung inklusive Konfidenz 0..1 (Offtrack Phase 8). */
data class KeyEstimate(
    val camelotKey: String,
    val confidence: Float,
)
