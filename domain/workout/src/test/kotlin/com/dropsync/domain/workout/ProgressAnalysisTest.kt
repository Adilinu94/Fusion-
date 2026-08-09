package com.dropsync.domain.workout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fortschrittsserie und Klassifizierung (Abschnitt 3): je Session ein
 * Punkt, Vergleich der juengsten Sessions mit dem Vorfenster (+/- 1.5 %),
 * Plateau ab drei Sessions ohne neue Bestleistung.
 */
class ProgressAnalysisTest {
    private fun segment(
        sessionId: Long,
        startedAt: Long,
        loadMilliKg: Long,
        reps: Int,
        multiplier: Int = 1,
    ) = QualifiedSegment(
        sessionId = sessionId,
        sessionStartedAtEpochMs = startedAt,
        clusterId = sessionId * 10,
        completedAtEpochMs = startedAt + 1,
        loadMilliKg = loadMilliKg,
        loadMultiplier = multiplier,
        reps = reps,
    )

    private fun point(oneRmMilliKg: Long) =
        ExerciseProgressPoint(
            sessionId = oneRmMilliKg,
            sessionStartedAtEpochMs = oneRmMilliKg,
            maxEffectiveLoadMilliKg = oneRmMilliKg,
            totalVolumeMilliKg = 0,
            bestEstimatedOneRmMilliKg = oneRmMilliKg,
        )

    @Test
    fun `builder gruppiert je session und sortiert nach startzeit`() {
        // Session 2 zuerst geliefert; Ausgabe muss chronologisch sein.
        val points =
            ProgressSeriesBuilder.build(
                listOf(
                    segment(sessionId = 2, startedAt = 2_000, loadMilliKg = 105_000, reps = 5),
                    segment(sessionId = 1, startedAt = 1_000, loadMilliKg = 100_000, reps = 5),
                    segment(sessionId = 1, startedAt = 1_000, loadMilliKg = 80_000, reps = 10),
                ),
            )

        assertEquals(listOf(1L, 2L), points.map { it.sessionId })
        val first = points[0]
        assertEquals(100_000L, first.maxEffectiveLoadMilliKg)
        // Volumen = 100 kg x 5 + 80 kg x 10 = 1300 kg.
        assertEquals(1_300_000L, first.totalVolumeMilliKg)
        // Bestes 1RM: 100_000 * 35 / 30, HALF_UP (WorkoutMath, Formel v1).
        assertEquals(116_667L, first.bestEstimatedOneRmMilliKg)
    }

    @Test
    fun `builder beruecksichtigt loadmultiplier fuer effektive last`() {
        val points =
            ProgressSeriesBuilder.build(
                listOf(segment(sessionId = 1, startedAt = 1_000, loadMilliKg = 30_000, reps = 10, multiplier = 2)),
            )
        assertEquals(60_000L, points.single().maxEffectiveLoadMilliKg)
        assertEquals(600_000L, points.single().totalVolumeMilliKg)
    }

    @Test
    fun `weniger als zwei sessions gelten als stagnierend ohne plateau`() {
        val result = ProgressionClassifier.classify(listOf(point(100_000)))
        assertEquals(ProgressStatus.STAGNATING, result.status)
        assertFalse(result.plateau)
        assertEquals(ProgressSuggestion.KEEP_GOING, result.suggestion)
    }

    @Test
    fun `steigende serie ist progressiv`() {
        val result =
            ProgressionClassifier.classify(
                listOf(100_000L, 100_000L, 100_000L, 110_000L, 120_000L, 130_000L).map(::point),
            )
        assertEquals(ProgressStatus.PROGRESSING, result.status)
        assertFalse(result.plateau)
        assertEquals(ProgressSuggestion.KEEP_GOING, result.suggestion)
    }

    @Test
    fun `fallende serie ist ruecklaeufig mit deload vorschlag`() {
        val result =
            ProgressionClassifier.classify(
                listOf(130_000L, 120_000L, 110_000L, 100_000L, 90_000L, 80_000L).map(::point),
            )
        assertEquals(ProgressStatus.DECLINING, result.status)
        assertEquals(ProgressSuggestion.DELOAD, result.suggestion)
    }

    @Test
    fun `plateau nach drei sessions ohne bestleistung`() {
        // Bestleistung in Session 1, danach vier flache Sessions.
        val result =
            ProgressionClassifier.classify(
                listOf(110_000L, 100_000L, 100_000L, 100_000L, 100_000L).map(::point),
            )
        assertEquals(ProgressStatus.STAGNATING, result.status)
        assertTrue(result.plateau)
        assertEquals(ProgressSuggestion.CHANGE_VARIATION, result.suggestion)
    }

    @Test
    fun `stagnation ohne plateau schlaegt mehr volumen vor`() {
        val result =
            ProgressionClassifier.classify(
                listOf(100_000L, 101_000L, 100_000L, 101_000L).map(::point),
            )
        assertEquals(ProgressStatus.STAGNATING, result.status)
        assertFalse(result.plateau)
        assertEquals(ProgressSuggestion.INCREASE_VOLUME, result.suggestion)
    }

    @Test
    fun `fallback auf maximale last wenn kein 1rm vorliegt`() {
        // reps > 10 liefert kein 1RM; Klassifizierung nutzt maxLoad.
        val noOneRm =
            listOf(100_000L, 100_000L, 120_000L, 140_000L).map {
                ExerciseProgressPoint(
                    sessionId = it,
                    sessionStartedAtEpochMs = it,
                    maxEffectiveLoadMilliKg = it,
                    totalVolumeMilliKg = 0,
                    bestEstimatedOneRmMilliKg = null,
                )
            }
        assertEquals(ProgressStatus.PROGRESSING, ProgressionClassifier.classify(noOneRm).status)
    }
}
