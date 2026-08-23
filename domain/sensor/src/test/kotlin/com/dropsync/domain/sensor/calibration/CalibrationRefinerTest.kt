package com.dropsync.domain.sensor.calibration

import com.dropsync.domain.sensor.CalibrationProfile
import com.dropsync.domain.sensor.ProfileStatus
import com.dropsync.domain.sensor.SensorSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

/**
 * Learn-loop contract (Fusion Phase 4 + Umbauplan Phase 7): a corrected rep
 * count re-analyses the buffered set and nudges the stored profile towards
 * the new evidence. Der Refiner lernt nur bei exakt [correctedReps]
 * zweiphasigen Peaks und revalidiert das Kandidatenprofil durch die echte
 * Live-Pipeline.
 */
class CalibrationRefinerTest {
    private val axisZ = listOf(0.0, 0.0, 1.0)
    private val zeroBias = listOf(0.0, 0.0, 0.0)

    private fun profile(
        prominence: Double = 200.0,
        durationMs: Double = 1_000.0,
        threshold: Double = 15.0,
    ) = CalibrationProfile(
        exerciseId = 1L,
        deviceId = "dev",
        rotationAxis = axisZ,
        gyroBias = zeroBias,
        repTemplate = emptyList(),
        expectedProminence = prominence,
        qualityScore = 0.9,
        detectionThreshold = threshold,
        noiseFloor = 5.0,
        expectedDurationMs = durationMs,
    )

    /**
     * Synthetisches Set: [reps] vollstaendige Sinus-Zyklen auf gz bei 50 Hz
     * (positiv auf, Richtungswechsel, negativ durch, Rueckkehr zur Baseline),
     * dazwischen Ruhe. 60 Ruhe-Samples am Anfang (SignalChain-Settle).
     */
    private fun setWith(
        reps: Int,
        cycleSamples: Int = 50,
        amplitude: Double = 120.0,
    ): List<SensorSample> {
        val samples = mutableListOf<SensorSample>()
        var t = 0L
        for (i in 0 until 60) {
            samples.add(SensorSample(timestampMs = t, ax = 0.0, ay = 0.0, az = 9.81, gx = 0.0, gy = 0.0, gz = 0.0))
            t += 20
        }
        repeat(reps) {
            for (i in 0 until cycleSamples) {
                val gz = amplitude * sin(2.0 * PI * i / cycleSamples)
                samples.add(SensorSample(timestampMs = t, ax = 0.0, ay = 0.0, az = 9.81, gx = 0.0, gy = 0.0, gz = gz))
                t += 20
            }
            for (i in 0 until 30) {
                samples.add(SensorSample(timestampMs = t, ax = 0.0, ay = 0.0, az = 9.81, gx = 0.0, gy = 0.0, gz = 0.0))
                t += 20
            }
        }
        return samples
    }

    @Test
    fun `refine returns null for too few samples`() {
        val short = setWith(reps = 1).take(10)
        assertNull(CalibrationRefiner.refine(short, correctedReps = 1, profile = profile()))
    }

    @Test
    fun `refine returns null for zero corrected reps`() {
        assertNull(CalibrationRefiner.refine(setWith(5), correctedReps = 0, profile = profile()))
    }

    @Test
    fun `refine lernt nicht wenn weniger Peaks als bestaetigt`() {
        // P0-Fix: 3 Zyklen im Puffer, aber 10 bestaetigte Reps - der Refiner
        // darf das Profil NICHT veraendern.
        val p = profile()
        assertNull(
            "weniger Peaks als bestaetigte Reps duerfen das Profil nicht veraendern",
            CalibrationRefiner.refine(setWith(reps = 3), correctedReps = 10, profile = p),
        )
    }

    @Test
    fun `refine lernt bei exakt passender Peak-Anzahl`() {
        val p = profile(prominence = 200.0, durationMs = 1_000.0)
        val improved = CalibrationRefiner.refine(setWith(reps = 5, cycleSamples = 50), correctedReps = 5, profile = p)
        assertNotNull(improved)
        // Die gemessenen Peaks (~240) heben die erwartete Prominenz an.
        assertTrue(improved!!.expectedProminence > p.expectedProminence)
    }

    @Test
    fun `refine passt Dauer an die korrigierten Abstaende an`() {
        val p = profile(prominence = 240.0, durationMs = 1_300.0)
        // Zyklus 80 Samples -> Intervall 1600 ms.
        val improved =
            CalibrationRefiner.refine(
                setWith(reps = 5, cycleSamples = 80),
                correctedReps = 5,
                profile = p,
            )
        assertNotNull(improved)
        assertTrue(improved!!.expectedDurationMs > p.expectedDurationMs)
    }

    @Test
    fun `refine haelt Achse Bias und IDs unveraendert`() {
        val p = profile()
        val improved = CalibrationRefiner.refine(setWith(5), correctedReps = 5, profile = p)
        assertNotNull(improved)
        assertEquals(p.rotationAxis, improved!!.rotationAxis)
        assertEquals(p.gyroBias, improved.gyroBias)
        assertEquals(p.exerciseId, improved.exerciseId)
        assertEquals(p.deviceId, improved.deviceId)
        assertEquals(p.detectionThreshold, improved.detectionThreshold, 1e-9)
    }

    // --- Umbauplan Phase 7.4: Kandidaten statt direktem Ueberschreiben ----

    @Test
    fun `refine liefert einen CANDIDATE mit revision+1 und parentRevision`() {
        val p = profile().copy(revision = 4, parentRevision = 3)
        val improved = CalibrationRefiner.refine(setWith(5), correctedReps = 5, profile = p)
        assertNotNull(improved)
        assertEquals(ProfileStatus.CANDIDATE, improved!!.status)
        assertEquals(5, improved.revision)
        assertEquals(4, improved.parentRevision)
        assertEquals(0, improved.validatedSetCount)
    }

    @Test
    fun `refine Kandidat uebernimmt Kalibrierungswerte unveraendert`() {
        val p = profile()
        val improved = CalibrationRefiner.refine(setWith(5), correctedReps = 5, profile = p)
        assertNotNull(improved)
        assertEquals(
            "Threshold/Achse/Bias bleiben Kalibrierungs-Erbe",
            p.detectionThreshold,
            improved!!.detectionThreshold,
            1e-9,
        )
        assertEquals(p.rotationAxis, improved.rotationAxis)
        assertEquals(p.gyroBias, improved.gyroBias)
    }
}
