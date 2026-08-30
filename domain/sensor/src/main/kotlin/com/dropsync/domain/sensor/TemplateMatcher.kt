package com.dropsync.domain.sensor

import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sqrt

/** Result of a template match (port of template_matcher.dart MatchResult). */
data class MatchResult(
    /** Aehnlichkeit in [-1, 1]; 1 = perfekte Uebereinstimmung. */
    val correlation: Double,
    val accepted: Boolean,
    val noTemplate: Boolean = false,
)

/**
 * Verfahren zum Vergleich von Rep-Fenster und Template.
 *
 * [NCC] ist die punktweise normalisierte Kreuzkorrelation (bisheriges
 * Verhalten). [DTW] nutzt Dynamic Time Warping mit begrenztem
 * Warping-Fenster.
 *
 * Warum DTW (P2-Recherche): NCC resampled beide Signale auf eine feste
 * Laenge und vergleicht dann Index gegen Index. Damit bestraft es
 * Tempo-Variation INNERHALB einer Wiederholung — genau das, was bei
 * Ermuedung passiert: die Exzentrik wird langsamer, die Konzentrik bleibt
 * explosiv. Die Kurvenform ist dieselbe, der NCC-Wert faellt trotzdem unter
 * die Schwelle und die Rep wird verworfen. DTW toleriert exakt diese
 * nichtlineare Zeitverzerrung, weil es Punkte flexibel einander zuordnet.
 * Das MDPI-Paper "Development of AI Algorithm for Weight Training Using
 * IMU" (2022) berichtet mit Half-DTW 96 % ueber 15 Uebungstypen.
 */
enum class TemplateMatchMode { NCC, DTW }

/**
 * Template matching gegen einen Pool bestaetigter Rep-Fenster.
 *
 * Resampelt das Fenster auf [TEMPLATE_LENGTH], normalisiert beide Signale
 * (Mittelwert 0, Standardabweichung 1) und vergleicht sie im gewaehlten
 * [mode]. Beide Verfahren sind fuer 50 Hz echtzeittauglich: NCC ist O(N),
 * DTW mit Sakoe-Chiba-Band O(N * W) — bei N = 64 und W = 8 rund 1000
 * Operationen je Template, also nichts bei ~12 Reps pro Minute.
 *
 * Punkt 5 (Multi-Template): statt eines einzelnen Templates haelt der
 * Matcher einen Pool der letzten [poolSize] bestaetigten Rep-Windows. Der
 * beste Wert gegen alle Templates gewinnt; [addToPool] erweitert den
 * Pool FIFO. So faengt der Matcher Formdrift (Ermuedung) ab, die das
 * starre Einzel-Template unter die Schwelle druecken wuerde.
 */
class TemplateMatcher(
    private val threshold: Double = 0.7,
    private val poolSize: Int = 5,
    private val mode: TemplateMatchMode = TemplateMatchMode.DTW,
) {
    private val templates: MutableList<List<Double>> = mutableListOf()

    /** Sets the learned rep template (any length; resampled internally). */
    fun setTemplate(rawTemplate: List<Double>) {
        if (rawTemplate.size < 4) {
            templates.clear()
            return
        }
        templates.clear()
        normalize(resample(rawTemplate, TEMPLATE_LENGTH))?.let { templates.add(it) }
    }

    /**
     * Punkt 5: nimmt ein bestaetigtes Rep-Window in den Pool auf
     * (normalisiert + resampled, FIFO mit [poolSize] Eintraegen).
     */
    fun addToPool(rawWindow: List<Double>) {
        if (rawWindow.size < 4) return
        val normalized = normalize(resample(rawWindow, TEMPLATE_LENGTH)) ?: return
        templates.add(normalized)
        if (templates.size > poolSize) {
            templates.removeAt(0)
        }
    }

    /**
     * Matches a peak window against the templates. Callers pass
     * `peak.window` directly — never an extended window (Befund-C fix,
     * PHASE_VALIDATOR_FIX_AUDIT_2026-08-05 section 7).
     */
    fun match(window: List<Double>): MatchResult {
        if (templates.isEmpty()) return MatchResult(0.0, accepted = true, noTemplate = true)
        if (window.size < 4) return MatchResult(0.0, accepted = false)
        val normalized =
            normalize(resample(window, TEMPLATE_LENGTH))
                ?: return MatchResult(0.0, accepted = false)
        val best =
            when (mode) {
                TemplateMatchMode.NCC -> templates.maxOf { crossCorrelate(it, normalized) }
                TemplateMatchMode.DTW -> templates.maxOf { dtwSimilarity(it, normalized) }
            }
        return MatchResult(best, accepted = best >= threshold)
    }

    val hasTemplate: Boolean
        get() = templates.isNotEmpty()

    /** Anzahl der Templates im Pool (Punkt 5: fuer Tests). */
    val poolCount: Int
        get() = templates.size

    fun clearTemplate() {
        templates.clear()
    }

    companion object {
        /** Fixed template length (64 samples = 1.28 s at 50 Hz). */
        const val TEMPLATE_LENGTH = 64

        /**
         * Breite des Sakoe-Chiba-Bands in Samples: eine Zuordnung darf um
         * hoechstens so viele Indizes verschoben sein. 8 von 64 erlaubt
         * ~12 % Tempo-Verzerrung je Phase — genug fuer Ermuedungsdrift, zu
         * wenig fuer eine voellig andere Bewegung.
         */
        const val DTW_BAND = 8

        internal fun resample(
            input: List<Double>,
            targetLength: Int,
        ): List<Double> {
            if (input.size == targetLength) return input.toList()
            val result = DoubleArray(targetLength)
            val ratio = (input.size - 1).toDouble() / (targetLength - 1)
            for (i in 0 until targetLength) {
                val srcPos = i * ratio
                val srcIdx = srcPos.toInt()
                val frac = srcPos - srcIdx
                result[i] =
                    if (srcIdx >= input.size - 1) {
                        input[input.size - 1]
                    } else {
                        input[srcIdx] * (1.0 - frac) + input[srcIdx + 1] * frac
                    }
            }
            return result.toList()
        }

        /** Mean 0, std 1; null for a (near-)constant signal. */
        internal fun normalize(input: List<Double>): List<Double>? {
            val n = input.size
            if (n == 0) return null
            val mean = input.sum() / n
            val variance = input.sumOf { (it - mean) * (it - mean) } / n
            val std = sqrt(variance)
            if (std < 1e-10) return null
            return input.map { (it - mean) / std }
        }

        internal fun crossCorrelate(
            a: List<Double>,
            b: List<Double>,
        ): Double {
            val n = min(a.size, b.size)
            if (n == 0) return 0.0
            var sum = 0.0
            for (i in 0 until n) sum += a[i] * b[i]
            return (sum / n).coerceIn(-1.0, 1.0)
        }

        /**
         * DTW-Aehnlichkeit in [-1, 1], damit sie dieselbe Schwelle wie NCC
         * benutzen kann.
         *
         * Grundlage ist die klassische DTW-Kostenmatrix mit
         * Sakoe-Chiba-Band ([DTW_BAND]) und absoluter Differenz als lokalem
         * Abstand. Die mittlere Kosten pro zugeordnetem Punkt werden dann
         * auf eine Aehnlichkeit abgebildet: bei normalisierten Signalen
         * (Standardabweichung 1) liegt eine mittlere Abweichung von 2.0
         * bereits bei "voellig unaehnlich", deshalb
         * `similarity = 1 - meanCost` mit Deckel bei -1.
         *
         * Speicher: zwei Zeilen statt der vollen Matrix (O(N) statt O(N^2)).
         */
        internal fun dtwSimilarity(
            a: List<Double>,
            b: List<Double>,
        ): Double {
            val n = a.size
            val m = b.size
            if (n == 0 || m == 0) return -1.0
            val band = DTW_BAND.coerceAtLeast(abs(n - m))

            var previous = DoubleArray(m + 1) { Double.POSITIVE_INFINITY }
            var current = DoubleArray(m + 1) { Double.POSITIVE_INFINITY }
            previous[0] = 0.0

            for (i in 1..n) {
                current.fill(Double.POSITIVE_INFINITY)
                val from = max(1, i - band)
                val to = min(m, i + band)
                for (j in from..to) {
                    val cost = abs(a[i - 1] - b[j - 1])
                    val best =
                        min(
                            previous[j], // Einfuegen
                            min(
                                current[j - 1], // Loeschen
                                previous[j - 1], // Zuordnen
                            ),
                        )
                    current[j] = cost + best
                }
                val swap = previous
                previous = current
                current = swap
            }

            val total = previous[m]
            if (!total.isFinite()) return -1.0
            // Pfadlaenge liegt zwischen max(n, m) und n + m; der Mittelwert
            // ueber max(n, m) ist die konservative (strengere) Normierung.
            val meanCost = total / max(n, m)
            return (1.0 - meanCost).coerceIn(-1.0, 1.0)
        }

        private fun max(
            a: Int,
            b: Int,
        ): Int = if (a > b) a else b

        private fun max(
            a: Double,
            b: Double,
        ): Double = if (a > b) a else b
    }
}
