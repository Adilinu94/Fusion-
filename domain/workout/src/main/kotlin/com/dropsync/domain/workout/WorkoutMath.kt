package com.dropsync.domain.workout

import com.training.core.TrainedSegment
import com.training.core.Units
import java.math.BigDecimal
import java.math.RoundingMode
import com.training.core.WorkoutMath as CoreWorkoutMath

/**
 * Trainingsmathematik wortgleich zu Bauplan 5.4:
 * `volumeMilliKg = externalLoadMilliKgPerImplement * loadMultiplier * reps`.
 *
 * Seit Schritt 5 der Flowtimer-Integration ist dies nur noch die
 * Millikilogramm-Fassade auf [CoreWorkoutMath] im gemeinsamen Kern
 * (CONTEXT E6: eine Wahrheit, im Kern). Fusions Invariante bleibt
 * unangetastet — nach aussen bleibt alles ganze Millikilogramm (Long),
 * Double erscheint nur auf der Kern-Grenze und wird dort auf ganze Gramm
 * gerundet, also verlustfrei (E1).
 */
object WorkoutMath {
    /** Erlaubte Multiplikatoren (5.4): 1 oder 2, nie berechnet. */
    fun isValidLoadMultiplier(value: Int): Boolean = CoreWorkoutMath.isValidLoadMultiplier(value)

    fun effectiveLoadMilliKg(
        loadMilliKg: Long,
        loadMultiplier: Int,
    ): Long =
        Units.kgToMilli(
            CoreWorkoutMath.effectiveLoadKg(Units.milliToKg(loadMilliKg), loadMultiplier),
        )

    fun segmentVolumeMilliKg(
        loadMilliKg: Long,
        loadMultiplier: Int,
        reps: Int,
    ): Long =
        Units.kgToMilli(
            CoreWorkoutMath.segmentVolumeKg(Units.milliToKg(loadMilliKg), loadMultiplier, reps),
        )

    /** Clustervolumen = Summe der Segmentvolumina (5.4). */
    fun clusterVolumeMilliKg(segments: List<SegmentInput>): Long =
        Units.kgToMilli(
            CoreWorkoutMath.clusterVolumeKg(
                segments
                    .filter(Qualification::segmentQualifies)
                    .map { it.toTrainedSegment() },
            ),
        )

    /**
     * UI-Eingabe in kg -> ganze Millikilogramm mit HALF_UP
     * (Schritt 10.2). Eingabe kommt als String aus dem Textfeld.
     *
     * Bleibt in Fusion: Der Kern nimmt Zahlen, nicht Textfeldinhalte —
     * Parsen und Komma-Behandlung sind Sache der eingebenden App.
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
    const val ONE_RM_FORMULA_VERSION = CoreWorkoutMath.ONE_RM_FORMULA_VERSION

    fun estimatedOneRmMilliKg(
        effectiveLoadMilliKg: Long,
        reps: Int,
    ): Long? =
        CoreWorkoutMath
            .estimatedOneRmKg(Units.milliToKg(effectiveLoadMilliKg), reps)
            ?.let(Units::kgToMilli)

    /**
     * Qualifiziertes [SegmentInput] -> Kern-Satz. Nur fuer die
     * Volumensumme; Zeit- und Session-Felder spielen dort keine Rolle und
     * bleiben auf ihren Defaults. Aufruf nur nach
     * [Qualification.segmentQualifies], deshalb sind Last und Reps hier
     * nicht mehr null.
     */
    private fun SegmentInput.toTrainedSegment(): TrainedSegment =
        TrainedSegment(
            loadKg = Units.milliToKg(externalLoadMilliKgPerImplement ?: 0L),
            reps = reps ?: 0,
            completedAtEpochMs = 0L,
            loadMultiplier = loadMultiplier,
        )
}
