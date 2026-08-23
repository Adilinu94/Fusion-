package com.dropsync.domain.workout

import kotlinx.coroutines.flow.Flow

/**
 * Benutzerziel „Trainings pro Woche" (Flowtimer-Integration Schritt 7):
 * Der Wert traegt den Wochenring des Progress-Dashboards (UI-Vertrag R2).
 * Persistiert per DataStore (Muster RestTimerPreferencesRepository), nicht in
 * der Room-DB — er ist Geraet-einstellung, kein Trainingsdatum.
 */
interface WorkoutGoalRepository {
    /** Wochenziel in Trainingstagen; bis zur ersten Aenderung [DEFAULT_WEEKLY_GOAL]. */
    val weeklyTrainingGoal: Flow<Int>

    /** Setzt das Wochenziel, begrenzt auf [MIN_WEEKLY_GOAL]..[MAX_WEEKLY_GOAL]. */
    suspend fun setWeeklyTrainingGoal(days: Int)

    companion object {
        const val DEFAULT_WEEKLY_GOAL = 3
        const val MIN_WEEKLY_GOAL = 1
        const val MAX_WEEKLY_GOAL = 7
    }
}
