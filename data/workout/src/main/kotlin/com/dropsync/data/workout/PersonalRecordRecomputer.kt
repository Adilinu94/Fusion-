package com.dropsync.data.workout

import com.dropsync.core.database.dao.FlatSetDao
import com.dropsync.core.database.dao.WorkoutDao
import com.dropsync.core.database.entity.PersonalRecordEntity
import com.dropsync.domain.workout.PrCalculator
import com.dropsync.domain.workout.QualifiedSegment

/**
 * A1/5.7: EINE PR-Wahrheit fuer beide Datenmodelle. Die Neuberechnung liest
 * die qualifizierte Cluster-Historie UND das flache Satz-Log derselben
 * Uebung und schreibt daraus die vollstaendige PR-Liste neu.
 *
 * Flache Saetze haben keine Session. Damit das Session-Volumen nicht mit
 * der Gesamthistorie waechst, zaehlt jeder flache Satz als eigene
 * Mini-Session: die Pseudo-ID ist die negierte Satz-ID ([QualifiedSegment]-
 * Konvention; [PrCalculator] macht daraus nach aussen null). Cluster- und
 * flache Historie teilen sich dieselbe Rechnung, deshalb koennen sie sich
 * nicht gegenseitig ueberschreiben.
 *
 * Muss innerhalb einer laufenden Transaktion aufgerufen werden; der
 * Aufrufer stellt das ueber `TransactionRunner` sicher.
 */
class PersonalRecordRecomputer(
    private val workoutDao: WorkoutDao,
    private val flatSetDao: FlatSetDao,
) {
    suspend fun recompute(exerciseId: Long) {
        val history =
            workoutDao.getQualifiedSegments(exerciseId).map {
                QualifiedSegment(
                    sessionId = it.sessionId,
                    sessionStartedAtEpochMs = it.sessionStartedAtEpochMs,
                    clusterId = it.clusterId,
                    completedAtEpochMs = it.completedAtEpochMs,
                    loadMilliKg = it.loadMilliKg,
                    loadMultiplier = it.loadMultiplier,
                    reps = it.reps,
                )
            } +
                flatSetDao.getForExercise(exerciseId).map {
                    QualifiedSegment(
                        sessionId = -it.id,
                        sessionStartedAtEpochMs = it.loggedAtEpochMs,
                        clusterId = it.id,
                        completedAtEpochMs = it.loggedAtEpochMs,
                        loadMilliKg = it.weightMilliKg,
                        loadMultiplier = 1,
                        reps = it.reps,
                    )
                }
        val records = PrCalculator.computeAll(history)
        workoutDao.deletePersonalRecordsForExercise(exerciseId)
        if (records.isNotEmpty()) {
            workoutDao.insertPersonalRecords(
                records.map {
                    PersonalRecordEntity(
                        exerciseId = exerciseId,
                        type = it.type.name,
                        achievedSessionId = it.achievedSessionId,
                        achievedClusterId = it.achievedClusterId,
                        valueLong = it.valueLong,
                        valueUnit = it.valueUnit.name,
                        comparableLoadMilliKg = it.comparableLoadMilliKg,
                        achievedAtEpochMs = it.achievedAtEpochMs,
                    )
                },
            )
        }
    }
}
