package com.dropsync.domain.workout

import com.training.core.TargetGoal
import com.training.core.TrainedSegment
import com.training.core.Units
import com.training.core.targetPct
import com.training.core.targetReached

/**
 * Zielauswertung an der Kern-Grenze (Entscheidung 14, E4). Die Regeln
 * selbst stehen im gemeinsamen Kern (`com.training.core`); hier wird
 * Fusions Millikilogramm-Modell darauf uebersetzt.
 *
 * Warum der beste Satz und nicht der letzte: Nach einem leichteren
 * Abschlusssatz darf der Status nicht grundlos zurueckfallen (E4).
 */
object TargetEvaluator {
    /**
     * Zielstatus aus den Saetzen einer Uebung. [sets] darf leer sein —
     * dann ist das Ziel nicht erreicht und der Fortschritt 0.
     *
     * Fortschritt bewusst nur ueber das Gewicht: Zwei Dimensionen in einen
     * Balken zu mischen ergibt eine Zahl, die nichts aussagt. Ob das Ziel
     * erfuellt ist, entscheidet weiterhin [targetReached] mit beiden
     * Bedingungen.
     */
    fun evaluate(
        target: ExerciseTarget,
        sets: List<FlatSet>,
    ): TargetStatus {
        val goal =
            TargetGoal(
                weightKg = Units.milliToKg(target.targetWeightMilliKg),
                reps = target.targetReps,
            )
        val segments = sets.map { it.toTrainedSegment() }
        // Bester Satz: hoechstes Gewicht, bei Gleichstand mehr Reps (E4).
        val best = sets.maxWithOrNull(compareBy({ it.weightMilliKg }, { it.reps }))

        return TargetStatus(
            target = target,
            reached = targetReached(segments, goal),
            progress =
                targetPct(
                    cur = Units.milliToKg(best?.weightMilliKg ?: 0L),
                    start = 0.0,
                    target = goal.weightKg,
                ).toFloat(),
            bestWeightMilliKg = best?.weightMilliKg,
            bestReps = best?.reps,
        )
    }

    /**
     * Flacher Satz -> Kern-Satz. `flat_sets` kennt keinen
     * Hantel-Multiplikator; das eingegebene Gewicht ist bereits die
     * effektive Last, deshalb bleibt der Multiplikator auf 1.
     */
    private fun FlatSet.toTrainedSegment(): TrainedSegment =
        TrainedSegment(
            loadKg = Units.milliToKg(weightMilliKg),
            reps = reps,
            completedAtEpochMs = loggedAtEpochMs,
        )
}
