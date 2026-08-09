package com.dropsync.domain.workout

// Fortschritts- und Plateauanalyse je Uebung (Abschnitt 3). Rein
// funktional und ohne Android-Abhaengigkeit, daher deterministisch
// testbar. Grundlage sind die qualifizierten Segmente einer Uebung
// (dieselbe Quelle wie die PR-Berechnung).

/** Ein Datenpunkt je Session fuer Verlaufsdiagramme (Schritt 10). */
data class ExerciseProgressPoint(
    val sessionId: Long,
    val sessionStartedAtEpochMs: Long,
    val maxEffectiveLoadMilliKg: Long,
    val totalVolumeMilliKg: Long,
    /** Geschaetztes 1RM (Trendwert, nie PR); null wenn nicht berechenbar. */
    val bestEstimatedOneRmMilliKg: Long?,
)

/** Klassifizierung der Entwicklung einer Uebung. */
enum class ProgressStatus { PROGRESSING, STAGNATING, DECLINING }

/** Konkreter Vorschlag bei Stagnation/Plateau (Abschnitt 3). */
enum class ProgressSuggestion { KEEP_GOING, DELOAD, INCREASE_VOLUME, CHANGE_VARIATION }

/** Ergebnis der Klassifizierung inklusive Plateau-Alarm. */
data class ProgressClassification(
    val status: ProgressStatus,
    val plateau: Boolean,
    val suggestion: ProgressSuggestion,
)

/**
 * Baut je Session einen [ExerciseProgressPoint]: maximale effektive Last,
 * Session-Volumen und bestes geschaetztes 1RM. Aufsteigend nach Startzeit.
 */
object ProgressSeriesBuilder {
    fun build(segments: List<QualifiedSegment>): List<ExerciseProgressPoint> {
        if (segments.isEmpty()) return emptyList()
        return segments
            .groupBy { it.sessionId }
            .map { (sessionId, group) ->
                val maxLoad =
                    group.maxOf {
                        WorkoutMath.effectiveLoadMilliKg(it.loadMilliKg, it.loadMultiplier)
                    }
                val volume =
                    group.sumOf {
                        WorkoutMath.segmentVolumeMilliKg(it.loadMilliKg, it.loadMultiplier, it.reps)
                    }
                val bestOneRm =
                    group
                        .mapNotNull {
                            WorkoutMath.estimatedOneRmMilliKg(
                                WorkoutMath.effectiveLoadMilliKg(it.loadMilliKg, it.loadMultiplier),
                                it.reps,
                            )
                        }.maxOrNull()
                ExerciseProgressPoint(
                    sessionId = sessionId,
                    sessionStartedAtEpochMs = group.first().sessionStartedAtEpochMs,
                    maxEffectiveLoadMilliKg = maxLoad,
                    totalVolumeMilliKg = volume,
                    bestEstimatedOneRmMilliKg = bestOneRm,
                )
            }.sortedBy { it.sessionStartedAtEpochMs }
    }
}

/**
 * Klassifiziert die Entwicklung anhand des besten geschaetzten 1RM je
 * Session (Fallback: maximale effektive Last). Der Durchschnitt der
 * juengsten Sessions wird mit dem davorliegenden Fenster verglichen:
 * mehr als +1.5 % ist progressiv, weniger als -1.5 % ruecklaeufig, sonst
 * stagnierend. Plateau, wenn seit mindestens drei Sessions keine neue
 * Bestleistung erreicht wurde und der Status nicht progressiv ist.
 */
object ProgressionClassifier {
    private const val PROGRESS_THRESHOLD = 0.015
    private const val PLATEAU_MIN_SESSIONS = 3

    fun classify(points: List<ExerciseProgressPoint>): ProgressClassification {
        val series =
            points.map { it.bestEstimatedOneRmMilliKg ?: it.maxEffectiveLoadMilliKg }
        if (series.size < 2) {
            return ProgressClassification(
                status = ProgressStatus.STAGNATING,
                plateau = false,
                suggestion = ProgressSuggestion.KEEP_GOING,
            )
        }
        val window = (series.size / 2).coerceIn(1, 3)
        val recent = series.takeLast(window)
        val previous = series.dropLast(window).takeLast(window)
        val recentAvg = recent.average()
        val previousAvg = if (previous.isEmpty()) recentAvg else previous.average()
        val change = if (previousAvg == 0.0) 0.0 else (recentAvg - previousAvg) / previousAvg

        val status =
            when {
                change > PROGRESS_THRESHOLD -> ProgressStatus.PROGRESSING
                change < -PROGRESS_THRESHOLD -> ProgressStatus.DECLINING
                else -> ProgressStatus.STAGNATING
            }

        val best = series.max()
        val sessionsSinceBest = series.size - 1 - series.lastIndexOf(best)
        val plateau =
            status != ProgressStatus.PROGRESSING && sessionsSinceBest >= PLATEAU_MIN_SESSIONS

        val suggestion =
            when {
                status == ProgressStatus.PROGRESSING -> ProgressSuggestion.KEEP_GOING
                status == ProgressStatus.DECLINING -> ProgressSuggestion.DELOAD
                plateau -> ProgressSuggestion.CHANGE_VARIATION
                else -> ProgressSuggestion.INCREASE_VOLUME
            }
        return ProgressClassification(status, plateau, suggestion)
    }
}
