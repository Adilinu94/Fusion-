package com.dropsync.domain.audio

import kotlin.math.sqrt

/**
 * On-Device-Drop-/Onset-Erkennung (Marker/Waveform-Plan Phase 5):
 * bewusst klassische Signalverarbeitung, kein ML — nachvollziehbar,
 * offline, ohne Trainingsdaten. Ehrlich benannt: erkannt werden
 * energetische Spruenge, keine musikalische Bedeutung; Ergebnisse sind
 * immer Kandidaten (AUTO_DETECTED, isEnabled = false), nie Automatik.
 */
object OnsetDetection {
    /** Gleitendes Schwellwert-Fenster (Anzahl Energie-Fenster, ~1 s bei 25 ms). */
    const val DEFAULT_THRESHOLD_WINDOW: Int = 40

    /** Schwellwert = Mittelwert + k * Standardabweichung im Fenster. */
    const val DEFAULT_K: Double = 2.5

    /**
     * Mindestabstand zwischen zwei Kandidaten; Groessenordnung der
     * MIN_DROPSYNC_DURATION_MS-Schwelle der Timer-Domaene (5 s).
     */
    const val DEFAULT_MIN_SPACING_MS: Long = 5_000L

    /** Begrenzung auf die staerksten N Kandidaten pro Track (3-5). */
    const val DEFAULT_MAX_CANDIDATES: Int = 5

    /**
     * Absoluter Mindestsprung der RMS-Energie (normalisiert auf [0..1]):
     * der gleitende Schwellwert allein feuert statistisch auch auf
     * Rauschen; ein echter Drop springt deutlich staerker.
     */
    const val DEFAULT_MIN_NOVELTY: Double = 0.05

    /**
     * Liefert die Positionen (Millisekunden) der staerksten
     * Energie-Anstiege in [energyWindows] (RMS je Fenster,
     * [windowDurationMs] Millisekunden pro Fenster), zeitlich aufsteigend.
     *
     * 1. Novelty = positive Differenz aufeinanderfolgender Fenster.
     * 2. Peak-Picking ueber gleitendem Schwellwert (Mittel + k * Stdabw),
     *    damit leise und laute Tracks gleich behandelt werden; ein
     *    absoluter Mindestsprung [minNovelty] filtert Rauschen.
     * 3. Mindestabstand zwischen Kandidaten, damit nicht jeder Beat als
     *    eigener "Drop" zaehlt.
     * 4. Top-N nach Novelty-Staerke.
     */
    fun detectOnsets(
        energyWindows: List<Double>,
        windowDurationMs: Long,
        thresholdWindow: Int = DEFAULT_THRESHOLD_WINDOW,
        k: Double = DEFAULT_K,
        minSpacingMs: Long = DEFAULT_MIN_SPACING_MS,
        maxCandidates: Int = DEFAULT_MAX_CANDIDATES,
        minNovelty: Double = DEFAULT_MIN_NOVELTY,
    ): List<Long> {
        require(windowDurationMs > 0) { "windowDurationMs muss positiv sein" }
        if (energyWindows.size < 2 || maxCandidates <= 0) return emptyList()

        val novelty =
            DoubleArray(energyWindows.size) { index ->
                if (index == 0) {
                    0.0
                } else {
                    (energyWindows[index] - energyWindows[index - 1]).coerceAtLeast(0.0)
                }
            }

        // Kandidaten: Novelty ueber dem lokalen Schwellwert.
        val candidates = mutableListOf<Pair<Int, Double>>()
        for (index in 1 until novelty.size) {
            val from = (index - thresholdWindow).coerceAtLeast(0)
            val to = (index + thresholdWindow).coerceAtMost(novelty.size - 1)
            var sum = 0.0
            for (i in from..to) sum += novelty[i]
            val count = to - from + 1
            val mean = sum / count
            var varianceSum = 0.0
            for (i in from..to) {
                val d = novelty[i] - mean
                varianceSum += d * d
            }
            val stdDev = sqrt(varianceSum / count)
            val threshold = mean + k * stdDev
            if (novelty[index] > threshold && novelty[index] >= minNovelty) {
                candidates += index to novelty[index]
            }
        }

        // Staerkste zuerst, Mindestabstand einhalten, dann Top-N.
        val selected = mutableListOf<Pair<Int, Double>>()
        for (candidate in candidates.sortedByDescending { it.second }) {
            val positionMs = candidate.first * windowDurationMs
            val tooClose =
                selected.any { picked ->
                    val pickedMs = picked.first * windowDurationMs
                    kotlin.math.abs(pickedMs - positionMs) < minSpacingMs
                }
            if (!tooClose) {
                selected += candidate
                if (selected.size == maxCandidates) break
            }
        }

        return selected
            .map { it.first * windowDurationMs }
            .sorted()
    }
}
