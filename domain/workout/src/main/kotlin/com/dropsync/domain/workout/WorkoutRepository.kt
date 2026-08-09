package com.dropsync.domain.workout

import com.dropsync.core.common.AppResult
import com.dropsync.core.model.RestMode
import com.dropsync.core.model.SessionStatus
import com.dropsync.core.model.SetRole
import kotlinx.coroutines.flow.Flow

/** Sichtbarer Sessionzustand fuer Features. */
data class WorkoutSessionInfo(
    val id: Long,
    val startedAtEpochMs: Long,
    val status: SessionStatus,
    val title: String?,
)

/**
 * Uebung fuer Auswahl-Listen: sprachneutraler Slug plus lokalisierter
 * Anzeigename (Abschnitt 2/Schritt 9.1); Fallback ist der Slug.
 */
data class ExerciseInfo(
    val id: Long,
    val slug: String,
    val displayName: String,
)

/** Uebung innerhalb der aktiven Session. */
data class SessionExerciseInfo(
    val id: Long,
    val exerciseId: Long,
    val orderIndex: Int,
    val displayName: String,
)

/**
 * Vertrag des Trainingslogs (Bauplan Schritt 9/10).
 * Implementierung in :data:workout; Satzabschluss und PR-Bestimmung
 * laufen in EINER Transaktion (10.3).
 */
interface WorkoutRepository {
    /** Es gibt hoechstens eine aktive Session (9.8). */
    val activeSession: Flow<WorkoutSessionInfo?>

    /** Aktive Uebungen mit lokalisiertem Namen fuer [locale] (z. B. "de"). */
    fun observeExercises(locale: String): Flow<List<ExerciseInfo>>

    /** Uebungen der Session in stabiler Reihenfolge (9.3). */
    fun observeSessionExercises(
        sessionId: Long,
        locale: String,
    ): Flow<List<SessionExerciseInfo>>

    suspend fun startSession(
        title: String?,
        fromRoutineId: Long?,
    ): AppResult<Long>

    suspend fun completeSession(sessionId: Long): AppResult<Unit>

    /** Setzt nur status = DISCARDED; loescht keine Daten (9.8). */
    suspend fun discardSession(sessionId: Long): AppResult<Unit>

    suspend fun addExercise(
        sessionId: Long,
        exerciseId: Long,
        supersetGroupId: Long?,
    ): AppResult<Long>

    /**
     * Schliesst einen Satzcluster ab: Segmente, Clusterstatus und die
     * vollstaendig neu berechneten PRs der Uebung in einer Transaktion.
     */
    suspend fun completeCluster(
        sessionExerciseId: Long,
        setRole: SetRole,
        segments: List<SegmentInput>,
        note: String?,
    ): AppResult<Long>

    /**
     * Macht einen Satzabschluss rueckgaengig (12.5, 10-Sekunden-Fenster
     * der UI): loescht den Cluster samt Segmenten und berechnet die PRs
     * der Uebung in derselben Transaktion vollstaendig neu (10.4).
     */
    suspend fun undoCompleteCluster(clusterId: Long): AppResult<Unit>

    /**
     * Vollstaendige Neuberechnung nach Korrektur/Loeschung (10.4);
     * entfernt falsche alte PRs.
     */
    suspend fun recomputeRecords(exerciseId: Long): AppResult<Unit>

    /** Werte des letzten abgeschlossenen Clusters derselben Uebung (9.4). */
    suspend fun lastCompletedClusterPrefill(exerciseId: Long): AppResult<List<SegmentInput>>

    /**
     * Speichert ein optionales PlaybackSnapshot-Ereignis (Schritt 11.1):
     * nur Song-ID, Marker-ID nullable, Playerposition und Zeitstempel;
     * niemals eine dauerhafte Queuekopie.
     */
    suspend fun recordPlaybackSnapshot(
        sessionId: Long,
        songId: Long,
        markerId: Long?,
        positionMs: Long,
    ): AppResult<Long>

    /** Snapshots einer Session, aufsteigend nach Aufnahmezeit. */
    suspend fun getPlaybackSnapshots(sessionId: Long): AppResult<List<PlaybackSnapshotInfo>>

    // --- Uebungsbibliothek (Schritt 9.1/9.2) ---

    /** Alle aktiven Uebungen mit Equipment fuer die Bibliotheksliste. */
    fun observeExerciseLibrary(locale: String): Flow<List<ExerciseLibraryItem>>

    /** Legt eine eigene Uebung mit Muskel-Mapping an (eindeutiger Slug). */
    suspend fun createCustomExercise(input: CustomExerciseInput): AppResult<Long>

    /** Vollstaendige Details inkl. Muskelbeteiligung fuer die Detailansicht. */
    suspend fun getExerciseDetail(
        exerciseId: Long,
        locale: String,
    ): AppResult<ExerciseDetail>

    /** Archiviert eine Uebung (verschwindet aus Auswahl; Historie bleibt). */
    suspend fun archiveExercise(exerciseId: Long): AppResult<Unit>

    // --- Resttimer pro Uebung (Abschnitt 8) ---

    /** Gemerkte Rest-Praeferenz der Uebung; null, wenn noch keine gesetzt. */
    suspend fun getRestPref(exerciseId: Long): AppResult<RestPref?>

    suspend fun setRestPref(
        exerciseId: Long,
        restSeconds: Int,
        restMode: RestMode,
    ): AppResult<Unit>

    // --- Uebungstausch und Wiederholen (Schritt 9) ---

    /**
     * Tauscht eine Sessionuebung: KEEP behaelt die geloggten Saetze an der
     * alten Uebung und haengt die neue an; MOVE ordnet die Saetze der neuen
     * Uebung zu; DISCARD verwirft die Saetze dieser Session. Die PRs der
     * betroffenen Uebungen werden in derselben Transaktion neu berechnet.
     */
    suspend fun swapSessionExercise(
        sessionExerciseId: Long,
        newExerciseId: Long,
        strategy: SwapStrategy,
    ): AppResult<Unit>

    /** Startet eine neue Session aus der zuletzt abgeschlossenen Session. */
    suspend fun repeatLastSession(): AppResult<Long>

    // --- Routinen / Templates (Schritt 9.7) ---

    fun observeRoutines(): Flow<List<RoutineInfo>>

    suspend fun getRoutineDetail(
        routineId: Long,
        locale: String,
    ): AppResult<RoutineDetail>

    suspend fun createRoutine(
        name: String,
        entries: List<RoutineEntry>,
    ): AppResult<Long>

    /** Speichert die gegebene Session als wiederverwendbare Routine. */
    suspend fun createRoutineFromSession(
        sessionId: Long,
        name: String,
    ): AppResult<Long>

    // --- Fortschritt & Analyse (Abschnitt 3) ---

    fun observePersonalRecords(exerciseId: Long): Flow<List<PrRecord>>

    suspend fun getExerciseProgress(exerciseId: Long): AppResult<List<ExerciseProgressPoint>>

    /** Waehrend der Session gespielte Tracks (Auswertung, Schritt 11.1). */
    suspend fun getSessionMusic(sessionId: Long): AppResult<List<PlayedTrackInfo>>
}
