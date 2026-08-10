package com.dropsync.domain.sensor.calibration

import com.dropsync.domain.sensor.CalibrationProfile
import com.dropsync.domain.sensor.SensorSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sin

/**
 * Learn-loop contract (Fusion Phase 4): a corrected rep count re-analyses the
 * buffered set and nudges the stored profile towards the new evidence.
 */
class CalibrationRefinerTest {
    private val axisZ = listOf(0.0, 0.0, 1.0)
    private val zeroBias = listOf(0.0, 0.0, 0.0)

    private fun profile() =
        CalibrationProfile(
            exerciseId = 1L,
            deviceId = "dev",
            rotationAxis = axisZ,
            gyroBias = zeroBias,
            repTemplate = List(64) { 0.0 },
            signalPeakLevel = 100.0,
            noisePeakLevel = 10.0,
            expectedProminence = 40.0,
            expectedDurationSamples = 50.0,
            qualityScore = 0.9,
        )

    /** Synthetic set: `reps` gyro bursts on the Z axis at 50 Hz. */
    private fun setWith(
        reps: Int,
        repDurationSamples: Int = 50,
    ): List<SensorSample> {
        val samples = mutableListOf<SensorSample>()
        val total = reps * repDurationSamples + repDurationSamples
        var t = 0L
        for (i in 0 until total) {
            val phase = (i % repDurationSamples).toDouble() / repDurationSamples
            // One rep = one burst of rotation on Z, rest is still.
            val gz =
                if (i >= repDurationSamples / 2 && phase < 0.8) {
                    120.0 * sin(phase * Math.PI)
                } else {
                    0.0
                }
            samples.add(SensorSample(timestampMs = t, ax = 0.0, ay = 0.0, az = 9.81, gx = 0.0, gy = 0.0, gz = gz))
            t += 20 // 50 Hz
        }
        return samples
    }

    @Test
    fun `refine returns null for too few samples`() {
        val short = setWith(reps = 1, repDurationSamples = 5) // 10 samples < 50
        assertNull(CalibrationRefiner.refine(short, correctedReps = 1, profile = profile()))
    }

    @Test
    fun `refine returns null for zero corrected reps`() {
        assertNull(CalibrationRefiner.refine(setWith(5), correctedReps = 0, profile = profile()))
    }

    @Test
    fun `refine nudges prominence towards the corrected peaks`() {
        val p = profile() // expectedProminence = 40
        val improved = CalibrationRefiner.refine(setWith(reps = 6), correctedReps = 6, profile = p)
        assertTrue(improved != null)
        // Synthetic peaks are taller than the stored 40 -> value rises (blended).
        assertTrue(improved!!.expectedProminence > p.expectedProminence)
    }

    @Test
    fun `refine nudges duration towards the corrected spacing`() {
        // Set has 8 peaks spread over a long window -> wider spacing than 50.
        val p = profile()
        val improved =
            CalibrationRefiner.refine(
                setWith(reps = 8, repDurationSamples = 80),
                correctedReps = 8,
                profile = p,
            )
        assertTrue(improved != null)
        assertNotEquals(p.expectedDurationSamples, improved!!.expectedDurationSamples)
    }

    @Test
    fun `refine keeps rotation axis and bias unchanged`() {
        val p = profile()
        val improved = CalibrationRefiner.refine(setWith(5), correctedReps = 5, profile = p)!!
        assertEquals(p.rotationAxis, improved.rotationAxis)
        assertEquals(p.gyroBias, improved.gyroBias)
        assertEquals(p.exerciseId, improved.exerciseId)
        assertEquals(p.deviceId, improved.deviceId)
    }

    @Test
    fun `refine adapts duration to the corrected count`() {
        // Fewer reps over the same window -> wider spacing; more reps -> tighter.
        val wide =
            CalibrationRefiner.refine(
                setWith(reps = 4, repDurationSamples = 100),
                correctedReps = 4,
                profile = profile(),
            )!!
        val tight =
            CalibrationRefiner.refine(
                setWith(reps = 10, repDurationSamples = 40),
                correctedReps = 10,
                profile = profile(),
            )!!
        assertTrue(tight.expectedDurationSamples < wide.expectedDurationSamples)
    }
}
