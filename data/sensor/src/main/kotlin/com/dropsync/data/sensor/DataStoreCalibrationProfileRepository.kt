package com.dropsync.data.sensor

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.dropsync.core.common.AppError
import com.dropsync.core.common.AppResult
import com.dropsync.domain.sensor.CalibrationProfile
import com.dropsync.domain.sensor.CalibrationProfileRepository
import com.dropsync.domain.sensor.ProfileStatus
import com.dropsync.domain.sensor.PromotionResult
import com.dropsync.domain.sensor.RepEngineVersion
import com.dropsync.domain.sensor.RepSignalKind
import com.dropsync.domain.sensor.calibration.ProfileLearningPolicy
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

// Internal statt private: der Modul-Test schreibt v3-Legacy-Bloe be fuer die
// Migration direkt in denselben DataStore.
internal val Context.calibrationProfileDataStore by preferencesDataStore(name = "sensor_calibration")

/**
 * DataStore-backed persistence of per-exercise+device calibration profiles
 * (Fusion Phase 4 step 3: "persist pro Übung+Gerät").
 *
 * Umbauplan Phase 0.1/1.4: profiles are schema- and engine-versioned and the
 * calibrated detection threshold is stored directly (no SPK/NPK roundtrip).
 * Legacy blobs (schema < PROFILE_SCHEMA_VERSION) are treated as "no profile"
 * so a stale blob never drives the pipeline with wrong parameters.
 *
 * Umbauplan Phase 7.4: profiles are revisioned. Key layout:
 * - `cal_<ex>_<dev>_r<rev>`  -> encoded profile blob
 * - `cal_<ex>_<dev>_active`  -> Int (active revision)
 * - `cal_<ex>_<dev>_cand`    -> Int (candidate revision, optional)
 * v3 blobs under the legacy key `cal_<ex>_<dev>` are migrated once to
 * revision 1 ACTIVE; users never have to re-calibrate.
 *
 * Serialization is a compact semicolon-separated list — no JSON library
 * needed for the small, flat [CalibrationProfile] shape. Unknown or corrupt
 * entries are treated as "no profile" (load returns null).
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
                val prefs = context.calibrationProfileDataStore.data.first()
                AppResult.success(
                    activeRevision(prefs, exerciseId, deviceId)?.let { rev ->
                        decode(prefs[revisionKey(exerciseId, deviceId, rev)].orEmpty(), exerciseId, deviceId)
                            ?: migrateLegacyIfNeeded(prefs, exerciseId, deviceId)
                    },
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppResult.failure(AppError.Unknown("calibration load: ${e.message}"))
            }

        override suspend fun loadHistory(
            exerciseId: Long,
            deviceId: String,
        ): AppResult<List<CalibrationProfile>> =
            try {
                val prefs = context.calibrationProfileDataStore.data.first()
                val prefix = "cal_${exerciseId}_${deviceId}_r"
                val profiles =
                    prefs
                        .asMap()
                        .entries
                        .filter { (key, _) -> key.name.startsWith(prefix) }
                        .mapNotNull { (_, value) -> decode(value as? String ?: "", exerciseId, deviceId) }
                        .sortedByDescending { it.revision }
                AppResult.success(profiles)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppResult.failure(AppError.Unknown("calibration history: ${e.message}"))
            }

        override suspend fun save(profile: CalibrationProfile): AppResult<Unit> =
            try {
                context.calibrationProfileDataStore.edit { prefs ->
                    when (profile.status) {
                        ProfileStatus.ACTIVE -> {
                            // Bisherige aktive Revision -> RETIRED (Kette bleibt).
                            activeRevision(prefs, profile.exerciseId, profile.deviceId)?.let { oldRev ->
                                val oldRaw = prefs[revisionKey(profile.exerciseId, profile.deviceId, oldRev)]
                                oldRaw?.let { raw ->
                                    decode(raw, profile.exerciseId, profile.deviceId)?.let { old ->
                                        prefs[revisionKey(profile.exerciseId, profile.deviceId, oldRev)] =
                                            encode(old.copy(status = ProfileStatus.RETIRED))
                                    }
                                }
                            }
                            prefs[revisionKey(profile.exerciseId, profile.deviceId, profile.revision)] = encode(profile)
                            prefs[activeKey(profile.exerciseId, profile.deviceId)] = profile.revision
                        }

                        ProfileStatus.CANDIDATE -> {
                            prefs[revisionKey(profile.exerciseId, profile.deviceId, profile.revision)] = encode(profile)
                            prefs[candidateKey(profile.exerciseId, profile.deviceId)] = profile.revision
                        }

                        ProfileStatus.RETIRED -> {
                            prefs[revisionKey(profile.exerciseId, profile.deviceId, profile.revision)] = encode(profile)
                        }
                    }
                }
                AppResult.success(Unit)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppResult.failure(AppError.Unknown("calibration save: ${e.message}"))
            }

        override suspend fun noteValidatedSet(
            exerciseId: Long,
            deviceId: String,
        ): AppResult<PromotionResult> =
            try {
                var result = PromotionResult.NO_CANDIDATE
                context.calibrationProfileDataStore.edit { prefs ->
                    val candRev =
                        prefs[candidateKey(exerciseId, deviceId)] ?: run {
                            result = PromotionResult.NO_CANDIDATE
                            return@edit
                        }
                    val raw =
                        prefs[revisionKey(exerciseId, deviceId, candRev)] ?: run {
                            result = PromotionResult.NO_CANDIDATE
                            return@edit
                        }
                    val candidate =
                        decode(raw, exerciseId, deviceId) ?: run {
                            result = PromotionResult.NO_CANDIDATE
                            return@edit
                        }
                    val incremented = candidate.copy(validatedSetCount = candidate.validatedSetCount + 1)
                    if (incremented.validatedSetCount >= ProfileLearningPolicy.PROMOTION_SETS) {
                        // Promotion: bisherige ACTIVE -> RETIRED, Kandidat wird aktiv.
                        activeRevision(prefs, exerciseId, deviceId)?.let { oldRev ->
                            val oldRaw = prefs[revisionKey(exerciseId, deviceId, oldRev)]
                            oldRaw?.let { oldRawStr ->
                                decode(oldRawStr, exerciseId, deviceId)?.let { old ->
                                    prefs[revisionKey(exerciseId, deviceId, oldRev)] =
                                        encode(old.copy(status = ProfileStatus.RETIRED))
                                }
                            }
                        }
                        prefs[revisionKey(exerciseId, deviceId, candRev)] =
                            encode(
                                incremented.copy(
                                    status = ProfileStatus.ACTIVE,
                                    validatedSetCount = 0,
                                ),
                            )
                        prefs[activeKey(exerciseId, deviceId)] = candRev
                        prefs.remove(candidateKey(exerciseId, deviceId))
                        result = PromotionResult.PROMOTED
                    } else {
                        prefs[revisionKey(exerciseId, deviceId, candRev)] = encode(incremented)
                        result = PromotionResult.PENDING
                    }
                }
                AppResult.success(result)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppResult.failure(AppError.Unknown("calibration promotion: ${e.message}"))
            }

        override suspend fun rollback(
            exerciseId: Long,
            deviceId: String,
        ): AppResult<Boolean> =
            try {
                var rolledBack = false
                context.calibrationProfileDataStore.edit { prefs ->
                    val activeRev = activeRevision(prefs, exerciseId, deviceId) ?: return@edit
                    val activeRaw = prefs[revisionKey(exerciseId, deviceId, activeRev)] ?: return@edit
                    val active = decode(activeRaw, exerciseId, deviceId) ?: return@edit
                    val parentRev = active.parentRevision ?: return@edit
                    val parentRaw = prefs[revisionKey(exerciseId, deviceId, parentRev)] ?: return@edit
                    val parent = decode(parentRaw, exerciseId, deviceId) ?: return@edit
                    prefs[revisionKey(exerciseId, deviceId, activeRev)] =
                        encode(active.copy(status = ProfileStatus.RETIRED))
                    prefs[revisionKey(exerciseId, deviceId, parentRev)] =
                        encode(parent.copy(status = ProfileStatus.ACTIVE))
                    prefs[activeKey(exerciseId, deviceId)] = parentRev
                    rolledBack = true
                }
                AppResult.success(rolledBack)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppResult.failure(AppError.Unknown("calibration rollback: ${e.message}"))
            }

        override suspend fun delete(
            exerciseId: Long,
            deviceId: String,
        ): AppResult<Unit> =
            try {
                context.calibrationProfileDataStore.edit { prefs ->
                    val prefix = "cal_${exerciseId}_$deviceId"
                    prefs.asMap().keys.filter { it.name.startsWith(prefix) }.forEach { key ->
                        prefs.remove(key)
                    }
                }
                AppResult.success(Unit)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppResult.failure(AppError.Unknown("calibration delete: ${e.message}"))
            }

        // --- Keys -------------------------------------------------------------

        private fun revisionKey(
            exerciseId: Long,
            deviceId: String,
            revision: Int,
        ) = stringPreferencesKey("cal_${exerciseId}_${deviceId}_r$revision")

        private fun activeKey(
            exerciseId: Long,
            deviceId: String,
        ) = intPreferencesKey("cal_${exerciseId}_${deviceId}_active")

        private fun candidateKey(
            exerciseId: Long,
            deviceId: String,
        ) = intPreferencesKey("cal_${exerciseId}_${deviceId}_cand")

        private fun legacyKey(
            exerciseId: Long,
            deviceId: String,
        ) = stringPreferencesKey("cal_${exerciseId}_$deviceId")

        private fun activeRevision(
            prefs: Preferences,
            exerciseId: Long,
            deviceId: String,
        ): Int? = prefs[activeKey(exerciseId, deviceId)] ?: prefs[legacyKey(exerciseId, deviceId)]?.let { 1 }

        /** Migriert einen v3-Blob unter dem Legacy-Key zu Revision 1 ACTIVE. */
        private suspend fun migrateLegacyIfNeeded(
            prefs: Preferences,
            exerciseId: Long,
            deviceId: String,
        ): CalibrationProfile? {
            val legacyRaw = prefs[legacyKey(exerciseId, deviceId)] ?: return null
            val migrated = decodeLegacy(legacyRaw, exerciseId, deviceId) ?: return null
            context.calibrationProfileDataStore.edit { editable ->
                editable[revisionKey(exerciseId, deviceId, 1)] =
                    encode(migrated.copy(revision = 1, status = ProfileStatus.ACTIVE))
                editable[activeKey(exerciseId, deviceId)] = 1
                editable.remove(legacyKey(exerciseId, deviceId))
            }
            return migrated
        }

        // --- Codec ------------------------------------------------------------

        /**
         * Schema v5 layout (semicolon-separated):
         * 0: schemaVersion, 1: engineVersion, 2: signalKind,
         * 3: rotationAxis (csv), 4: gyroBias (csv), 5: repTemplate (csv),
         * 6: expectedProminence, 7: qualityScore, 8: detectionThreshold,
         * 9: noiseFloor, 10: expectedDurationMs,
         * 11: revision, 12: parentRevision (-1 = null), 13: status,
         * 14: validatedSetCount, 15: accelThreshold (P2-Fix #22)
         */
        private fun encode(profile: CalibrationProfile): String =
            buildString {
                append(profile.schemaVersion)
                append(';')
                append(profile.engineVersion.name)
                append(';')
                append(profile.signalKind.name)
                append(';')
                append(profile.rotationAxis.joinToString(","))
                append(';')
                append(profile.gyroBias.joinToString(","))
                append(';')
                append(profile.repTemplate.joinToString(","))
                append(';')
                append(profile.expectedProminence)
                append(';')
                append(profile.qualityScore)
                append(';')
                append(profile.detectionThreshold)
                append(';')
                append(profile.noiseFloor)
                append(';')
                append(profile.expectedDurationMs)
                append(';')
                append(profile.revision)
                append(';')
                append(profile.parentRevision ?: -1)
                append(';')
                append(profile.status.name)
                append(';')
                append(profile.validatedSetCount)
                append(';')
                append(profile.accelThreshold)
            }

        /**
         * Liest v5 UND v4. v4-Bloebe (15 Felder, ohne `accelThreshold`) werden
         * lesend auf v5 hochgezogen: `accelThreshold = 0.0` bedeutet "nicht
         * kalibriert", die Live-Pipeline laeuft dann wie bisher ohne
         * Accel-Voting. So muss niemand wegen P2-Fix #22 neu kalibrieren; der
         * Wert entsteht bei der naechsten Kalibrierung von selbst.
         */
        private fun decode(
            raw: String,
            exerciseId: Long,
            deviceId: String,
        ): CalibrationProfile? {
            if (raw.isEmpty()) return null
            val parts = raw.split(';')
            if (parts.size != V5_FIELD_COUNT && parts.size != V4_FIELD_COUNT) return null
            val schema = parts[0].toIntOrNull() ?: return null
            // Versioned read: nur die aktuelle und die direkt vorhergehende
            // Revision werden interpretiert.
            val expectedSchema = if (parts.size == V5_FIELD_COUNT) CalibrationProfile.PROFILE_SCHEMA_VERSION else 4
            if (schema != expectedSchema) return null
            val engine =
                parts[1].let { name ->
                    RepEngineVersion.entries.firstOrNull { it.name == name }
                } ?: return null
            val signalKind =
                parts[2].let { name ->
                    RepSignalKind.entries.firstOrNull { it.name == name }
                } ?: return null
            val axis = parts[3].split(',').mapNotNull { it.toDoubleOrNull() }
            val bias = parts[4].split(',').mapNotNull { it.toDoubleOrNull() }
            val template = parts[5].split(',').mapNotNull { it.toDoubleOrNull() }
            val prominence = parts[6].toDoubleOrNull() ?: return null
            val quality = parts[7].toDoubleOrNull() ?: return null
            val threshold = parts[8].toDoubleOrNull() ?: return null
            val noiseFloor = parts[9].toDoubleOrNull() ?: return null
            val durationMs = parts[10].toDoubleOrNull() ?: return null
            val revision = parts[11].toIntOrNull() ?: return null
            val parentRaw = parts[12].toIntOrNull() ?: return null
            val status =
                parts[13].let { name ->
                    ProfileStatus.entries.firstOrNull { it.name == name }
                } ?: return null
            val validatedSets = parts[14].toIntOrNull() ?: return null
            val accelThreshold =
                if (parts.size == V5_FIELD_COUNT) {
                    parts[15].toDoubleOrNull() ?: return null
                } else {
                    0.0
                }
            if (axis.size != 3 || bias.size != 3 || template.isEmpty()) return null
            if (!threshold.isFinite() || threshold < 0.0) return null
            if (!durationMs.isFinite() || durationMs <= 0.0) return null
            if (!accelThreshold.isFinite() || accelThreshold < 0.0) return null
            return CalibrationProfile(
                exerciseId = exerciseId,
                deviceId = deviceId,
                rotationAxis = axis,
                gyroBias = bias,
                repTemplate = template,
                expectedProminence = prominence,
                qualityScore = quality,
                schemaVersion = CalibrationProfile.PROFILE_SCHEMA_VERSION,
                engineVersion = engine,
                signalKind = signalKind,
                detectionThreshold = threshold,
                noiseFloor = noiseFloor,
                expectedDurationMs = durationMs,
                revision = revision,
                parentRevision = parentRaw.takeIf { it >= 0 },
                status = status,
                validatedSetCount = validatedSets,
                accelThreshold = accelThreshold,
            )
        }

        /**
         * v3-Layout (11 Felder, keine Revisionsfelder). Wird bei der
         * Migration einmalig zu Revision 1 ACTIVE hochgezogen.
         */
        private fun decodeLegacy(
            raw: String,
            exerciseId: Long,
            deviceId: String,
        ): CalibrationProfile? {
            val parts = raw.split(';')
            if (parts.size != 11) return null
            val schema = parts[0].toIntOrNull() ?: return null
            if (schema != 3) return null
            val engine =
                parts[1].let { name ->
                    RepEngineVersion.entries.firstOrNull { it.name == name }
                } ?: return null
            val signalKind =
                parts[2].let { name ->
                    RepSignalKind.entries.firstOrNull { it.name == name }
                } ?: return null
            val axis = parts[3].split(',').mapNotNull { it.toDoubleOrNull() }
            val bias = parts[4].split(',').mapNotNull { it.toDoubleOrNull() }
            val template = parts[5].split(',').mapNotNull { it.toDoubleOrNull() }
            val prominence = parts[6].toDoubleOrNull() ?: return null
            val quality = parts[7].toDoubleOrNull() ?: return null
            val threshold = parts[8].toDoubleOrNull() ?: return null
            val noiseFloor = parts[9].toDoubleOrNull() ?: return null
            val durationMs = parts[10].toDoubleOrNull() ?: return null
            if (axis.size != 3 || bias.size != 3 || template.isEmpty()) return null
            if (!threshold.isFinite() || threshold < 0.0) return null
            if (!durationMs.isFinite() || durationMs <= 0.0) return null
            return CalibrationProfile(
                exerciseId = exerciseId,
                deviceId = deviceId,
                rotationAxis = axis,
                gyroBias = bias,
                repTemplate = template,
                expectedProminence = prominence,
                qualityScore = quality,
                schemaVersion = CalibrationProfile.PROFILE_SCHEMA_VERSION,
                engineVersion = engine,
                signalKind = signalKind,
                detectionThreshold = threshold,
                noiseFloor = noiseFloor,
                expectedDurationMs = durationMs,
                revision = 1,
                parentRevision = null,
                status = ProfileStatus.ACTIVE,
                validatedSetCount = 0,
            )
        }

        private companion object {
            /** Feldanzahl im v5-Blob (mit accelThreshold). */
            const val V5_FIELD_COUNT = 16

            /** Feldanzahl im v4-Blob (ohne accelThreshold). */
            const val V4_FIELD_COUNT = 15
        }
    }
