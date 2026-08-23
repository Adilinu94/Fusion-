package com.dropsync.data.sensor

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dropsync.core.common.AppResult
import com.dropsync.domain.sensor.CalibrationProfile
import com.dropsync.domain.sensor.ProfileStatus
import com.dropsync.domain.sensor.PromotionResult
import com.dropsync.domain.sensor.RepEngineVersion
import com.dropsync.domain.sensor.RepSignalKind
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class DataStoreCalibrationProfileRepositoryTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val repo = DataStoreCalibrationProfileRepository(context)

    private fun profile(
        exerciseId: Long = 1L,
        deviceId: String = "AA:BB",
    ) = CalibrationProfile(
        exerciseId = exerciseId,
        deviceId = deviceId,
        rotationAxis = listOf(0.0, 0.0, 1.0),
        gyroBias = listOf(0.01, -0.02, 0.03),
        repTemplate = listOf(0.1, 0.5, 1.0, 0.5, 0.1),
        expectedProminence = 0.4,
        qualityScore = 0.85,
        detectionThreshold = 1.5,
        noiseFloor = 0.2,
        expectedDurationMs = 500.0,
    )

    @Before
    fun clean() =
        runTest {
            // Unique key per test run; delete anything stale.
            repo.delete(1L, "AA:BB")
            repo.delete(2L, "AA:BB")
            repo.delete(1L, "CC:DD")
        }

    @Test
    fun `load returns null when nothing saved`() =
        runTest {
            val result = repo.load(9L, "ZZ:ZZ")
            assertTrue(result is AppResult.Success)
            assertNull((result as AppResult.Success).value)
        }

    @Test
    fun `save then load round-trips the profile`() =
        runTest {
            val original = profile()
            repo.save(original)
            val loaded = repo.load(1L, "AA:BB")
            assertTrue(loaded is AppResult.Success)
            val restored = (loaded as AppResult.Success).value
            assertEquals(original.exerciseId, restored?.exerciseId)
            assertEquals(original.deviceId, restored?.deviceId)
            assertEquals(original.rotationAxis, restored?.rotationAxis)
            assertEquals(original.gyroBias, restored?.gyroBias)
            assertEquals(original.repTemplate, restored?.repTemplate)
            assertEquals(original.expectedProminence, restored?.expectedProminence ?: 0.0, 1e-9)
            assertEquals(original.qualityScore, restored?.qualityScore ?: 0.0, 1e-9)
            assertEquals(original.detectionThreshold, restored?.detectionThreshold ?: 0.0, 1e-9)
            assertEquals(original.noiseFloor, restored?.noiseFloor ?: 0.0, 1e-9)
            assertEquals(original.expectedDurationMs, restored?.expectedDurationMs ?: 0.0, 1e-9)
            assertEquals(original.engineVersion, restored?.engineVersion)
            assertEquals(original.signalKind, restored?.signalKind)
            assertEquals(original.schemaVersion, restored?.schemaVersion)
        }

    @Test
    fun `threshold round-trips exactly`() {
        // Umbauplan Phase 1.4: der gespeicherte Threshold entspricht nach
        // dem Laden exakt dem kalibrierten Threshold.
        val theta = 47.123456789
        runTest {
            repo.save(profile().copy(detectionThreshold = theta))
            val restored = (repo.load(1L, "AA:BB") as AppResult.Success).value
            assertEquals(theta, restored?.detectionThreshold ?: 0.0, 1e-9)
        }
    }

    @Test
    fun `profile with empty template is not returned`() =
        runTest {
            // An axis/template-less profile cannot drive the pipeline; the
            // codec drops it (decode returns null) -> load reports "absent".
            repo.save(profile(5L, "LEGACY").copy(repTemplate = emptyList()))
            assertNull((repo.load(5L, "LEGACY") as AppResult.Success).value)
        }

    @Test
    fun `profiles are keyed per exercise and device`() =
        runTest {
            repo.save(profile(1L, "AA:BB").copy(detectionThreshold = 1.0))
            repo.save(profile(2L, "AA:BB").copy(detectionThreshold = 2.0))
            repo.save(profile(1L, "CC:DD").copy(detectionThreshold = 3.0))
            assertEquals(1.0, (repo.load(1L, "AA:BB") as AppResult.Success).value?.detectionThreshold ?: 0.0, 1e-9)
            assertEquals(2.0, (repo.load(2L, "AA:BB") as AppResult.Success).value?.detectionThreshold ?: 0.0, 1e-9)
            assertEquals(3.0, (repo.load(1L, "CC:DD") as AppResult.Success).value?.detectionThreshold ?: 0.0, 1e-9)
        }

    @Test
    fun `delete removes only the matching profile`() =
        runTest {
            repo.save(profile(1L, "AA:BB"))
            repo.save(profile(2L, "AA:BB"))
            repo.delete(1L, "AA:BB")
            assertNull((repo.load(1L, "AA:BB") as AppResult.Success).value)
            assertTrue((repo.load(2L, "AA:BB") as AppResult.Success).value != null)
        }

    @Test
    fun `save overwrites an existing profile for the same key`() =
        runTest {
            repo.save(profile().copy(detectionThreshold = 1.0))
            repo.save(profile().copy(detectionThreshold = 9.9))
            assertEquals(9.9, (repo.load(1L, "AA:BB") as AppResult.Success).value?.detectionThreshold ?: 0.0, 1e-9)
        }

    @Test
    fun `engine version and signal kind round-trip`() =
        runTest {
            repo.save(
                profile().copy(
                    engineVersion = RepEngineVersion.V1_CURRENT,
                    signalKind = RepSignalKind.SIGNED_GYRO_PROJECTION,
                ),
            )
            val restored = (repo.load(1L, "AA:BB") as AppResult.Success).value
            assertEquals(RepEngineVersion.V1_CURRENT, restored?.engineVersion)
            assertEquals(RepSignalKind.SIGNED_GYRO_PROJECTION, restored?.signalKind)
        }

    // --- Umbauplan Phase 7.4: Revisionen -------------------------------

    @Test
    fun `revision und status round-trippen in v4`() =
        runTest {
            repo.save(
                profile().copy(
                    revision = 3,
                    parentRevision = 2,
                    status = ProfileStatus.CANDIDATE,
                    validatedSetCount = 1,
                ),
            )
            val restored = (repo.loadHistory(1L, "AA:BB") as AppResult.Success).value.single()
            assertEquals(3, restored.revision)
            assertEquals(2, restored.parentRevision)
            assertEquals(ProfileStatus.CANDIDATE, restored.status)
            assertEquals(1, restored.validatedSetCount)
            assertEquals(CalibrationProfile.PROFILE_SCHEMA_VERSION, restored.schemaVersion)
        }

    @Test
    fun `save CANDIDATE laesst die aktive Revision unangetastet`() =
        runTest {
            repo.save(profile().copy(revision = 1, status = ProfileStatus.ACTIVE))
            repo.save(
                profile().copy(
                    revision = 2,
                    parentRevision = 1,
                    status = ProfileStatus.CANDIDATE,
                    detectionThreshold = 9.9,
                ),
            )
            val active = (repo.load(1L, "AA:BB") as AppResult.Success).value
            assertEquals(1, active?.revision)
            assertEquals(ProfileStatus.ACTIVE, active?.status)
            assertEquals(1.5, active?.detectionThreshold ?: 0.0, 1e-9)
            val history = (repo.loadHistory(1L, "AA:BB") as AppResult.Success).value
            assertEquals(2, history.size)
        }

    @Test
    fun `noteValidatedSet promoted nach drei validierten Sets`() =
        runTest {
            repo.save(profile().copy(revision = 1, status = ProfileStatus.ACTIVE))
            repo.save(
                profile().copy(
                    revision = 2,
                    parentRevision = 1,
                    status = ProfileStatus.CANDIDATE,
                ),
            )
            assertEquals(PromotionResult.PENDING, (repo.noteValidatedSet(1L, "AA:BB") as AppResult.Success).value)
            assertEquals(PromotionResult.PENDING, (repo.noteValidatedSet(1L, "AA:BB") as AppResult.Success).value)
            assertEquals(PromotionResult.PROMOTED, (repo.noteValidatedSet(1L, "AA:BB") as AppResult.Success).value)

            val active = (repo.load(1L, "AA:BB") as AppResult.Success).value
            assertEquals(2, active?.revision)
            assertEquals(ProfileStatus.ACTIVE, active?.status)
            assertEquals(0, active?.validatedSetCount)

            // Die alte Revision ist RETIRED, die Kette bleibt erhalten.
            val history = (repo.loadHistory(1L, "AA:BB") as AppResult.Success).value
            val retired = history.firstOrNull { it.revision == 1 }
            assertEquals(ProfileStatus.RETIRED, retired?.status)
            assertEquals(null, retired?.parentRevision)
            assertEquals(1, history.first { it.revision == 2 }.parentRevision)
        }

    @Test
    fun `noteValidatedSet ohne Kandidat liefert NO_CANDIDATE`() =
        runTest {
            repo.save(profile().copy(revision = 1, status = ProfileStatus.ACTIVE))
            assertEquals(
                PromotionResult.NO_CANDIDATE,
                (repo.noteValidatedSet(1L, "AA:BB") as AppResult.Success).value,
            )
        }

    @Test
    fun `rollback aktiviert die parentRevision`() =
        runTest {
            // Revision 2 ist aktiv (parent 1), Revision 1 ist RETIRED.
            repo.save(profile().copy(revision = 1, status = ProfileStatus.ACTIVE))
            repo.save(profile().copy(revision = 2, parentRevision = 1, status = ProfileStatus.ACTIVE))
            val rolledBack = (repo.rollback(1L, "AA:BB") as AppResult.Success).value
            assertTrue(rolledBack)
            val active = (repo.load(1L, "AA:BB") as AppResult.Success).value
            assertEquals(1, active?.revision)
            assertEquals(ProfileStatus.ACTIVE, active?.status)
        }

    @Test
    fun `rollback ohne parentRevision liefert false`() =
        runTest {
            repo.save(profile().copy(revision = 1, status = ProfileStatus.ACTIVE))
            val rolledBack = (repo.rollback(1L, "AA:BB") as AppResult.Success).value
            assertFalse(rolledBack)
            val active = (repo.load(1L, "AA:BB") as AppResult.Success).value
            assertEquals(1, active?.revision)
            assertEquals(ProfileStatus.ACTIVE, active?.status)
        }

    @Test
    fun `v3-Legacy-Blob wird einmalig zu Revision 1 ACTIVE migriert`() =
        runTest {
            // Simuliert einen vor dem Umbau gespeicherten v3-Blob (11 Felder,
            // Legacy-Key) ueber denselben DataStore-Namen.
            val dataStore = context.calibrationProfileDataStore
            val legacyKey = stringPreferencesKey("cal_3_LEGACY")
            val v3Blob =
                "3;V2_RELIABLE;SIGNED_GYRO_PROJECTION;0.0,0.0,1.0;0.01,-0.02,0.03;" +
                    "0.1,0.5,1.0,0.5,0.1;0.4;0.85;1.5;0.2;500.0"
            dataStore.edit { prefs -> prefs[legacyKey] = v3Blob }

            // load() migriert: Revision 1, ACTIVE, alter Key wird entfernt.
            val loaded = (repo.load(3L, "LEGACY") as AppResult.Success).value
            assertEquals(1, loaded?.revision)
            assertEquals(ProfileStatus.ACTIVE, loaded?.status)
            assertEquals(CalibrationProfile.PROFILE_SCHEMA_VERSION, loaded?.schemaVersion)

            // Zweiter Load liest aus dem neuen Key (Migration ist idempotent).
            val loadedAgain = (repo.load(3L, "LEGACY") as AppResult.Success).value
            assertEquals(1, loadedAgain?.revision)

            // Legacy-Key ist entfernt.
            val prefs = dataStore.data.first()
            assertNull(prefs[legacyKey])
            repo.delete(3L, "LEGACY")
        }
}
