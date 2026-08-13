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

    @Test
    fun `session zaehlt retries ueber mehrere callbacks hinweg`() {
        val session = MtuNegotiationSession()

        // 1. Fehler (133) -> Retry, Zaehler auf 1.
        val first = session.onMtuChanged(mtu = 0, status = MtuNegotiator.GATT_ERROR)
        assertTrue(first is MtuNegotiator.Decision.Request)
        assertEquals(2, (first as MtuNegotiator.Decision.Request).attempt)
        assertEquals(1, session.retriesUsed)

        // Timeout -> zweiter Retry, Zaehler auf 2.
        val second = session.onTimeout()
        assertTrue(second is MtuNegotiator.Decision.Request)
        assertEquals(3, (second as MtuNegotiator.Decision.Request).attempt)
        assertEquals(2, session.retriesUsed)

        // Noch ein Fehler -> Retries erschoepft, Fallback.
        val third = session.onMtuChanged(mtu = 0, status = 8)
        assertTrue(third is MtuNegotiator.Decision.UseFallback)
        assertEquals(MtuNegotiator.FALLBACK_MTU, (third as MtuNegotiator.Decision.UseFallback).mtu)
    }

    @Test
    fun `session reset beginnt neu`() {
        val session = MtuNegotiationSession()
        session.onTimeout()
        session.onTimeout()
        assertEquals(2, session.retriesUsed)

        session.reset()
        assertEquals(0, session.retriesUsed)
        val d = session.onTimeout()
        assertTrue(d is MtuNegotiator.Decision.Request)
        assertEquals(2, (d as MtuNegotiator.Decision.Request).attempt)
    }

    @Test
    fun `request mtu bleibt unterhalb der hyperos off-by-one grenze`() {
        // HyperOS MTU-517 Bug (Produktions-Praxis): der angefragte Wert
        // muss deutlich unter 517 bleiben, 512 waere riskant.
        assertTrue(
            "512 trifft die HyperOS-Grenze, $MtuNegotiator.REQUEST_MTU ist sicher",
            MtuNegotiator.REQUEST_MTU < 517,
        )
    }

    @Test
    fun `erfolg nach retries liefert die verhandelte mtu`() {
        val session = MtuNegotiationSession()
        session.onTimeout() // retry 1
        val d = session.onMtuChanged(mtu = 247, status = MtuNegotiator.GATT_SUCCESS)
        assertTrue(d is MtuNegotiator.Decision.Negotiated)
        assertEquals(247, (d as MtuNegotiator.Decision.Negotiated).mtu)
    }
}
