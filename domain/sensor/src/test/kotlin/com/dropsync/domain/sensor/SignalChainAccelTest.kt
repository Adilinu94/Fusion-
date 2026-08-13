package com.dropsync.domain.sensor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Accel-Zweig der SignalChain (Umbauplan Punkt 4): Abweichung der
 * Magnitude von 1 g wird gefiltert und als smoothedAccel in den Frame
 * gelegt. Ohne accelEnabled bleibt der Kanal 0.
 */
class SignalChainAccelTest {
    private fun chain(accelEnabled: Boolean = true) =
        SignalChain(
            rotationAxis = doubleArrayOf(1.0, 0.0, 0.0),
            gyroBias = doubleArrayOf(0.0, 0.0, 0.0),
            settleSamples = 1,
            accelEnabled = accelEnabled,
        )

    @Test
    fun `accel zweig erzeugt smoothedAccel ungleich null bei bewegung`() {
        val chain = chain()
        // Magnitude schlaegt von 1 g auf 1.5 g aus -> Abweichung 0.5.
        val frame =
            chain.process(
                timestampMs = 0,
                gx = 0.0,
                gy = 0.0,
                gz = 0.0,
                ax = 1.5,
                ay = 0.0,
                az = 0.0,
            )
        assertTrue("smoothedAccel muss auf die Abweichung reagieren", frame.smoothedAccel > 0.0)
        assertTrue("accelEnvelope muss > 0 sein", frame.accelEnvelope > 0.0)
    }

    @Test
    fun `ruhezustand erzeugt smoothedAccel nahe null`() {
        val chain = chain()
        // Exakt 1 g: Abweichung 0, nach Filterung ~0.
        val frame =
            chain.process(
                timestampMs = 0,
                gx = 0.0,
                gy = 0.0,
                gz = 0.0,
                ax = 1.0,
                ay = 0.0,
                az = 0.0,
            )
        assertEquals(0.0, frame.smoothedAccel, 1e-6)
    }

    @Test
    fun `deaktivierter accel zweig bleibt null`() {
        val chain = chain(accelEnabled = false)
        val frame =
            chain.process(
                timestampMs = 0,
                gx = 0.0,
                gy = 0.0,
                gz = 0.0,
                ax = 1.5,
                ay = 0.0,
                az = 0.0,
            )
        assertEquals(0.0, frame.smoothedAccel, 1e-6)
        assertEquals(0.0, frame.accelEnvelope, 1e-6)
    }
}
