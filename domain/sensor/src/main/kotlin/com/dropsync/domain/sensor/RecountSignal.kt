package com.dropsync.domain.sensor

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Signalverarbeitung fuer [SetRecount]: Raster, Nullphasen-Tiefpass, Autokorrelations-Periode,
 * Peak-Auswahl und Ketten-Bildung. Intern, weil die Parameter an die Nachzaehlung gebunden sind.
 */
internal object RecountSignal {
    /** Verkettet aufeinanderfolgende Peaks, deren Abstand 0,6 bis 1,5 Perioden betraegt. */
    fun linkSegments(
        indices: List<Int>,
        periodSamples: Double,
    ): List<List<Int>> {
        val segments = ArrayList<MutableList<Int>>()
        for (p in indices) {
            val current = segments.lastOrNull()
            val gap = if (current == null) 0 else p - current.last()
            if (current != null && gap >= LINK_MIN_PERIODS * periodSamples && gap <= LINK_MAX_PERIODS * periodSamples) {
                current.add(p)
            } else {
                segments.add(mutableListOf(p))
            }
        }
        return segments
    }

    class Grid(
        val values: DoubleArray,
        private val bad: BooleanArray,
        val interpolatedFraction: Double,
        val hasBadGap: Boolean,
    ) {
        fun isBad(
            from: Int,
            to: Int,
        ): Boolean {
            for (i in from..to) if (bad[i]) return true
            return false
        }
    }

    /** Legt die unregelmaessigen Rohsamples auf ein festes Raster (linear interpoliert). */
    fun resample(
        samples: List<SensorSample>,
        rateHz: Double,
        value: (SensorSample) -> Double,
    ): Grid? {
        val dtMs = 1_000.0 / rateHz
        val t0 = samples.first().timestampMs
        val spanMs = (samples.last().timestampMs - t0).toDouble()
        if (spanMs <= 0.0) return null
        val n = (spanMs / dtMs).toInt() + 1
        val values = DoubleArray(n)
        val bad = BooleanArray(n)
        var interpolated = 0
        var hasBad = false
        var j = 0
        for (k in 0 until n) {
            val t = t0 + k * dtMs
            while (j + 1 < samples.size - 1 && samples[j + 1].timestampMs <= t) j++
            val a = samples[j]
            val b = samples[min(j + 1, samples.size - 1)]
            val gap = (b.timestampMs - a.timestampMs).toDouble()
            val f = if (gap <= 0.0) 0.0 else ((t - a.timestampMs) / gap).coerceIn(0.0, 1.0)
            values[k] = value(a) + (value(b) - value(a)) * f
            if (gap > GAP_INTERPOLATED_MS) {
                interpolated++
                if (gap > SetRecount.MAX_BRIDGE_MS) {
                    bad[k] = true
                    hasBad = true
                }
            }
        }
        return Grid(values, bad, interpolated.toDouble() / n, hasBad)
    }

    /** Vorwaerts + rueckwaerts gefiltertes Tiefpass-Signal (Nullphase, ca. 5 Hz Grenzfrequenz). */
    fun zeroPhaseLowPass(
        x: DoubleArray,
        rateHz: Double,
    ): DoubleArray {
        val dt = 1.0 / rateHz
        val rc = 1.0 / (2.0 * PI * CUTOFF_HZ)
        val alpha = dt / (rc + dt)
        val y = DoubleArray(x.size)
        var s = x[0]
        for (i in x.indices) {
            s += alpha * (x[i] - s)
            y[i] = s
        }
        s = y[y.size - 1]
        for (i in x.indices.reversed()) {
            s += alpha * (y[i] - s)
            y[i] = s
        }
        return y
    }

    /**
     * Periode ueber die normierte Autokorrelation. Gewaehlt wird der KLEINSTE Lag, dessen
     * Wert mindestens [ACF_PEAK_FRACTION] des besten Wertes erreicht - sonst wuerde ein
     * Vielfaches der Periode (zwei Reps) gewinnen.
     */
    fun estimatePeriod(
        x: DoubleArray,
        rateHz: Double,
    ): Double? {
        val minLag = (rateHz * MIN_PERIOD_S).toInt().coerceAtLeast(2)
        val maxLag = min((rateHz * MAX_PERIOD_S).toInt(), x.size / 2)
        if (maxLag <= minLag + 2) return null
        val mean = x.average()
        val c = DoubleArray(x.size) { x[it] - mean }
        var zero = 0.0
        for (v in c) zero += v * v
        if (zero < 1e-12) return null
        val acf = DoubleArray(maxLag + 2)
        var best = 0.0
        for (lag in minLag..maxLag + 1) {
            var d = 0.0
            for (i in 0 until c.size - lag) d += c[i] * c[i + lag]
            acf[lag] = d / zero
            if (lag <= maxLag) best = max(best, acf[lag])
        }
        if (best < MIN_PERIODICITY) return null
        for (lag in minLag + 1..maxLag) {
            val isLocalMax = acf[lag] >= acf[lag - 1] && acf[lag] > acf[lag + 1]
            if (isLocalMax && acf[lag] >= ACF_PEAK_FRACTION * best && acf[lag] >= MIN_PERIODICITY) {
                return lag / rateHz
            }
        }
        return null
    }

    class Peaks(
        val indices: List<Int>,
        val medianProminence: Double,
    )

    /**
     * Lokale Maxima mit Prominenz relativ zum Median der markanten Peaks, mit
     * Mindestabstand (bei Konflikt gewinnt der prominentere).
     */
    fun acceptedPeaks(
        x: DoubleArray,
        range: Double,
        minDistance: Int,
        scan: Int,
    ): Peaks {
        val n = x.size
        val candidates = ArrayList<Pair<Int, Double>>()
        for (i in 1 until n - 1) {
            if (x[i] > x[i - 1] && x[i] >= x[i + 1] && x[i] > 0.0) {
                val prom = prominence(x, i, scan)
                if (prom >= CANDIDATE_PROMINENCE_FRACTION * range) candidates.add(i to prom)
            }
        }
        if (candidates.isEmpty()) return Peaks(emptyList(), 0.0)
        val maxProm = candidates.maxOf { it.second }
        val strong = candidates.filter { it.second >= 0.5 * maxProm }.map { it.second }.sorted()
        val reference = strong[strong.size / 2]
        val accepted = ArrayList<Pair<Int, Double>>()
        for (cand in candidates.filter { it.second >= ACCEPT_FRACTION * reference }.sortedByDescending { it.second }) {
            if (accepted.none { abs(it.first - cand.first) < minDistance }) accepted.add(cand)
        }
        return Peaks(accepted.map { it.first }.sorted(), reference)
    }

    private fun prominence(
        x: DoubleArray,
        i: Int,
        scan: Int,
    ): Double {
        var leftMin = x[i]
        var k = i - 1
        while (k >= 0 && i - k <= scan && x[k] <= x[i]) {
            leftMin = min(leftMin, x[k])
            k--
        }
        var rightMin = x[i]
        k = i + 1
        while (k < x.size && k - i <= scan && x[k] <= x[i]) {
            rightMin = min(rightMin, x[k])
            k++
        }
        return x[i] - max(leftMin, rightMin)
    }

    /** Anzahl zusammenhaengender Samples um den Peak mit mindestens halber Peak-Hoehe. */
    fun halfHeightWidth(
        x: DoubleArray,
        i: Int,
    ): Int {
        val half = x[i] * 0.5
        var left = i
        while (left > 0 && x[left - 1] >= half) left--
        var right = i
        while (right < x.size - 1 && x[right + 1] >= half) right++
        return right - left + 1
    }

    fun median(x: DoubleArray): Double {
        val s = x.sortedArray()
        return s[s.size / 2]
    }

    fun coefficientOfVariation(values: List<Double>): Double {
        if (values.size < 2) return 0.0
        val mean = values.average()
        if (mean <= 0.0) return Double.MAX_VALUE
        val variance = values.sumOf { (it - mean) * (it - mean) } / values.size
        return sqrt(variance) / mean
    }

    /** Ab hier zaehlt eine Luecke als "interpoliert" (ca. 2,5 Samples bei 50 Hz). */
    private const val GAP_INTERPOLATED_MS = 50L
    private const val CUTOFF_HZ = 5.0
    private const val MIN_PERIOD_S = 0.6
    private const val MAX_PERIOD_S = 8.0
    private const val LINK_MIN_PERIODS = 0.6
    private const val LINK_MAX_PERIODS = 1.5
    private const val MIN_PERIODICITY = 0.25
    private const val ACF_PEAK_FRACTION = 0.8
    private const val CANDIDATE_PROMINENCE_FRACTION = 0.25
    private const val ACCEPT_FRACTION = 0.4
}
