package com.dropsync.feature.progress

import com.dropsync.domain.workout.FlatSet
import java.util.Calendar

/**
 * Eine Balken-Spalte des Wochen-Charts (aelteste Woche zuerst). Der Chart
 * traegt zwei Groessen (UI-Vertrag R4): Hoehe = Volumen, Grundlinie =
 * Wochenziel erfullet — dafuer braucht jeder Balken seine Trainingstage.
 */
data class ProgressWeekBar(
    val weekStartEpochMs: Long,
    val volumeKg: Double,
    val trainingDays: Int,
    val isCurrentWeek: Boolean,
)

/**
 * C6 (U-7): Lade-/Fehlerzustand des Dashboards. Vorher war "laedt noch" von
 * "keine Saetze" nicht unterscheidbar und ein Ladefehler blieb stumm; der
 * Fehlerfall bietet jetzt einen Retry an.
 */
sealed interface ProgressDashboardScreenState {
    data object Loading : ProgressDashboardScreenState

    data object Error : ProgressDashboardScreenState

    data class Ready(
        val dashboard: ProgressDashboardUiState,
    ) : ProgressDashboardScreenState
}

/**
 * Dashboard-State nach UI-Vertrag (2026-08-22-flowtimer-integration-UI.md).
 *
 * Alle Definitionen dort: Ein Workout ist ein Kalendertag mit mindestens einem
 * Satz (E2, Tagesgrenze lokale Mitternacht); die Woche beginnt Montag
 * (Entscheidung 15); der Streak zaehlt Trainingstage in Folge und bricht erst
 * nach drei zusammenhaengenden Ruhetagen (E4b — zwei sind frei, passend zum
 * 48-72-h-Fenster des Krafttrainings).
 *
 * Die Zeit wird als [Calendar] uebergeben, damit der Zustand deterministisch
 * testbar ist; das ViewModel reicht `Calendar.getInstance()` durch. Diese
 * Logik wandert spaeter in den gemeinsamen Kern (design.md Schritt 3);
 * Semantik und Tests bleiben dann unveraendert.
 */
data class ProgressUiState(
    val hasAnySets: Boolean,
    val trainingDaysThisWeek: Int,
    val weeklyGoal: Int,
    val streakDays: Int,
    val streakAtRisk: Boolean,
    val firstWeekDayWithoutSets: Boolean,
    val weekVolumeKg: Double,
    val weekBars: List<ProgressWeekBar>,
) {
    val ringProgress: Float
        get() = if (weeklyGoal <= 0) 0f else (trainingDaysThisWeek.toFloat() / weeklyGoal).coerceAtMost(1f)

    val weeklyGoalReached: Boolean
        get() = trainingDaysThisWeek >= weeklyGoal

    val weeklyGoalExceeded: Boolean
        get() = trainingDaysThisWeek > weeklyGoal

    /** Chart-Sichtbarkeit: ein einzelner Balken ist kein Trend (UI-Vertrag). */
    val chartVisible: Boolean
        get() = weekBars.count { it.volumeKg > 0.0 } >= 2

    /** Streak-Sichtbarkeit: „1 Tag in Folge" ist keine Serie (UI-Vertrag). */
    val streakVisible: Boolean
        get() = streakDays >= 2

    companion object {
        const val DEFAULT_WEEKLY_GOAL = 3
        private const val CHART_WEEKS = 8
        private const val MAX_REST_DAYS = 2

        val Empty =
            ProgressUiState(
                hasAnySets = false,
                trainingDaysThisWeek = 0,
                weeklyGoal = DEFAULT_WEEKLY_GOAL,
                streakDays = 0,
                streakAtRisk = false,
                firstWeekDayWithoutSets = false,
                weekVolumeKg = 0.0,
                weekBars = emptyList(),
            )

        /** Anteil des Ueberschusses ueber das Wochenziel, fuer den duennen Zweitbogen des Rings. */
        fun excessProgress(
            trainingDays: Int,
            weeklyGoal: Int,
        ): Float =
            if (weeklyGoal <= 0 ||
                trainingDays <= weeklyGoal
            ) {
                0f
            } else {
                ((trainingDays - weeklyGoal).toFloat() / weeklyGoal).coerceAtMost(1f)
            }

        fun from(
            sets: List<FlatSet>,
            now: Calendar,
            weeklyGoal: Int = DEFAULT_WEEKLY_GOAL,
        ): ProgressUiState {
            if (sets.isEmpty()) return Empty.copy(weeklyGoal = weeklyGoal)

            // Tagesbeginn-lokale Normalisierung: ein Trainingstag ist durch
            // lokale Mitternacht begrenzt (E2), nicht durch 24-h-Differenz —
            // DST-Umstellungen duerfen die Tageszuordnung nicht verschieben.
            val trainingDays = sets.map { dayStartOf(it.loggedAtEpochMs) }.toSet()
            val weekStart = weekStartOf(now)
            val trainingDaysThisWeek = trainingDays.count { it >= weekStart }
            val streak = countStreak(trainingDays, now)
            val today = dayStartOf(now.timeInMillis)
            val yesterday =
                Calendar
                    .getInstance()
                    .apply {
                        timeInMillis = today
                        add(Calendar.DAY_OF_YEAR, -1)
                    }.timeInMillis

            return ProgressUiState(
                hasAnySets = true,
                trainingDaysThisWeek = trainingDaysThisWeek,
                weeklyGoal = weeklyGoal,
                streakDays = streak,
                streakAtRisk = streak >= 2 && today !in trainingDays && yesterday !in trainingDays,
                firstWeekDayWithoutSets =
                    now.get(Calendar.DAY_OF_WEEK) == Calendar.MONDAY &&
                        trainingDaysThisWeek == 0,
                weekVolumeKg = sets.filter { it.loggedAtEpochMs >= weekStart }.sumOf { it.volumeKg },
                weekBars = weekBars(sets, weekStart),
            )
        }

        /**
         * Trainingstage in Folge mit zwei freien Ruhetagen (E4b): Der Zaehler
         * laeuft vom heutigen Tag rueckwaerts und bricht beim dritten
         * zusammenhaengenden Tag ohne Satz. Ein heute noch fehlender Satz
         * verbraucht deshalb nur einen der beiden Kulanz-Tage.
         */
        private fun countStreak(
            trainingDays: Set<Long>,
            now: Calendar,
        ): Int {
            var streak = 0
            var restDays = 0
            val cursor =
                Calendar.getInstance().apply {
                    timeInMillis = now.timeInMillis
                    zeroTime()
                }
            while (restDays <= MAX_REST_DAYS) {
                if (cursor.timeInMillis in trainingDays) {
                    streak++
                    restDays = 0
                } else {
                    restDays++
                }
                cursor.add(Calendar.DAY_OF_YEAR, -1)
            }
            return streak
        }

        private fun weekBars(
            sets: List<FlatSet>,
            currentWeekStartEpochMs: Long,
        ): List<ProgressWeekBar> {
            // Wochenstart-Grenzen kalendarisch (nicht +7*24h): Eine
            // Herbst-Umstellungswoche ist 169 Stunden lang, eine fixe
            // Millisekunden-Grenze wuerde Saetze am Sonntag falsch zuordnen.
            val starts = mutableListOf<Long>()
            val week =
                Calendar.getInstance().apply {
                    timeInMillis = currentWeekStartEpochMs
                }
            repeat(CHART_WEEKS) {
                starts += week.timeInMillis
                week.add(Calendar.WEEK_OF_YEAR, -1)
            }
            starts.reverse()
            return starts.mapIndexed { index, start ->
                val end = starts.getOrNull(index + 1) ?: Long.MAX_VALUE
                val weekSets = sets.filter { it.loggedAtEpochMs >= start && it.loggedAtEpochMs < end }
                ProgressWeekBar(
                    weekStartEpochMs = start,
                    volumeKg = weekSets.sumOf { it.volumeKg },
                    trainingDays = weekSets.map { dayStartOf(it.loggedAtEpochMs) }.toSet().size,
                    isCurrentWeek = start == currentWeekStartEpochMs,
                )
            }
        }

        private fun dayStartOf(epochMs: Long): Long =
            Calendar
                .getInstance()
                .apply {
                    timeInMillis = epochMs
                    zeroTime()
                }.timeInMillis

        private fun weekStartOf(now: Calendar): Long =
            (now.clone() as Calendar)
                .apply {
                    zeroTime()
                    firstDayOfWeek = Calendar.MONDAY
                    set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
                }.timeInMillis

        private fun Calendar.zeroTime(): Calendar =
            apply {
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
    }
}
