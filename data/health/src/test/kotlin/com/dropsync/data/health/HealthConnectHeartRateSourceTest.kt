package com.dropsync.data.health

import com.dropsync.core.common.AppError
import com.dropsync.core.common.AppResult
import com.dropsync.core.testing.FakeClock
import com.dropsync.core.testing.TestDispatcherProvider
import com.dropsync.domain.health.HeartRateAvailability
import com.dropsync.domain.health.HeartRateAvailabilityResolver.ProviderState
import com.dropsync.domain.health.HeartRateSample
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verhalten der Health-Connect-Source rein auf der JVM (Herzfrequenz-Plan
 * Phase 1): Zustandsautomat, Initial-Read, Changes-Pfad und der
 * Token-Ablauf-Fallback — alles gegen ein Fake-Gateway ohne SDK.
 */
class HealthConnectHeartRateSourceTest {
    private val gateway = FakeGateway()
    private val tokenStore = FakeTokenStore()
    private val clock = FakeClock(initialEpochMillis = NOW_MS)

    private fun source() = HealthConnectHeartRateSource(gateway, tokenStore, clock, TestDispatcherProvider())

    @Test
    fun `ohne provider bleibt availability not available und refresh ist erfolgreich`() =
        runTest {
            gateway.provider = ProviderState.NOT_AVAILABLE
            val source = source()

            val result = source.refresh()

            assertTrue(result is AppResult.Success)
            assertEquals(HeartRateAvailability.HEALTH_CONNECT_NOT_AVAILABLE, source.availability.first())
            assertNull(tokenStore.stored)
        }

    @Test
    fun `update noetig wird als eigener zustand gemeldet`() =
        runTest {
            gateway.provider = ProviderState.UPDATE_REQUIRED
            val source = source()

            source.refreshAvailability()

            assertEquals(HeartRateAvailability.UPDATE_REQUIRED, source.availability.first())
        }

    @Test
    fun `fehlende berechtigung liefert permission denied fehler`() =
        runTest {
            gateway.granted = false
            val source = source()

            val result = source.refresh()

            assertTrue(result is AppResult.Failure)
            assertEquals(
                AppError.PermissionDenied(HealthConnectHeartRateSource.READ_HEART_RATE_PERMISSION),
                (result as AppResult.Failure).error,
            )
            assertEquals(HeartRateAvailability.PERMISSION_REQUIRED, source.availability.first())
        }

    @Test
    fun `initial read nimmt das neueste von mehreren samples und speichert frischen token`() =
        runTest {
            // Entspricht dem Fall "mehrere Samples in einem Record": das
            // Gateway liefert die geflatteten Samples, das neueste zaehlt.
            gateway.recentSamples =
                listOf(
                    HeartRateSample(bpm = 88, recordedAtEpochMs = NOW_MS - 60_000),
                    HeartRateSample(bpm = 132, recordedAtEpochMs = NOW_MS - 5_000),
                    HeartRateSample(bpm = 120, recordedAtEpochMs = NOW_MS - 30_000),
                )
            val source = source()

            val result = source.refresh()

            assertTrue(result is AppResult.Success)
            assertEquals(132, source.latestSample.first()?.bpm)
            assertEquals("token-1", tokenStore.stored)
            assertEquals(HeartRateAvailability.READY, source.availability.first())
            // Initial-Read fragt exakt das 15-Minuten-Fenster ab (Plan 3.3/3).
            assertEquals(NOW_MS - HealthConnectHeartRateSource.INITIAL_WINDOW_MS, gateway.lastReadSince)
        }

    @Test
    fun `changes pfad aktualisiert sample und schreibt naechsten token fort`() =
        runTest {
            tokenStore.stored = "token-1"
            gateway.changes =
                HealthChanges(
                    tokenExpired = false,
                    nextToken = "token-2",
                    samples = listOf(HeartRateSample(bpm = 141, recordedAtEpochMs = NOW_MS - 1_000)),
                )
            val source = source()

            source.refresh()

            assertEquals(141, source.latestSample.first()?.bpm)
            assertEquals("token-2", tokenStore.stored)
            assertEquals(0, gateway.readCalls) // kein Vollzeitraum-Polling
        }

    @Test
    fun `abgelaufener token faellt auf initial read mit frischem token zurueck`() =
        runTest {
            tokenStore.stored = "token-alt"
            gateway.changes = HealthChanges(tokenExpired = true, nextToken = null, samples = emptyList())
            gateway.recentSamples = listOf(HeartRateSample(bpm = 95, recordedAtEpochMs = NOW_MS - 2_000))
            val source = source()

            val result = source.refresh()

            assertTrue(result is AppResult.Success)
            assertEquals(95, source.latestSample.first()?.bpm)
            assertEquals("token-1", tokenStore.stored)
            assertEquals(1, gateway.readCalls)
        }

    @Test
    fun `aeltere changes verdraengen ein neueres sample nicht`() =
        runTest {
            gateway.recentSamples = listOf(HeartRateSample(bpm = 150, recordedAtEpochMs = NOW_MS - 1_000))
            val source = source()
            source.refresh()

            gateway.changes =
                HealthChanges(
                    tokenExpired = false,
                    nextToken = "token-2",
                    samples = listOf(HeartRateSample(bpm = 70, recordedAtEpochMs = NOW_MS - 500_000)),
                )
            source.refresh()

            assertEquals(150, source.latestSample.first()?.bpm)
        }

    private class FakeGateway : HealthConnectGateway {
        var provider = ProviderState.AVAILABLE
        var granted = true
        var recentSamples: List<HeartRateSample> = emptyList()
        var changes = HealthChanges(tokenExpired = false, nextToken = null, samples = emptyList())
        var readCalls = 0
        var lastReadSince: Long? = null
        private var tokenCounter = 0

        override fun providerState(): ProviderState = provider

        override suspend fun permissionGranted(): Boolean = granted

        override suspend fun readRecentSamples(
            sinceEpochMs: Long,
            untilEpochMs: Long,
        ): List<HeartRateSample> {
            readCalls++
            lastReadSince = sinceEpochMs
            return recentSamples
        }

        override suspend fun freshChangesToken(): String = "token-${++tokenCounter}"

        override suspend fun changesSince(token: String): HealthChanges = changes
    }

    private class FakeTokenStore : ChangesTokenStore {
        var stored: String? = null

        override suspend fun changesToken(): String? = stored

        override suspend fun saveChangesToken(token: String) {
            stored = token
        }

        override suspend fun clearChangesToken() {
            stored = null
        }
    }

    companion object {
        private const val NOW_MS = 1_753_600_000_000L
    }
}
