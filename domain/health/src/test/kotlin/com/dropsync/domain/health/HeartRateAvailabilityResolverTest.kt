package com.dropsync.domain.health

import com.dropsync.domain.health.HeartRateAvailabilityResolver.ProviderState
import org.junit.Assert.assertEquals
import org.junit.Test

/** Verfuegbarkeits-Zustandsautomat (Herzfrequenz-Plan Phase 1). */
class HeartRateAvailabilityResolverTest {
    @Test
    fun `provider nicht verfuegbar dominiert alle anderen eingaben`() {
        for (granted in listOf(true, false)) {
            for (hasSample in listOf(true, false)) {
                assertEquals(
                    HeartRateAvailability.HEALTH_CONNECT_NOT_AVAILABLE,
                    HeartRateAvailabilityResolver.resolve(ProviderState.NOT_AVAILABLE, granted, hasSample),
                )
            }
        }
    }

    @Test
    fun `update noetig ist eigener zustand und nicht not available`() {
        assertEquals(
            HeartRateAvailability.UPDATE_REQUIRED,
            HeartRateAvailabilityResolver.resolve(ProviderState.UPDATE_REQUIRED, true, true),
        )
    }

    @Test
    fun `fehlende berechtigung vor datenlage`() {
        assertEquals(
            HeartRateAvailability.PERMISSION_REQUIRED,
            HeartRateAvailabilityResolver.resolve(ProviderState.AVAILABLE, false, true),
        )
    }

    @Test
    fun `berechtigt ohne daten ist no recent data`() {
        assertEquals(
            HeartRateAvailability.NO_RECENT_DATA,
            HeartRateAvailabilityResolver.resolve(ProviderState.AVAILABLE, true, false),
        )
    }

    @Test
    fun `berechtigt mit daten ist ready`() {
        assertEquals(
            HeartRateAvailability.READY,
            HeartRateAvailabilityResolver.resolve(ProviderState.AVAILABLE, true, true),
        )
    }
}
