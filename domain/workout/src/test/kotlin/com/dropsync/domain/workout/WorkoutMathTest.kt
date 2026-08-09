package com.dropsync.domain.workout

import com.dropsync.core.model.PrType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkoutMathTest {
    @Test
    fun `abnahme 30 kg pro hand mal 10 mal 2 ergibt 600 kg volumen`() {
        // Abnahmekriterium Schritt 10.
        val volume =
            WorkoutMath.segmentVolumeMilliKg(
                loadMilliKg = 30_000,
                loadMultiplier = 2,
                reps = 10,
            )
        assertEquals(600_000, volume) // 600 kg in Millikilogramm.
    }

    @Test
    fun `dropset volumen summiert alle segmente`() {
        // Abnahme: dreiteiliges Drop-Set zaehlt als ein Arbeitsset,
        // das Volumen wird aber komplett summiert.
        val segments =
            listOf(
                SegmentInput(80_000, 1, 8),
                SegmentInput(60_000, 1, 6),
                SegmentInput(40_000, 1, 10),
            )
        val expected = 80_000L * 8 + 60_000L * 6 + 40_000L * 10
        assertEquals(expected, WorkoutMath.clusterVolumeMilliKg(segments))
    }

    @Test
    fun `nur multiplikator 1 und 2 sind erlaubt`() {
        assertTrue(WorkoutMath.isValidLoadMultiplier(1))
        assertTrue(WorkoutMath.isValidLoadMultiplier(2))
        assertTrue(!WorkoutMath.isValidLoadMultiplier(0))
        assertTrue(!WorkoutMath.isValidLoadMultiplier(3))
    }

    @Test
    fun `kg eingabe rundet HALF_UP auf millikilogramm`() {
        assertEquals(22_500, WorkoutMath.roundKgInputToMilliKg("22.5"))
        assertEquals(22_500, WorkoutMath.roundKgInputToMilliKg("22,5"))
        // 0.0005 kg = 0.5 mKg -> HALF_UP auf 1 mKg.
        assertEquals(1, WorkoutMath.roundKgInputToMilliKg("0.0005"))
        assertEquals(100_000, WorkoutMath.roundKgInputToMilliKg("100"))
    }

    @Test
    fun `1rm nur fuer 1 bis 10 wiederholungen und positive last`() {
        // 100 kg x 10: 100 * (1 + 10/30) = 133.3333 kg -> 133333 mKg (HALF_UP).
        assertEquals(133_333L, WorkoutMath.estimatedOneRmMilliKg(100_000, 10)!!)
        // 90 kg x 5: 90 * (35/30) = 105 kg exakt.
        assertEquals(105_000L, WorkoutMath.estimatedOneRmMilliKg(90_000, 5)!!)
        assertNull(WorkoutMath.estimatedOneRmMilliKg(100_000, 11))
        assertNull(WorkoutMath.estimatedOneRmMilliKg(100_000, 0))
        assertNull(WorkoutMath.estimatedOneRmMilliKg(0, 5))
    }
}

class PrCalculatorTest {
    private fun segment(
        sessionId: Long,
        clusterId: Long,
        load: Long,
        reps: Int,
        multiplier: Int = 1,
        completedAt: Long = clusterId * 1000,
        sessionStart: Long = sessionId * 100_000,
    ) = QualifiedSegment(
        sessionId = sessionId,
        sessionStartedAtEpochMs = sessionStart,
        clusterId = clusterId,
        completedAtEpochMs = completedAt,
        loadMilliKg = load,
        loadMultiplier = multiplier,
        reps = reps,
    )

    @Test
    fun `gleichstand erzeugt keine neue pr`() {
        // Abnahme Schritt 10: Gleichstand -> Rekord bleibt beim
        // fruehesten Segment (Cluster 1, Session 1).
        val records =
            PrCalculator.computeAll(
                listOf(
                    segment(sessionId = 1, clusterId = 1, load = 100_000, reps = 5),
                    segment(sessionId = 2, clusterId = 9, load = 100_000, reps = 5),
                ),
            )
        val highestLoad = records.single { it.type == PrType.HIGHEST_LOAD }
        assertEquals(1, highestLoad.achievedSessionId)
        assertEquals(1L, highestLoad.achievedClusterId)
    }

    @Test
    fun `hoechste last nutzt die effektive last mit multiplikator`() {
        val records =
            PrCalculator.computeAll(
                listOf(
                    segment(sessionId = 1, clusterId = 1, load = 80_000, reps = 5),
                    // 2 x 45 kg Kurzhanteln = 90 kg effektiv.
                    segment(sessionId = 1, clusterId = 2, load = 45_000, reps = 5, multiplier = 2),
                ),
            )
        val highestLoad = records.single { it.type == PrType.HIGHEST_LOAD }
        assertEquals(90_000, highestLoad.valueLong)
        assertEquals(2L, highestLoad.achievedClusterId)
    }

    @Test
    fun `session volumen pr vergleicht sessionsummen`() {
        val records =
            PrCalculator.computeAll(
                listOf(
                    segment(sessionId = 1, clusterId = 1, load = 100_000, reps = 10), // 1000 kg
                    segment(sessionId = 2, clusterId = 5, load = 100_000, reps = 8), // 800 kg
                ),
            )
        val volumePr = records.single { it.type == PrType.HIGHEST_SESSION_VOLUME }
        assertEquals(1, volumePr.achievedSessionId)
        assertEquals(1_000_000, volumePr.valueLong)
    }

    @Test
    fun `reps pr gilt je identischer effektiver last`() {
        val records =
            PrCalculator.computeAll(
                listOf(
                    segment(sessionId = 1, clusterId = 1, load = 60_000, reps = 8),
                    segment(sessionId = 2, clusterId = 5, load = 60_000, reps = 10),
                    segment(sessionId = 2, clusterId = 6, load = 80_000, reps = 5),
                ),
            )
        val repsAt60 =
            records.single {
                it.type == PrType.MOST_REPS_AT_LOAD && it.comparableLoadMilliKg == 60_000L
            }
        assertEquals(10, repsAt60.valueLong)
        assertEquals(5L, repsAt60.achievedClusterId)

        val repsAt80 =
            records.single {
                it.type == PrType.MOST_REPS_AT_LOAD && it.comparableLoadMilliKg == 80_000L
            }
        assertEquals(5, repsAt80.valueLong)
    }

    @Test
    fun `leere historie ergibt keine records`() {
        assertTrue(PrCalculator.computeAll(emptyList()).isEmpty())
    }
}

class RoutineExpanderTest {
    private fun entry(
        exerciseId: Long,
        order: Int,
        group: Long? = null,
        sets: Int? = null,
    ) = RoutineEntry(exerciseId, order, group, sets, null, null, null)

    @Test
    fun `expansion behaelt reihenfolge und supersetgruppen`() {
        // Abnahme Schritt 9: Triset in reproduzierbarer Reihenfolge.
        val planned =
            RoutineExpander.expand(
                listOf(
                    entry(exerciseId = 30, order = 2, group = 7),
                    entry(exerciseId = 10, order = 0, group = 7),
                    entry(exerciseId = 20, order = 1, group = 7),
                ),
            )
        assertEquals(listOf(10L, 20L, 30L), planned.map { it.exerciseId })
        assertTrue(planned.all { it.supersetGroupId == 7L })
        assertEquals(RoutineExpander.DEFAULT_SETS, planned.first().plannedSets)
    }

    @Test
    fun `gruppe mit nur einer uebung ist ungueltig`() {
        var failed = false
        try {
            RoutineExpander.expand(listOf(entry(exerciseId = 1, order = 0, group = 5)))
        } catch (e: IllegalArgumentException) {
            failed = true
        }
        assertTrue(failed)
    }

    @Test
    fun `gruppe mit doppelter uebung ist ungueltig`() {
        // 9.6: exakt 2-3 UNTERSCHIEDLICHE Sessionuebungen.
        assertTrue(!SupersetRules.validateGroups(listOf(1L to 5L, 1L to 5L)))
        assertTrue(SupersetRules.validateGroups(listOf(1L to 5L, 2L to 5L, 3L to 5L)))
        assertTrue(!SupersetRules.validateGroups(listOf(1L to 5L, 2L to 5L, 3L to 5L, 4L to 5L)))
    }
}
