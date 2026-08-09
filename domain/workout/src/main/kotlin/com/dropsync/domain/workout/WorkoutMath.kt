package com.dropsync.domain.workout

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Trainingsmathematik wortgleich zu Bauplan 5.4:
 * `volumeMilliKg = externalLoadMilliKgPerImplement * loadMultiplier * reps`.
 * Alles in ganzen Millikilogramm; keine Gleitkommarechnung im Modell.
 */
object WorkoutMath {
    /** Erlaubte Multiplikatoren (5.4): 1 oder 2, nie berechnet. */
    fun isValidLoadMultiplier(value: Int): Boolean = value == 1 || value == 2

    fun effectiveLoadMilliKg(
        loadMilliKg: Long,
        loadMultiplier: Int,
    ): Long {
        require(isValidLoadMultiplier(loadMultiplier)) {
            "loadMultiplier muss 1 oder 2 sein: $loadMultiplier"
        }
        return loadMilliKg * loadMultiplier
    }

    fun segmentVolumeMilliKg(
        loadMilliKg: Long,
        loadMultiplier: Int,
        reps: Int,
    ): Long {
        require(reps > 0) { "reps muss positiv sein: $reps" }
        return effectiveLoadMilliKg(loadMilliKg, loadMultiplier) * reps
    }

    /** Clustervolumen = Summe der Segmentvolumina (5.4). */
    fun clusterVolumeMilliKg(segments: List<SegmentInput>): Long =
        segments
            .filter(Qualification::segmentQualifies)
            .sumOf {
                segmentVolumeMilliKg(
                    it.externalLoadMilliKgPerImplement ?: 0,
                    it.loadMultiplier,
                    it.reps ?: 0,
                )
            }

    /**
     * UI-Eingabe in kg -> ganze Millikilogramm mit HALF_UP
     * (Schritt 10.2). Eingabe kommt als String aus dem Textfeld.
     */
    fun roundKgInputToMilliKg(kgInput: String): Long =
        BigDecimal(kgInput.trim().replace(',', '.'))
            .multiply(BigDecimal(1000))
            .setScale(0, RoundingMode.HALF_UP)
            .longValueExact()

    /**
     * Geschaetztes 1RM (5.4): nur Trendwert, nie "PR". Formel
     * `loadKg * (1 + reps / 30)` fuer 1..10 Wiederholungen und positive
     * Last; Ergebnis in Millikilogramm mit HALF_UP.
     */
    const val ONE_RM_FORMULA_VERSION = 1

    fun estimatedOneRmMilliKg(
        effectiveLoadMilliKg: Long,
        reps: Int,
    ): Long? {
        if (reps !in 1..10 || effectiveLoadMilliKg <= 0) return null
        // (load * (30 + reps)) / 30, kaufmaennisch gerundet.
        val numerator = effectiveLoadMilliKg * (30 + reps)
        return (numerator + 15) / 30
    }
}
