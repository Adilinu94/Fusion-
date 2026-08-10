package com.dropsync.data.sensor

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dropsync.core.common.AppResult
import com.dropsync.domain.sensor.CalibrationProfile
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
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
        repTemplate = listOf(0.1, 0.5, 1.0, 0.5, 0.1),
        signalPeakLevel = 1.5,
        noisePeakLevel = 0.2,
        expectedProminence = 0.4,
        expectedDurationSamples = 25.0,
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
            assertEquals(original.repTemplate, restored?.repTemplate)
            assertEquals(original.signalPeakLevel, restored?.signalPeakLevel ?: 0.0, 1e-9)
            assertEquals(original.noisePeakLevel, restored?.noisePeakLevel ?: 0.0, 1e-9)
            assertEquals(original.expectedProminence, restored?.expectedProminence ?: 0.0, 1e-9)
            assertEquals(original.expectedDurationSamples, restored?.expectedDurationSamples ?: 0.0, 1e-9)
        }

    @Test
    fun `profiles are keyed per exercise and device`() =
        runTest {
            repo.save(profile(1L, "AA:BB").copy(signalPeakLevel = 1.0))
            repo.save(profile(2L, "AA:BB").copy(signalPeakLevel = 2.0))
            repo.save(profile(1L, "CC:DD").copy(signalPeakLevel = 3.0))
            assertEquals(1.0, (repo.load(1L, "AA:BB") as AppResult.Success).value?.signalPeakLevel ?: 0.0, 1e-9)
            assertEquals(2.0, (repo.load(2L, "AA:BB") as AppResult.Success).value?.signalPeakLevel ?: 0.0, 1e-9)
            assertEquals(3.0, (repo.load(1L, "CC:DD") as AppResult.Success).value?.signalPeakLevel ?: 0.0, 1e-9)
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
            repo.save(profile().copy(signalPeakLevel = 1.0))
            repo.save(profile().copy(signalPeakLevel = 9.9))
            assertEquals(9.9, (repo.load(1L, "AA:BB") as AppResult.Success).value?.signalPeakLevel ?: 0.0, 1e-9)
        }
}
