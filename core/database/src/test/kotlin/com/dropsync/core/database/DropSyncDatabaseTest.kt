package com.dropsync.core.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.dropsync.core.database.entity.PersonalRecordEntity
import com.dropsync.core.database.entity.SessionExerciseEntity
import com.dropsync.core.database.entity.SetClusterEntity
import com.dropsync.core.database.entity.SetSegmentEntity
import com.dropsync.core.database.entity.WorkoutSessionEntity
import com.dropsync.core.database.seed.ExerciseSeeder
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Schritt-3-Abnahmen auf der JVM (Robolectric):
 * - Drop-Set = 1 Cluster + 3 Segmente, nicht 3 Arbeitssets.
 * - Abgebrochener Schreibvorgang hinterlaesst weder halbe Segmente noch
 *   eine halbe PR (Transaktionsrollback).
 * - Seed ist idempotent und ueberschreibt nie Benutzerdaten.
 */
@RunWith(RobolectricTestRunner::class)
class DropSyncDatabaseTest {
    private lateinit var db: DropSyncDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db =
            Room
                .inMemoryDatabaseBuilder(context, DropSyncDatabase::class.java)
                .allowMainThreadQueries()
                .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun seededExerciseId(): Long {
        val seedJson =
            ApplicationProvider
                .getApplicationContext<Context>()
                .assets
                .open(ExerciseSeeder.ASSET_PATH)
                .bufferedReader()
                .readText()
        ExerciseSeeder(db).seed(seedJson)
        return requireNotNull(db.exerciseDao().getByCanonicalName("barbell_bench_press")).id
    }

    private suspend fun activeSessionExercise(exerciseId: Long): Pair<Long, Long> {
        val sessionId =
            db.workoutDao().insertSession(
                WorkoutSessionEntity(
                    startedAtEpochMs = 1_700_000_000_000L,
                    endedAtEpochMs = null,
                    zoneIdAtStart = "Europe/Berlin",
                    status = "ACTIVE",
                    title = null,
                    notes = null,
                ),
            )
        val sessionExerciseId =
            db.workoutDao().insertSessionExercise(
                SessionExerciseEntity(
                    sessionId = sessionId,
                    exerciseId = exerciseId,
                    orderIndex = 0,
                    supersetGroupId = null,
                ),
            )
        return sessionId to sessionExerciseId
    }

    @Test
    fun `drop set ist ein cluster mit drei segmenten und ein arbeitsset`() =
        runTest {
            val exerciseId = seededExerciseId()
            val (_, sessionExerciseId) = activeSessionExercise(exerciseId)

            val clusterId =
                db.workoutDao().insertCluster(
                    SetClusterEntity(
                        sessionExerciseId = sessionExerciseId,
                        orderIndex = 0,
                        setRole = "WORKING",
                        isCompleted = false,
                        note = null,
                        completedAtEpochMs = null,
                    ),
                )
            db.workoutDao().completeClusterTransaction(
                clusterId = clusterId,
                completedAtEpochMs = 1_700_000_100_000L,
                segments =
                    listOf(
                        SetSegmentEntity(
                            clusterId = clusterId,
                            segmentIndex = 0,
                            externalLoadMilliKgPerImplement = 80_000,
                            loadMultiplier = 1,
                            reps = 8,
                            durationMs = null,
                            distanceM = null,
                        ),
                        SetSegmentEntity(
                            clusterId = clusterId,
                            segmentIndex = 1,
                            externalLoadMilliKgPerImplement = 60_000,
                            loadMultiplier = 1,
                            reps = 6,
                            durationMs = null,
                            distanceM = null,
                        ),
                        SetSegmentEntity(
                            clusterId = clusterId,
                            segmentIndex = 2,
                            externalLoadMilliKgPerImplement = 40_000,
                            loadMultiplier = 1,
                            reps = 5,
                            durationMs = null,
                            distanceM = null,
                        ),
                    ),
                newRecords = emptyList(),
            )

            val clusters = db.workoutDao().getClustersForSessionExercise(sessionExerciseId)
            assertEquals("Ein Drop-Set ist genau ein Cluster", 1, clusters.size)
            assertTrue(clusters.single().isCompleted)
            assertEquals(3, db.workoutDao().countSegments(clusterId))
        }

    @Test
    fun `abgebrochene transaktion hinterlaesst keine teilzeilen`() =
        runTest {
            val exerciseId = seededExerciseId()
            val (sessionId, sessionExerciseId) = activeSessionExercise(exerciseId)

            val clusterId =
                db.workoutDao().insertCluster(
                    SetClusterEntity(
                        sessionExerciseId = sessionExerciseId,
                        orderIndex = 0,
                        setRole = "WORKING",
                        isCompleted = false,
                        note = null,
                        completedAtEpochMs = null,
                    ),
                )

            // Die PR verletzt absichtlich den Foreign Key (unbekannte Session),
            // nachdem Segmente und Clusterstatus bereits geschrieben wurden.
            val invalidRecord =
                PersonalRecordEntity(
                    exerciseId = exerciseId,
                    type = "HIGHEST_LOAD",
                    achievedSessionId = sessionId + 999,
                    achievedClusterId = clusterId,
                    valueLong = 80_000,
                    valueUnit = "MILLI_KG",
                    comparableLoadMilliKg = 80_000,
                    achievedAtEpochMs = 1_700_000_100_000L,
                )

            var failed = false
            try {
                db.workoutDao().completeClusterTransaction(
                    clusterId = clusterId,
                    completedAtEpochMs = 1_700_000_100_000L,
                    segments =
                        listOf(
                            SetSegmentEntity(
                                clusterId = clusterId,
                                segmentIndex = 0,
                                externalLoadMilliKgPerImplement = 80_000,
                                loadMultiplier = 1,
                                reps = 8,
                                durationMs = null,
                                distanceM = null,
                            ),
                        ),
                    newRecords = listOf(invalidRecord),
                )
            } catch (expected: Exception) {
                failed = true
            }

            assertTrue("Transaktion muss fehlschlagen", failed)
            assertEquals("Keine halben Segmente", 0, db.workoutDao().countSegments(clusterId))
            val cluster = db.workoutDao().getClustersForSessionExercise(sessionExerciseId).single()
            assertFalse("Clusterstatus wurde zurueckgerollt", cluster.isCompleted)
            assertTrue(
                "Keine halbe PR",
                db.workoutDao().getPersonalRecordsForExercise(exerciseId).isEmpty(),
            )
        }

    @Test
    fun `seed ist idempotent und ueberschreibt keine daten`() =
        runTest {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val seedJson =
                context.assets
                    .open(ExerciseSeeder.ASSET_PATH)
                    .bufferedReader()
                    .readText()

            ExerciseSeeder(db).seed(seedJson)
            val countAfterFirst = db.exerciseDao().countStandardExercises()
            assertTrue(countAfterFirst > 0)

            // Zweiter Lauf: keine Duplikate, keine Fehler.
            ExerciseSeeder(db).seed(seedJson)
            assertEquals(countAfterFirst, db.exerciseDao().countStandardExercises())

            val bench = requireNotNull(db.exerciseDao().getByCanonicalName("barbell_bench_press"))
            assertEquals("Bankdruecken (Langhantel)", db.exerciseDao().getDisplayName(bench.id, "de"))
            assertEquals("Barbell Bench Press", db.exerciseDao().getDisplayName(bench.id, "en"))
        }

    @Test
    fun `marker ohne link gelten als nicht zugeordnet`() =
        runTest {
            val markerId =
                db.markerDao().insert(
                    com.dropsync.core.database.entity.SongMarkerEntity(
                        sourceFingerprint = "fp-1",
                        label = "Drop 1",
                        positionMs = 134_500,
                        source = "IMPORT",
                        isEnabled = true,
                        createdAtEpochMs = 1_700_000_000_000L,
                    ),
                )
            assertNotNull(db.markerDao().getById(markerId))
            assertEquals(null, db.markerDao().getLinkForMarker(markerId))
        }
}
