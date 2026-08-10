package com.dropsync.domain.sensor

import com.dropsync.core.common.AppResult

/**
 * Persistence contract for per-exercise+device calibration profiles
 * (design doc Phase 4 step 3: "persist pro Übung+Gerät").
 *
 * Implemented in :data:sensor (DataStore-backed). Pure domain port: no
 * Android types, returns [AppResult] like every repository in the app.
 */
interface CalibrationProfileRepository {
    suspend fun load(
        exerciseId: Long,
        deviceId: String,
    ): AppResult<CalibrationProfile?>

    suspend fun save(profile: CalibrationProfile): AppResult<Unit>

    suspend fun delete(
        exerciseId: Long,
        deviceId: String,
    ): AppResult<Unit>
}
