package com.dropsync.feature.progress

import com.dropsync.domain.workout.FlatSet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/**
 * Dashboard-State nach UI-Vertrag: Tages-Streak mit zwei freien Ruhetagen
 * (E4b), Workout = Kalendertag mit Satz (E2), Woche ab Montag
 * (Entscheidung 15). Alle Daten relativ zum Montag der aktuellen Woche,
 * damit die Tests unabhaengig vom echten Wochentag sind.
 */
class ProgressUiStateTest {
    private val monday =
        Calendar.getInstance().apply {
            set(2026, Calendar.AUGUST, 17, 0, 0, 0)
            set(Calendar.MILLISECOND, 0)
            firstDayOfWeek = Calendar.MONDAY
            set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
            zeroTime()
        }

    /** Freitag 14:30 der Bezugswoche. */
    private val now = dayAt(4, 14, 30)

    private fun dayAt(
        offsetDays: Int,
        hour: Int = 10,
        minute: Int = 0,
    ): Calendar =
        (monday.clone() as Calendar).apply {
            add(Calendar.DAY_OF_YEAR, offsetDays)
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

    private fun set(
        id: Long,
        at: Calendar,
        weightMilliKg: Long = 80_000_000,
        reps: Int = 5,
    ) = FlatSet(
        id = id,
        exerciseId = 1,
        weightMilliKg = weightMilliKg,
        reps = reps,
        loggedAtEpochMs = at.timeInMillis,
    )

    private fun Calendar.zeroTime(): Calendar =
        apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

    @Test
    fun `Wochenring zaehlt Tage, nicht Sessions`() {
        val state =
            ProgressUiState.from(
                sets =
                    listOf(
                        set(1, dayAt(4, 9)),
                        set(2, dayAt(4, 18)), // gleicher Tag, zweites Training
                        set(3, dayAt(2)),
                    ),
                now = now,
            )

        assertEquals(2, state.trainingDaysThisWeek)
        assertEquals(0.6667f, state.ringProgress, 0.001f)
    }

    @Test
    fun `Ring bleibt bei hundert Prozent und meldet Ueberschreitung`() {
        val state =
            ProgressUiState.from(
                sets = listOf(set(1, dayAt(0)), set(2, dayAt(1)), set(3, dayAt(2)), set(4, dayAt(3))),
                now = now,
            )

        assertEquals(4, state.trainingDaysThisWeek)
        assertEquals(1f, state.ringProgress, 0f)
        assertTrue(state.weeklyGoalExceeded)
    }

    @Test
    fun `Streak laeuft mit einem Ruhetag weiter`() {
        val state =
            ProgressUiState.from(
                sets = listOf(set(1, dayAt(4)), set(2, dayAt(2))),
                now = now,
            )

        assertEquals(2, state.streakDays)
    }

    @Test
    fun `Streak laeuft mit zwei Ruhetagen weiter`() {
        val state =
            ProgressUiState.from(
                sets = listOf(set(1, dayAt(4)), set(2, dayAt(1))),
                now = now,
            )

        assertEquals(2, state.streakDays)
    }

    @Test
    fun `Streak bricht beim dritten Ruhetag`() {
        val state =
            ProgressUiState.from(
                sets = listOf(set(1, dayAt(4)), set(2, dayAt(0))),
                now = now,
            )

        assertEquals(1, state.streakDays)
        assertFalse(state.streakVisible)
    }

    @Test
    fun `Ein heute fehlender Satz verbraucht nur einen Kulanztag`() {
        // Heute (Freitag) noch nichts geloggt, gestern trainiert.
        val state =
            ProgressUiState.from(
                sets = listOf(set(1, dayAt(3)), set(2, dayAt(1))),
                now = now,
            )

        assertEquals(2, state.streakDays)
        assertFalse(state.streakAtRisk)
    }

    @Test
    fun `Streak-Gefahr wenn heute und gestern nichts geloggt ist`() {
        val state =
            ProgressUiState.from(
                sets = listOf(set(1, dayAt(2)), set(2, dayAt(1))),
                now = now,
            )

        assertEquals(2, state.streakDays)
        assertTrue(state.streakAtRisk)
    }

    @Test
    fun `Montag ohne Saetze dieser Woche ist der Neue-Woche-Zustand`() {
        val state =
            ProgressUiState.from(
                sets = listOf(set(1, dayAt(-3))), // Freitag der Vorwoche
                now = dayAt(0, 8),
            )

        assertTrue(state.hasAnySets)
        assertTrue(state.firstWeekDayWithoutSets)
        assertEquals(0, state.trainingDaysThisWeek)
    }

    @Test
    fun `Saetze um lokale Mitternacht zaehlen in verschiedene Tage`() {
        val state =
            ProgressUiState.from(
                sets = listOf(set(1, dayAt(2, 23)), set(2, dayAt(3, 0))),
                now = now,
            )

        assertEquals(2, state.trainingDaysThisWeek)
    }

    @Test
    fun `Chart gruppiert nach Montag-Wochen`() {
        val state =
            ProgressUiState.from(
                sets =
                    listOf(
                        set(1, dayAt(0, 20)), // Montagabend
                        set(2, dayAt(6, 8)), // Sonntagmorgen — dieselbe Woche
                        set(3, dayAt(-7)), // Montag der Vorwoche
                    ),
                now = now,
            )

        assertEquals(8, state.weekBars.size)
        val thisWeek = state.weekBars.last()
        val lastWeek = state.weekBars[state.weekBars.size - 2]
        assertEquals(800.0, thisWeek.volumeKg, 0.001) // 2 x (80 kg x 5)
        assertEquals(400.0, lastWeek.volumeKg, 0.001)
        assertTrue(state.chartVisible)
    }

    @Test
    fun `Chart zaehlt Trainingstage pro Woche und markiert nur die aktuelle`() {
        val state =
            ProgressUiState.from(
                sets =
                    listOf(
                        set(1, dayAt(0, 20)),
                        set(2, dayAt(6, 8)), // zwei verschiedene Tage diese Woche
                        set(3, dayAt(-7)),
                    ),
                now = now,
            )

        val thisWeek = state.weekBars.last()
        val lastWeek = state.weekBars[state.weekBars.size - 2]
        assertEquals(2, thisWeek.trainingDays)
        assertEquals(1, lastWeek.trainingDays)
        assertTrue(thisWeek.isCurrentWeek)
        assertFalse(lastWeek.isCurrentWeek)
        assertEquals(1, state.weekBars.count { it.isCurrentWeek })
    }

    @Test
    fun `Chart bleibt verborgen mit nur einer Woche mit Saetzen`() {
        val state =
            ProgressUiState.from(
                sets = listOf(set(1, dayAt(4)), set(2, dayAt(2))),
                now = now,
            )

        assertFalse(state.chartVisible)
    }

    @Test
    fun `Ohne Saetze bleibt der Zustand leer`() {
        val state = ProgressUiState.from(sets = emptyList(), now = now)

        assertFalse(state.hasAnySets)
        assertEquals(0, state.streakDays)
        assertFalse(state.chartVisible)
        assertFalse(state.weeklyGoalReached)
    }
}
