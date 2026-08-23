package com.dropsync.domain.sensor

import com.dropsync.core.common.AppResult

/**
 * Persistence contract for per-exercise+device calibration profiles
 * (design doc Phase 4 step 3: "persist pro Übung+Gerät").
 *
 * Implemented in :data:sensor (DataStore-backed). Pure domain port: no
 * Android types, returns [AppResult] like every repository in the app.
 *
 * Umbauplan Phase 7.4: profiles are revisioned. [load] returns the ACTIVE
 * revision; the learn loop stores CANDIDATE profiles via [save] and promotes
 * them with [noteValidatedSet]; [rollback] reactivates the parent revision.
 */
interface CalibrationProfileRepository {
    /** Laedt die aktive Profilrevision; null wenn keine existiert. */
    suspend fun load(
        exerciseId: Long,
        deviceId: String,
    ): AppResult<CalibrationProfile?>

    /**
     * Alle Revisionen der Kombination, neueste zuerst. Fuer Diagnose und
     * spaetere manuelle Rollback-UI.
     */
    suspend fun loadHistory(
        exerciseId: Long,
        deviceId: String,
    ): AppResult<List<CalibrationProfile>>

    /**
     * Speichert [profile] entsprechend seinem [ProfileStatus]:
     * - ACTIVE: ersetzt die aktive Revision (bisherige ACTIVE wird RETIRED,
     *   die parentRevision-Kette bleibt erhalten).
     * - CANDIDATE: landet neben der aktiven Revision, die unangetastet bleibt.
     * - RETIRED: wird nur als Revisionsstand abgelegt (kein Statuswechsel
     *   anderer Revisionen).
     */
    suspend fun save(profile: CalibrationProfile): AppResult<Unit>

    /**
     * Registriert ein validiertes Set fuer die Kandidaten-Revision. Bei
     * [ProfileLearningPolicy.PROMOTION_SETS] validierten Sets wird der
     * Kandidat aktiv (bisherige ACTIVE wird RETIRED).
     */
    suspend fun noteValidatedSet(
        exerciseId: Long,
        deviceId: String,
    ): AppResult<PromotionResult>

    /**
     * Rollt zur parentRevision der aktiven Revision zurueck. Liefert false,
     * wenn keine parentRevision existiert (Erst-Kalibrierung).
     */
    suspend fun rollback(
        exerciseId: Long,
        deviceId: String,
    ): AppResult<Boolean>

    /** Loescht alle Revisionen der Kombination. */
    suspend fun delete(
        exerciseId: Long,
        deviceId: String,
    ): AppResult<Unit>
}

/** Ergebnis von [CalibrationProfileRepository.noteValidatedSet]. */
enum class PromotionResult {
    /** Kandidat hat genug validierte Sets und ist jetzt aktiv. */
    PROMOTED,

    /** Kandidat wurde hochgezaehlt, braucht aber noch mehr Beweise. */
    PENDING,

    /** Kein Kandidat vorhanden. */
    NO_CANDIDATE,
}
