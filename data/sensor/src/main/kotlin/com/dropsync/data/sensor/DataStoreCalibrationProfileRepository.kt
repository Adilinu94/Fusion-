package com.dropsync.data.sensor

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.dropsync.core.common.AppError
import com.dropsync.core.common.AppResult
import com.dropsync.domain.sensor.CalibrationProfile
import com.dropsync.domain.sensor.CalibrationProfileRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

private val Context.calibrationProfileDataStore by preferencesDataStore(name = "sensor_calibration")

/**
 * DataStore-backed persistence of per-exercise+device calibration profiles
 * (Fusion Phase 4 step 3: "persist pro Übung+Gerät").
 *
 * Serialization is a compact semicolon-separated Double list — no JSON
 * library needed for the small, flat [CalibrationProfile] shape. Unknown or
 * corrupt entries are treated as "no profile" (load returns null) so a stale
 * blob never breaks the train flow.
 */
@Singleton
class DataStoreCalibrationProfileRepository
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) : CalibrationProfileRepository {
        override suspend fun load(
            exerciseId: Long,
            deviceId: String,
        ): AppResult<CalibrationProfile?> =
            try {
                val raw = context.calibrationProfileDataStore.data.first()[key(exerciseId, deviceId)]
                AppResult.success(raw?.let { decode(it, exerciseId, deviceId) })
            } catch (e: Exception) {
                AppResult.failure(AppError.Unknown("calibration load: ${e.message}"))
            }

        override suspend fun save(profile: CalibrationProfile): AppResult<Unit> =
            try {
                context.calibrationProfileDataStore.edit { prefs ->
                    prefs[key(profile.exerciseId, profile.deviceId)] = encode(profile)
                }
                AppResult.success(Unit)
            } catch (e: Exception) {
                AppResult.failure(AppError.Unknown("calibration save: ${e.message}"))
            }

        override suspend fun delete(
            exerciseId: Long,
            deviceId: String,
        ): AppResult<Unit> =
            try {
                context.calibrationProfileDataStore.edit { prefs ->
                    prefs.remove(key(exerciseId, deviceId))
                }
                AppResult.success(Unit)
            } catch (e: Exception) {
                AppResult.failure(AppError.Unknown("calibration delete: ${e.message}"))
            }

        private fun key(
            exerciseId: Long,
            deviceId: String,
        ) = stringPreferencesKey("cal_${exerciseId}_$deviceId")

        // Compact semicolon-separated Double lists (no JSON lib needed).
        // v1 had 5 parts (no axis/bias); v2 has 8 parts. A v1 blob lacks the
        // rotation axis, so it cannot drive the pipeline -> treated as absent.
        private fun encode(profile: CalibrationProfile): String =
            buildString {
                append(profile.rotationAxis.joinToString(","))
                append(';')
                append(profile.gyroBias.joinToString(","))
                append(';')
                append(profile.repTemplate.joinToString(","))
                append(';')
                append(profile.signalPeakLevel)
                append(';')
                append(profile.noisePeakLevel)
                append(';')
                append(profile.expectedProminence)
                append(';')
                append(profile.expectedDurationSamples)
                append(';')
                append(profile.qualityScore)
            }

        private fun decode(
            raw: String,
            exerciseId: Long,
            deviceId: String,
        ): CalibrationProfile? {
            val parts = raw.split(';')
            // v1 blobs (5 parts) have no rotation axis -> cannot calibrate.
            if (parts.size != 8) return null
            val axis = parts[0].split(',').mapNotNull { it.toDoubleOrNull() }
            val bias = parts[1].split(',').mapNotNull { it.toDoubleOrNull() }
            val template = parts[2].split(',').mapNotNull { it.toDoubleOrNull() }
            val signalPeak = parts[3].toDoubleOrNull() ?: return null
            val noisePeak = parts[4].toDoubleOrNull() ?: return null
            val prominence = parts[5].toDoubleOrNull() ?: return null
            val durationSamples = parts[6].toDoubleOrNull() ?: return null
            val quality = parts[7].toDoubleOrNull() ?: 1.0
            if (axis.size != 3 || bias.size != 3 || template.isEmpty()) return null
            return CalibrationProfile(
                exerciseId = exerciseId,
                deviceId = deviceId,
                rotationAxis = axis,
                gyroBias = bias,
                repTemplate = template,
                signalPeakLevel = signalPeak,
                noisePeakLevel = noisePeak,
                expectedProminence = prominence,
                expectedDurationSamples = durationSamples,
                qualityScore = quality,
            )
        }
    }
