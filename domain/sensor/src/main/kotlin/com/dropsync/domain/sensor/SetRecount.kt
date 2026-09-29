package com.dropsync.domain.sensor

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Unabhaengige Zweitmeinung zur Live-Zaehlung: zaehlt die Wiederholungen eines
 * ABGESCHLOSSENEN Satzes offline aus den Rohsamples nach.
 *
 * Warum eine zweite Zaehlung? Der Live-Zaehler entscheidet kausal, mit einer festen
 * kalibrierten Schwelle, Filter-Einschwingzeit und Zustandsautomat. Nach dem Satz liegt
 * das ganze Signal vor, und diese Beschraenkungen entfallen:
 * - Nullphasen-Filter (vorwaerts + rueckwaerts): keine Verzoegerung, kein Einschwingen.
 * - Luecken werden ueber das ganze Signal linear ueberbrueckt.
 * - Die Peak-Hoehe wird relativ zum MEDIAN der Peaks dieses Satzes bewertet statt gegen
 *   die Kalibrierung - unempfindlich gegen ein veraltetes Profil oder Ermuedung.
 * - Die Autokorrelations-Periode gibt den Mindestabstand vor (kein Doppelpeak je Rep).
 *
 * Das Ergebnis ist ein VORSCHLAG. Es ueberschreibt nie still: die UI zeigt "Analyse: n,
 * live: m" und laesst den Nutzer uebernehmen. Genutzt wird es nur bei [Confidence.HIGH].
 *
 * Bewusst gleiche Semantik wie der Live-Zaehler: eine Rep ist ein positiver Peak
 * (konzentrische Phase in Richtung der kalibrierten Achse), auf den eine negative
 * Phase folgt. Ein Peak ohne anschliessende negative Phase (Satz mitten in der Rep
 * gestoppt) zaehlt nicht.
 */
object SetRecount {
    enum class Confidence { HIGH, LOW }

    data class Result(
        /** Nachgezaehlte Wiederholungen. */
        val count: Int,
        /** Live-Zaehlung des Satzes zum Vergleich. */
        val liveCount: Int,
        /** Autokorrelations-Periode in ms (null, wenn das Signal nicht periodisch genug ist). */
        val periodMs: Double?,
        val confidence: Confidence,
        /** Anteil der Gitterpunkte, die durch Luecken-Interpolation entstanden sind. */
        val interpolatedFraction: Double,
        /** true, wenn eine Luecke laenger als [MAX_BRIDGE_MS] die Analyse unsicher macht. */
        val hasUnbridgedGap: Boolean,
    ) {
        val agrees: Boolean get() = count == liveCount

        /** true, wenn die UI der Person eine Korrektur anbieten soll. */
        val isSuggestion: Boolean get() = confidence == Confidence.HIGH && count != liveCount
    }

    /**
     * @param rotationAxis kalibrierte Projektionsachse (wird normiert)
     * @param gyroBias Bias, der vor der Projektion abgezogen wird
     * @param liveCount Live-Zaehlung des Satzes (nur zum Vergleich)
     * @param sampleRateHz Zielraster; die Rohsamples duerfen unregelmaessig sein
     * @return null, wenn zu wenig Signal vorliegt oder es keine Bewegung gibt
     */
    fun count(
        samples: List<SensorSample>,
        rotationAxis: List<Double>,
        gyroBias: List<Double>,
        liveCount: Int,
        sampleRateHz: Double = DEFAULT_RATE_HZ,
    ): Result? {
        if (samples.size < MIN_SAMPLES || sampleRateHz <= 0.0) return null
        if (rotationAxis.size < 3 || gyroBias.size < 3) return null
        val norm = sqrt(rotationAxis[0] * rotationAxis[0] + rotationAxis[1] * rotationAxis[1] + rotationAxis[2] * rotationAxis[2])
        if (norm < 1e-9) return null
        val ux = rotationAxis[0] / norm
        val uy = rotationAxis[1] / norm
        val uz = rotationAxis[2] / norm

        val grid = resample(samples, sampleRateHz) { s ->
            (s.gx - gyroBias[0]) * ux + (s.gy - gyroBias[1]) * uy + (s.gz - gyroBias[2]) * uz
        } ?: return null
        val n = grid.values.size
        if (n < MIN_SAMPLES) return null

        val smooth = zeroPhaseLowPass(grid.values, sampleRateHz)
        val baseline = median(smooth)
        for (i in 0 until n) smooth[i] -= baseline

        val sorted = smooth.sortedArray()
        val hi = sorted[((n - 1) * 0.95).toInt()]
        val lo = sorted[((n - 1) * 0.05).toInt()]
        val range = hi - lo
        if (range < MIN_RANGE) return null

        val period = estimatePeriod(smooth, sampleRateHz)
        val minDistance = ((period ?: DEFAULT_MIN_PERIOD_S) * sampleRateHz * MIN_DISTANCE_FRACTION).toInt().coerceAtLeast(3)

        val peaks = acceptedPeaks(smooth, range, minDistance, (sampleRateHz * MAX_PROMINENCE_SCAN_S).toInt())
        val medianProminence = peaks.medianProminence

        // Regelmaessige Kette: nur Peaks im Abstand von ca. einer Periode gehoeren zum Satz.
        // Einzelne Ausreisser (Zappeln vor dem Satz, Teilreps danach) zaehlen nicht und
        // machen die Analyse unsicher - eine falsche Korrektur waere schlimmer als keine.
        val periodSamples = (period ?: DEFAULT_EXCURSION_PERIOD_S) * sampleRateHz
        // Form: die Halbwertsbreite eines Rep-Lappens liegt im Satz eng beieinander. Ein
        // kurzer Ruck oder ein Teilrep ist deutlich schmaler und keine Wiederholung.
        val widths = peaks.indices.map { halfHeightWidth(smooth, it) }
        val medianWidth = if (widths.isEmpty()) 0 else widths.sorted()[widths.size / 2]
        val shaped =
            peaks.indices.filterIndexed { i, _ ->
                widths[i] >= MIN_WIDTH_RATIO * medianWidth && widths[i] <= MAX_WIDTH_RATIO * medianWidth
            }
        val segments = linkSegments(shaped, periodSamples)
        val main = segments.filter { it.size >= MIN_SEGMENT_REPS }.ifEmpty { listOfNotNull(segments.maxByOrNull { it.size }) }
        val hasOutliers = shaped.size < peaks.indices.size || segments.size > main.size

        // Vollzyklus: nach jedem Peak muss vor dem naechsten (bzw. innerhalb von
        // EXCURSION_PERIODS Perioden) eine negative Phase liegen. Peaks in
        // unueberbrueckten Luecken zaehlen nicht.
        val depth = NEGATIVE_DEPTH_FRACTION * medianProminence
        val reach = (periodSamples * EXCURSION_PERIODS).toInt()
        val counted = ArrayList<Int>()
        val intervals = ArrayList<Double>()
        var skippedInGap = false
        for (segment in main) {
            var previous: Int? = null
            for (p in segment) {
                val nextOverall = peaks.indices.firstOrNull { it > p }
                val end = min(nextOverall ?: (n - 1), min(n - 1, p + reach))
                var minValue = Double.MAX_VALUE
                for (k in p..end) minValue = min(minValue, smooth[k])
                if (minValue > -depth) continue
                if (grid.isBad(max(0, p - BAD_MARGIN), min(n - 1, end))) {
                    skippedInGap = true
                    continue
                }
                counted.add(p)
                previous?.let { intervals.add((p - it) / sampleRateHz) }
                previous = p
            }
        }

        val cv = coefficientOfVariation(intervals)
        val confident =
            period != null &&
                counted.size >= MIN_REPS_FOR_CONFIDENCE &&
                !hasOutliers &&
                !grid.hasBadGap &&
                !skippedInGap &&
                grid.interpolatedFraction <= MAX_INTERPOLATED_FRACTION &&
                cv <= MAX_INTERVAL_CV
        return Result(
            count = counted.size,
            liveCount = liveCount,
            periodMs = period?.times(1_000.0),
            confidence = if (confident) Confidence.HIGH else Confidence.LOW,
            interpolatedFraction = grid.interpolatedFraction,
            hasUnbridgedGap = grid.hasBadGap || skippedInGap,
        )
    }

    /** Verkettet aufeinanderfolgende Peaks, deren Abstand 0,6 bis 1,5 Perioden betraegt. */
    private fun linkSegments(indices: List<Int>, periodSamples: Double): List<List<Int>> {
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

    private class Grid(
        val values: DoubleArray,
        private val bad: BooleanArray,
        val interpolatedFraction: Double,
        val hasBadGap: Boolean,
    ) {
        fun isBad(from: Int, to: Int): Boolean {
            for (i in from..to) if (bad[i]) return true
            return false
        }
    }

    /** Legt die unregelmaessigen Rohsamples auf ein festes Raster (linear interpoliert). */
    private fun resample(samples: List<SensorSample>, rateHz: Double, value: (SensorSample) -> Double): Grid? {
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
                if (gap > MAX_BRIDGE_MS) {
                    bad[k] = true
                    hasBad = true
                }
            }
        }
        return Grid(values, bad, interpolated.toDouble() / n, hasBad)
    }

    /** Vorwaerts + rueckwaerts gefiltertes Tiefpass-Signal (Nullphase, ca. 5 Hz Grenzfrequenz). */
    private fun zeroPhaseLowPass(x: DoubleArray, rateHz: Double): DoubleArray {
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
    private fun estimatePeriod(x: DoubleArray, rateHz: Double): Double? {
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

    private class Peaks(val indices: List<Int>, val medianProminence: Double)

    /**
     * Lokale Maxima mit Prominenz relativ zum Median der markanten Peaks, mit
     * Mindestabstand (bei Konflikt gewinnt der prominentere).
     */
    private fun acceptedPeaks(x: DoubleArray, range: Double, minDistance: Int, scan: Int): Peaks {
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

    private fun prominence(x: DoubleArray, i: Int, scan: Int): Double {
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
    private fun halfHeightWidth(x: DoubleArray, i: Int): Int {
        val half = x[i] * 0.5
        var left = i
        while (left > 0 && x[left - 1] >= half) left--
        var right = i
        while (right < x.size - 1 && x[right + 1] >= half) right++
        return right - left + 1
    }

    private fun median(x: DoubleArray): Double {
        val s = x.sortedArray()
        return s[s.size / 2]
    }

    private fun coefficientOfVariation(values: List<Double>): Double {
        if (values.size < 2) return 0.0
        val mean = values.average()
        if (mean <= 0.0) return Double.MAX_VALUE
        val variance = values.sumOf { (it - mean) * (it - mean) } / values.size
        return sqrt(variance) / mean
    }

    const val DEFAULT_RATE_HZ = 50.0

    /** Ab hier zaehlt eine Luecke als "interpoliert" (ca. 2,5 Samples bei 50 Hz). */
    private const val GAP_INTERPOLATED_MS = 50L

    /** Laengere Luecken sind nicht mehr belastbar ueberbrueckt (wie beim Live-Zaehler ~500 ms plus Reserve). */
    const val MAX_BRIDGE_MS = 600L

    private const val MIN_SAMPLES = 150
    private const val MIN_RANGE = 1e-6
    private const val CUTOFF_HZ = 5.0
    private const val MIN_PERIOD_S = 0.6
    private const val MAX_PERIOD_S = 8.0
    private const val DEFAULT_MIN_PERIOD_S = 1.0
    private const val DEFAULT_EXCURSION_PERIOD_S = 2.0
    private const val EXCURSION_PERIODS = 0.9
    private const val LINK_MIN_PERIODS = 0.6
    private const val LINK_MAX_PERIODS = 1.5
    private const val MIN_SEGMENT_REPS = 3
    private const val MIN_WIDTH_RATIO = 0.5
    private const val MAX_WIDTH_RATIO = 2.0
    private const val MIN_PERIODICITY = 0.25
    private const val ACF_PEAK_FRACTION = 0.8
    private const val MIN_DISTANCE_FRACTION = 0.6
    private const val MAX_PROMINENCE_SCAN_S = 6.0
    private const val CANDIDATE_PROMINENCE_FRACTION = 0.25
    private const val ACCEPT_FRACTION = 0.4
    private const val NEGATIVE_DEPTH_FRACTION = 0.15
    private const val BAD_MARGIN = 10
    private const val MIN_REPS_FOR_CONFIDENCE = 3
    private const val MAX_INTERPOLATED_FRACTION = 0.10
    private const val MAX_INTERVAL_CV = 0.40
}
