package com.dropsync.feature.progress

import com.dropsync.domain.workout.ExerciseTarget
import com.dropsync.domain.workout.FlatSet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/**
 * Ziele-Tile (UI-Vertrag R5, Entscheidung 14). Geprueft wird die
 * Reihenfolge (Goal-Gradient), die Distanz-Werte fuer die R2-Sprache und
 * die Zehnerteilung der Punkte.
 *
 * Die Zeit wird als [Calendar] injiziert, damit „laenger nicht trainiert"
 * deterministisch pruefbar ist.
 */
class ProgressGoalsUiStateTest {
    private fun now(): Calendar =
        Calendar.getInstance(TimeZone.getTimeZone("Europe/Berlin")).apply {
            set(2026, Calendar.AUGUST, 25, 12, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }

    private fun target(
        exerciseId: Long,
        weightMilliKg: Long = 100_000L,
        reps: Int = 5,
    ) = ExerciseTarget(
        exerciseId = exerciseId,
        targetWeightMilliKg = weightMilliKg,
        targetReps = reps,
        updatedAtEpochMs = 0L,
    )

    private fun set(
        exerciseId: Long,
        weightMilliKg: Long,
        reps: Int,
        daysAgo: Int = 1,
        id: Long = 1L,
    ) = FlatSet(
        id = id,
        exerciseId = exerciseId,
        weightMilliKg = weightMilliKg,
        reps = reps,
        loggedAtEpochMs =
            (now().clone() as Calendar)
                .apply { add(Calendar.DAY_OF_YEAR, -daysAgo) }
                .timeInMillis,
    )

    private val names = mapOf(1L to "Bankdruecken", 2L to "Kniebeuge", 3L to "Kreuzheben")

    private fun state(
        targets: List<ExerciseTarget>,
        sets: List<FlatSet>,
    ) = ProgressGoalsUiState.from(
        targets = targets,
        sets = sets,
        exerciseNames = names,
        now = now(),
        fallbackExerciseName = "Uebung",
    )

    @Test
    fun `ohne ziele ist das tile nicht sichtbar`() {
        val result = state(emptyList(), listOf(set(1L, 100_000L, 5)))
        assertFalse(result.hasAnyGoal)
        assertEquals(0, result.totalCount)
    }

    /**
     * Der Kern von R5: Uebungen ohne Ziel erscheinen nicht — sonst wird die
     * Liste mit jeder neuen Uebung laenger und sagt weniger.
     */
    @Test
    fun `uebungen ohne ziel erscheinen nicht`() {
        val result =
            state(
                targets = listOf(target(1L)),
                sets = listOf(set(1L, 90_000L, 5), set(2L, 80_000L, 8, id = 2)),
            )
        assertEquals(1, result.rows.size)
        assertEquals(1L, result.rows.first().exerciseId)
    }

    @Test
    fun `distanz nennt fehlendes gewicht`() {
        val row = state(listOf(target(1L)), listOf(set(1L, 90_000L, 5))).rows.first()
        assertFalse(row.reached)
        assertEquals(10_000L, row.missingWeightMilliKg)
        assertEquals(0, row.missingReps)
    }

    @Test
    fun `distanz nennt fehlende reps`() {
        val row = state(listOf(target(1L)), listOf(set(1L, 100_000L, 3))).rows.first()
        assertEquals(0L, row.missingWeightMilliKg)
        assertEquals(2, row.missingReps)
    }

    @Test
    fun `distanz nennt beide dimensionen`() {
        val row = state(listOf(target(1L)), listOf(set(1L, 90_000L, 3))).rows.first()
        assertEquals(10_000L, row.missingWeightMilliKg)
        assertEquals(2, row.missingReps)
    }

    @Test
    fun `erreichtes ziel hat keine distanz`() {
        val row = state(listOf(target(1L)), listOf(set(1L, 100_000L, 5))).rows.first()
        assertTrue(row.reached)
        assertEquals(0L, row.missingWeightMilliKg)
        assertEquals(0, row.missingReps)
        assertEquals(10, row.filledDots)
    }

    @Test
    fun `ohne satz steht noch kein satz`() {
        val row = state(listOf(target(1L)), emptyList()).rows.first()
        assertFalse(row.hasAnySet)
        assertFalse(row.reached)
        // Volle Restdistanz, also kein gefuellter Punkt.
        assertEquals(0, row.filledDots)
    }

    /**
     * Goal-Gradient (R5): Das Fast-Geschaffte zuerst, Erreichtes ans Ende.
     */
    @Test
    fun `reihenfolge zeigt fast geschafftes zuerst`() {
        val result =
            state(
                targets = listOf(target(1L), target(2L), target(3L)),
                // 1: 50 % offen, 2: erreicht, 3: 10 % offen
                sets =
                    listOf(
                        set(1L, 50_000L, 5, id = 1),
                        set(2L, 100_000L, 5, id = 2),
                        set(3L, 90_000L, 5, id = 3),
                    ),
            )
        assertEquals(listOf(3L, 1L, 2L), result.rows.map { it.exerciseId })
        assertEquals(1, result.reachedCount)
        assertEquals(3, result.totalCount)
    }

    @Test
    fun `groessere offene dimension entscheidet die sortierung`() {
        // Uebung 1: Gewicht 10 % offen, Reps 0 % -> 10 %
        // Uebung 2: Gewicht 0 %, Reps 40 % offen (3 von 5) -> 40 %
        val result =
            state(
                targets = listOf(target(1L), target(2L)),
                sets = listOf(set(1L, 90_000L, 5, id = 1), set(2L, 100_000L, 3, id = 2)),
            )
        assertEquals(listOf(1L, 2L), result.rows.map { it.exerciseId })
        assertEquals(0.1f, result.rows.first().remainingFraction, 0.001f)
        assertEquals(0.4f, result.rows.last().remainingFraction, 0.001f)
    }

    @Test
    fun `punkte folgen der zehnerteilung`() {
        // 90 von 100 kg -> 10 % offen -> 9 Punkte
        assertEquals(9, state(listOf(target(1L)), listOf(set(1L, 90_000L, 5))).rows.first().filledDots)
        // 50 von 100 kg -> 50 % offen -> 5 Punkte
        assertEquals(5, state(listOf(target(1L)), listOf(set(1L, 50_000L, 5))).rows.first().filledDots)
    }

    /**
     * Nicht erreicht darf nie zehn Punkte zeigen — sonst sieht die Zeile
     * erfuellt aus, obwohl sie es nicht ist.
     */
    @Test
    fun `knapp verfehlt zeigt nie zehn punkte`() {
        // 99,999 von 100 kg: rechnerisch 100 %, aber nicht erreicht.
        val row = state(listOf(target(1L)), listOf(set(1L, 99_999L, 5))).rows.first()
        assertFalse(row.reached)
        assertEquals(9, row.filledDots)
    }

    @Test
    fun `laenger nicht trainiert wird eigene gruppe`() {
        val result =
            state(
                targets = listOf(target(1L), target(2L)),
                sets = listOf(set(1L, 90_000L, 5, daysAgo = 1, id = 1), set(2L, 90_000L, 5, daysAgo = 70, id = 2)),
            )
        assertEquals(listOf(1L), result.rows.map { it.exerciseId })
        assertEquals(listOf(2L), result.staleRows.map { it.exerciseId })
        // Der Zaehler umfasst beide Gruppen.
        assertEquals(2, result.totalCount)
    }

    @Test
    fun `ziel ohne satz gilt nicht als laenger nicht trainiert`() {
        val result = state(listOf(target(1L)), emptyList())
        assertEquals(1, result.rows.size)
        assertTrue(result.staleRows.isEmpty())
    }

    /** Bester Satz zaehlt, nicht der letzte (E4). */
    @Test
    fun `leichter abschlusssatz nimmt das ziel nicht zurueck`() {
        val row =
            state(
                listOf(target(1L)),
                listOf(
                    set(1L, 100_000L, 5, daysAgo = 2, id = 1),
                    set(1L, 60_000L, 12, daysAgo = 1, id = 2),
                ),
            ).rows.first()
        assertTrue(row.reached)
    }

    @Test
    fun `unbekannte uebung nutzt den fallbacknamen`() {
        val row = state(listOf(target(99L)), emptyList()).rows.first()
        assertEquals("Uebung", row.exerciseName)
    }
}
