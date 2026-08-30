package com.dropsync.domain.sensor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P2-Fix #21: die Pipeline darf die Abtastrate nicht annehmen, sondern muss
 * sie messen. Geprueft wird der Estimator isoliert und seine Wirkung in der
 * [ExerciseEnginePipeline].
 */
class SampleRateEstimatorTest {
    @Test
    fun `ohne genug abstaende gilt die nominale rate`() {
        val estimator = SampleRateEstimator()
        // 5 Abstaende, MIN_DELTAS_FOR_ESTIMATE ist 20.
        for (i in 0..5) estimator.onSample(i * 20L)
        assertEquals(SampleRateEstimator.NOMINAL_RATE_HZ, estimator.estimatedRateHz, 1e-9)
        assertEquals(false, estimator.isConfident)
    }

    @Test
    fun `konstanter takt wird exakt geschaetzt`() {
        val estimator = SampleRateEstimator()
        // 33 ms Abstand entspricht ~30.3 Hz - der Fall, den die fest
        // verdrahteten 50 Hz falsch behandelt haben.
        for (i in 0..60) estimator.onSample(i * 33L)
        assertTrue(estimator.isConfident)
        assertEquals(1_000.0 / 33.0, estimator.estimatedRateHz, 0.01)
    }

    @Test
    fun `einzelne grosse luecke verzerrt die schaetzung nicht`() {
        val estimator = SampleRateEstimator()
        var t = 0L
        // 40 saubere 20-ms-Abstaende.
        for (i in 0..40) {
            estimator.onSample(t)
            t += 20L
        }
        val before = estimator.estimatedRateHz

        // Paketverlust: 300 ms Luecke. Ein Mittelwert wuerde hier massiv
        // einbrechen; der Median darf sich praktisch nicht bewegen, und der
        // Ausreisser wird ohnehin als Luecke verworfen.
        t += 300L
        estimator.onSample(t)

        assertEquals("Luecke darf die Rate nicht druecken", before, estimator.estimatedRateHz, 0.5)
        assertEquals(50.0, estimator.estimatedRateHz, 0.5)
    }

    @Test
    fun `rate wird auf einen physikalisch sinnvollen bereich begrenzt`() {
        val estimator = SampleRateEstimator()
        // 1-ms-Abstaende entsprechen 1000 Hz - kein von uns unterstuetzter
        // Sensor liefert das; die Schaetzung muss deckeln.
        for (i in 0..40) estimator.onSample(i * 1L)
        assertEquals(SampleRateEstimator.MAX_RATE_HZ, estimator.estimatedRateHz, 1e-9)
    }

    @Test
    fun `reset verwirft die historie`() {
        val estimator = SampleRateEstimator()
        for (i in 0..40) estimator.onSample(i * 33L)
        assertTrue(estimator.isConfident)

        estimator.reset()

        assertEquals(false, estimator.isConfident)
        assertEquals(SampleRateEstimator.NOMINAL_RATE_HZ, estimator.estimatedRateHz, 1e-9)
    }

    @Test
    fun `rueckwaerts laufende timestamps werden ignoriert`() {
        val estimator = SampleRateEstimator()
        for (i in 0..40) estimator.onSample(i * 20L)
        val before = estimator.estimatedRateHz

        // Firmware-Ueberlauf oder vertauschte Reihenfolge: negativer Abstand.
        estimator.onSample(0L)

        assertEquals(before, estimator.estimatedRateHz, 1e-9)
    }

    @Test
    fun `pipeline uebernimmt die gemessene rate`() {
        val pipeline =
            ExerciseEnginePipeline(
                ExerciseEngineConfig(
                    rotationAxis = listOf(1.0, 0.0, 0.0),
                    gyroBias = listOf(0.0, 0.0, 0.0),
                    detectionThreshold = 32.5,
                ),
            )

        // Ruhiger Strom mit 33-ms-Takt (~30 Hz) statt der nominalen 50 Hz.
        for (i in 0..80) pipeline.processSample(i * 33L, 0.0, 0.0, 0.0, 0.0, 0.0, 1.0)

        assertEquals(
            "die Pipeline muss die echte Rate sehen, nicht die nominale",
            1_000.0 / 33.0,
            pipeline.estimatedSampleRateHz,
            0.5,
        )
    }
}
