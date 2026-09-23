package com.dropsync.feature.progress

import com.dropsync.core.model.PrType
import com.dropsync.core.model.PrValueUnit
import com.dropsync.domain.workout.FlatSet
import com.dropsync.domain.workout.PrRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/** Zeilen-Feed nach UI-Vertrag: letzte zehn Saetze, Bestwerte der letzten 7 Tage (R6). */
class ProgressFeedUiStateTest {
    private val now =
        Calendar.getInstance().apply {
            set(2026, Calendar.AUGUST, 21, 12, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }

    private fun daysAgo(
        id: Long,
        days: Int,
        exerciseId: Long = 1,
        weightMilliKg: Long = 80_000_000,
        reps: Int = 5,
    ) = FlatSet(
        id = id,
        exerciseId = exerciseId,
        weightMilliKg = weightMilliKg,
        reps = reps,
        loggedAtEpochMs =
            (now.clone() as Calendar)
                .apply { add(Calendar.DAY_OF_YEAR, -days) }
                .timeInMillis,
    )

    @Test
    fun `Rekordsatz aelter als sieben Tage ist kein neuer Bestwert`() {
        val state =
            ProgressFeedUiState.from(
                sets = listOf(daysAgo(1, days = 14, weightMilliKg = 90_000_000)),
                exerciseNames = mapOf(1L to "Bankdrücken"),
                now = now,
                fallbackExerciseName = "Übung",
            )

        assertEquals(emptyList<ProgressSetRow>(), state.freshRecords)
    }

    @Test
    fun `Frischer Rekordsatz zaehlt als neuer Bestwert`() {
        val state =
            ProgressFeedUiState.from(
                sets =
                    listOf(
                        daysAgo(1, days = 2, weightMilliKg = 90_000_000),
                        daysAgo(2, days = 3, weightMilliKg = 70_000_000),
                    ),
                exerciseNames = mapOf(1L to "Bankdrücken"),
                now = now,
                fallbackExerciseName = "Übung",
            )

        assertEquals(listOf(1L), state.freshRecords.map { it.set.id })
        assertEquals(listOf("Bankdrücken"), state.freshRecords.map { it.exerciseName })
    }

    @Test
    fun `Frischer aber schwacher Satz ist kein neuer Bestwert`() {
        val state =
            ProgressFeedUiState.from(
                sets =
                    listOf(
                        daysAgo(1, days = 10, weightMilliKg = 100_000_000),
                        daysAgo(2, days = 1, weightMilliKg = 50_000_000),
                    ),
                exerciseNames = mapOf(1L to "Bankdrücken"),
                now = now,
                fallbackExerciseName = "Übung",
            )

        assertEquals(emptyList<ProgressSetRow>(), state.freshRecords)
    }

    @Test
    fun `Letzte Saetze sind auf zehn begrenzt und unbekannte Uebungen fallen zurueck`() {
        val sets = (1L..12L).map { id -> daysAgo(id, days = id.toInt()) }
        val state =
            ProgressFeedUiState.from(
                sets = sets,
                exerciseNames = emptyMap(),
                now = now,
                fallbackExerciseName = "Übung",
            )

        assertEquals((1L..10L).toList(), state.recentSets.map { it.set.id })
        assertEquals(List(10) { "Übung" }, state.recentSets.map { it.exerciseName })
    }

    /** Echter PR (Befund 3.14/153) zum Zeitpunkt [days] vor [now]. */
    private fun pr(
        exerciseId: Long = 1,
        days: Int = 2,
        type: PrType = PrType.HIGHEST_LOAD,
        valueLong: Long = 100_000_000,
        valueUnit: PrValueUnit = PrValueUnit.MILLI_KG,
    ) = PrRecord(
        exerciseId = exerciseId,
        type = type,
        achievedSessionId = 1L,
        achievedClusterId = null,
        valueLong = valueLong,
        valueUnit = valueUnit,
        comparableLoadMilliKg = null,
        achievedAtEpochMs =
            (now.clone() as Calendar)
                .apply { add(Calendar.DAY_OF_YEAR, -days) }
                .timeInMillis,
    )

    @Test
    fun `echte PRs juenger als sieben Tage erscheinen mit Uebungsname`() {
        val state =
            ProgressFeedUiState.from(
                sets = emptyList(),
                exerciseNames = emptyMap(),
                now = now,
                fallbackExerciseName = "Übung",
                personalRecords = listOf(pr(exerciseId = 7)),
                prExerciseNames = mapOf(7L to "Kreuzheben"),
            )

        assertEquals(1, state.newPrRecords.size)
        assertEquals("Kreuzheben", state.newPrRecords.first().exerciseName)
        assertEquals(
            PrType.HIGHEST_LOAD,
            state.newPrRecords
                .first()
                .record.type,
        )
    }

    @Test
    fun `alte echte PRs werden nicht gezeigt und Sortierung ist absteigend`() {
        val state =
            ProgressFeedUiState.from(
                sets = emptyList(),
                exerciseNames = emptyMap(),
                now = now,
                fallbackExerciseName = "Übung",
                personalRecords =
                    listOf(
                        pr(exerciseId = 1, days = 14),
                        pr(exerciseId = 2, days = 5),
                        pr(exerciseId = 3, days = 1),
                    ),
            )

        assertEquals(listOf(3L, 2L), state.newPrRecords.map { it.record.exerciseId })
        assertTrue(state.newPrRecords.all { it.exerciseName == "Übung" })
    }

    @Test
    fun `ohne Saetze aber mit echten PRs ist der Feed nicht leer`() {
        val state =
            ProgressFeedUiState.from(
                sets = emptyList(),
                exerciseNames = emptyMap(),
                now = now,
                fallbackExerciseName = "Übung",
                personalRecords = listOf(pr()),
            )

        assertTrue(state.newPrRecords.isNotEmpty())
        assertEquals(emptyList<ProgressSetRow>(), state.recentSets)
    }
}
