package com.dropsync.domain.sensor

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
        val signal = prepareSignal(samples, rotationAxis, gyroBias, sampleRateHz) ?: return null
        val chain = peakChain(signal, sampleRateHz)
        val cycles = countCycles(signal, chain, sampleRateHz)
        val confident = isConfident(signal.grid, chain, cycles)
        return Result(
            count = cycles.counted.size,
            liveCount = liveCount,
            periodMs = chain.period?.times(1_000.0),
            confidence = if (confident) Confidence.HIGH else Confidence.LOW,
            interpolatedFraction = signal.grid.interpolatedFraction,
            hasUnbridgedGap = signal.grid.hasBadGap || cycles.skippedInGap,
        )
    }

    /** Projiziertes, geglaettetes und auf den Median bezogenes Signal eines Satzes. */
    private class Signal(
        val grid: RecountSignal.Grid,
        val smooth: DoubleArray,
        val range: Double,
    )

    /** Regelmaessige Kette von Peaks (siehe [peakChain]). */
    private class Chain(
        val period: Double?,
        val periodSamples: Double,
        val peaks: RecountSignal.Peaks,
        val main: List<List<Int>>,
        val hasOutliers: Boolean,
    )

    private class Cycles(
        val counted: List<Int>,
        val intervals: List<Double>,
        val skippedInGap: Boolean,
    )

    private enum class CycleOutcome { COUNTED, NO_NEGATIVE_PHASE, IN_GAP }

    private fun prepareSignal(
        samples: List<SensorSample>,
        rotationAxis: List<Double>,
        gyroBias: List<Double>,
        sampleRateHz: Double,
    ): Signal? {
        val usable =
            samples.size >= MIN_SAMPLES && sampleRateHz > 0.0 && rotationAxis.size >= 3 && gyroBias.size >= 3
        val norm = if (usable) sqrt(rotationAxis.take(3).sumOf { it * it }) else 0.0
        val grid =
            if (norm < MIN_AXIS_NORM) {
                null
            } else {
                RecountSignal.resample(samples, sampleRateHz) { s ->
                    val dx = s.gx - gyroBias[0]
                    val dy = s.gy - gyroBias[1]
                    val dz = s.gz - gyroBias[2]
                    (dx * rotationAxis[0] + dy * rotationAxis[1] + dz * rotationAxis[2]) / norm
                }
            }
        val smooth = grid?.takeIf { it.values.size >= MIN_SAMPLES }?.let { smoothed(it, sampleRateHz) }
        val range = smooth?.let(::spread) ?: 0.0
        return if (grid == null || smooth == null || range < MIN_RANGE) null else Signal(grid, smooth, range)
    }

    private fun smoothed(
        grid: RecountSignal.Grid,
        sampleRateHz: Double,
    ): DoubleArray {
        val smooth = RecountSignal.zeroPhaseLowPass(grid.values, sampleRateHz)
        val baseline = RecountSignal.median(smooth)
        for (i in smooth.indices) smooth[i] -= baseline
        return smooth
    }

    /** Abstand zwischen dem 95- und dem 5-Prozent-Perzentil: robuste Signalspanne. */
    private fun spread(smooth: DoubleArray): Double {
        val sorted = smooth.sortedArray()
        val n = sorted.size
        return sorted[((n - 1) * UPPER_PERCENTILE).toInt()] - sorted[((n - 1) * LOWER_PERCENTILE).toInt()]
    }

    /**
     * Regelmaessige Kette: nur Peaks im Abstand von ca. einer Periode gehoeren zum Satz. Einzelne
     * Ausreisser (Zappeln vor dem Satz, Teilreps danach) zaehlen nicht und machen die Analyse
     * unsicher - eine falsche Korrektur waere schlimmer als keine. Dazu die Form: die Halbwerts-
     * breite eines Rep-Lappens liegt im Satz eng beieinander, ein kurzer Ruck ist deutlich schmaler.
     */
    private fun peakChain(
        signal: Signal,
        sampleRateHz: Double,
    ): Chain {
        val period = RecountSignal.estimatePeriod(signal.smooth, sampleRateHz)
        val minDistance =
            ((period ?: DEFAULT_MIN_PERIOD_S) * sampleRateHz * MIN_DISTANCE_FRACTION).toInt().coerceAtLeast(3)
        val scan = (sampleRateHz * MAX_PROMINENCE_SCAN_S).toInt()
        val peaks = RecountSignal.acceptedPeaks(signal.smooth, signal.range, minDistance, scan)
        val periodSamples = (period ?: DEFAULT_EXCURSION_PERIOD_S) * sampleRateHz

        val widths = peaks.indices.map { RecountSignal.halfHeightWidth(signal.smooth, it) }
        val medianWidth = if (widths.isEmpty()) 0 else widths.sorted()[widths.size / 2]
        val shaped =
            peaks.indices.filterIndexed { i, _ ->
                widths[i] >= MIN_WIDTH_RATIO * medianWidth && widths[i] <= MAX_WIDTH_RATIO * medianWidth
            }
        val segments = RecountSignal.linkSegments(shaped, periodSamples)
        val main =
            segments
                .filter { it.size >= MIN_SEGMENT_REPS }
                .ifEmpty { listOfNotNull(segments.maxByOrNull { it.size }) }
        val hasOutliers = shaped.size < peaks.indices.size || segments.size > main.size
        return Chain(period, periodSamples, peaks, main, hasOutliers)
    }

    /**
     * Vollzyklus: nach jedem Peak muss vor dem naechsten (bzw. innerhalb von [EXCURSION_PERIODS]
     * Perioden) eine negative Phase liegen. Peaks in unueberbrueckten Luecken zaehlen nicht.
     */
    private fun countCycles(
        signal: Signal,
        chain: Chain,
        sampleRateHz: Double,
    ): Cycles {
        val counted = ArrayList<Int>()
        val intervals = ArrayList<Double>()
        var skippedInGap = false
        for (segment in chain.main) {
            var previous: Int? = null
            for (p in segment) {
                when (cycleOutcome(signal, chain, p)) {
                    CycleOutcome.COUNTED -> {
                        counted.add(p)
                        previous?.let { intervals.add((p - it) / sampleRateHz) }
                        previous = p
                    }

                    CycleOutcome.IN_GAP -> {
                        skippedInGap = true
                    }

                    CycleOutcome.NO_NEGATIVE_PHASE -> {
                        Unit
                    }
                }
            }
        }
        return Cycles(counted, intervals, skippedInGap)
    }

    private fun cycleOutcome(
        signal: Signal,
        chain: Chain,
        peak: Int,
    ): CycleOutcome {
        val last = signal.smooth.size - 1
        val reach = (chain.periodSamples * EXCURSION_PERIODS).toInt()
        val nextOverall = chain.peaks.indices.firstOrNull { it > peak }
        val end = min(nextOverall ?: last, min(last, peak + reach))
        var minValue = Double.MAX_VALUE
        for (k in peak..end) minValue = min(minValue, signal.smooth[k])
        val depth = NEGATIVE_DEPTH_FRACTION * chain.peaks.medianProminence
        return when {
            minValue > -depth -> CycleOutcome.NO_NEGATIVE_PHASE
            signal.grid.isBad(max(0, peak - BAD_MARGIN), min(last, end)) -> CycleOutcome.IN_GAP
            else -> CycleOutcome.COUNTED
        }
    }

    private fun isConfident(
        grid: RecountSignal.Grid,
        chain: Chain,
        cycles: Cycles,
    ): Boolean {
        val regular =
            chain.period != null &&
                cycles.counted.size >= MIN_REPS_FOR_CONFIDENCE &&
                !chain.hasOutliers &&
                RecountSignal.coefficientOfVariation(cycles.intervals) <= MAX_INTERVAL_CV
        val clean = !grid.hasBadGap && !cycles.skippedInGap && grid.interpolatedFraction <= MAX_INTERPOLATED_FRACTION
        return regular && clean
    }

    private const val MIN_AXIS_NORM = 1e-9
    private const val UPPER_PERCENTILE = 0.95
    private const val LOWER_PERCENTILE = 0.05

    const val DEFAULT_RATE_HZ = 50.0

    /** Laengere Luecken sind nicht mehr belastbar ueberbrueckt (wie beim Live-Zaehler ~500 ms plus Reserve). */
    const val MAX_BRIDGE_MS = 600L
    private const val MIN_SAMPLES = 150
    private const val MIN_RANGE = 1e-6
    private const val DEFAULT_MIN_PERIOD_S = 1.0
    private const val DEFAULT_EXCURSION_PERIOD_S = 2.0
    private const val EXCURSION_PERIODS = 0.9
    private const val MIN_SEGMENT_REPS = 3
    private const val MIN_WIDTH_RATIO = 0.5
    private const val MAX_WIDTH_RATIO = 2.0
    private const val MIN_DISTANCE_FRACTION = 0.6
    private const val MAX_PROMINENCE_SCAN_S = 6.0
    private const val NEGATIVE_DEPTH_FRACTION = 0.15
    private const val BAD_MARGIN = 10
    private const val MIN_REPS_FOR_CONFIDENCE = 3
    private const val MAX_INTERPOLATED_FRACTION = 0.10
    private const val MAX_INTERVAL_CV = 0.40
}
