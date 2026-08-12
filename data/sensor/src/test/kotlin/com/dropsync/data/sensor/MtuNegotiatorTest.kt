package com.dropsync.data.sensor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests fuer [MtuNegotiator] (5a): Timeout/Retry/Fallback-Logik ohne BLE.
 */
class MtuNegotiatorTest {
    @Test
    fun `erfolg liefert verhandelte mtu`() {
        val d = MtuNegotiator.onMtuChanged(mtu = 247, status = MtuNegotiator.GATT_SUCCESS, retriesUsed = 0)
        assertTrue(d is MtuNegotiator.Decision.Negotiated)
        assertEquals(247, (d as MtuNegotiator.Decision.Negotiated).mtu)
    }

    @Test
    fun `status 133 loest retry aus nicht endzustand`() {
        val d = MtuNegotiator.onMtuChanged(mtu = 0, status = MtuNegotiator.GATT_ERROR, retriesUsed = 0)
        assertTrue(d is MtuNegotiator.Decision.Request)
        assertEquals(2, (d as MtuNegotiator.Decision.Request).attempt)
    }

    @Test
    fun `nach max retries faellt er auf mtu 23 zurueck`() {
        // retriesUsed == MAX_RETRIES -> kein weiterer Versuch.
        val d = MtuNegotiator.onMtuChanged(mtu = 0, status = 8, retriesUsed = MtuNegotiator.MAX_RETRIES)
        assertTrue(d is MtuNegotiator.Decision.UseFallback)
        assertEquals(MtuNegotiator.FALLBACK_MTU, (d as MtuNegotiator.Decision.UseFallback).mtu)
    }

    @Test
    fun `timeout loest retry aus und danach fallback`() {
        val first = MtuNegotiator.onTimeout(retriesUsed = 0)
        assertTrue(first is MtuNegotiator.Decision.Request)

        val exhausted = MtuNegotiator.onTimeout(retriesUsed = MtuNegotiator.MAX_RETRIES)
        assertTrue(exhausted is MtuNegotiator.Decision.UseFallback)
    }

    @Test
    fun `retry zaehler erhoeht den versuch`() {
        val d = MtuNegotiator.onTimeout(retriesUsed = 1)
        assertEquals(3, (d as MtuNegotiator.Decision.Request).attempt)
    }
}
