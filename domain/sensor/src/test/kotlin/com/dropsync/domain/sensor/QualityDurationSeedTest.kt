package com.dropsync.domain.sensor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * B2 (RC-19, Stufe 1): [ExerciseEngineConfig.qualityDurationMs] seedet NUR
 * die Qualitaets-Erwartung des Motors (z. B. die am Set-Ende gemessene
 * Rep-Periode des Vorgaenger-Satzes). Die Refraktaerzeit des PeakDetectors
 * — und damit der Pending-Deckel — bleibt am Profilwert (Entscheidung 5.14:
 * "nur die Qualitaetsbewertung").
 */
class QualityDurationSeedTest {
    private fun pipeline(qualityDurationMs: Double?): ExerciseEnginePipeline =
        ExerciseEnginePipeline(
            ExerciseEngineConfig(
                rotationAxis = listOf(1.0, 0.0, 0.0),
                gyroBias = listOf(0.0, 0.0, 0.0),
                expectedProminence = 60.0,
                expectedDurationMs = 1_000.0,
                qualityDurationMs = qualityDurationMs,
                detectionThreshold = 15.0,
                hasValidCalibration = true,
            ),
        )

    /** Zwei-Phasen-Zyklus, 60 Samples = 1.2 s bei 50 Hz. */
    private fun cycle(amplitude: Double = 60.0): List<Double> =
        (1..15).map { amplitude * it / 15.0 } +
            (1..30).map { amplitude - 2.0 * amplitude * it / 30.0 } +
            (1..15).map { -amplitude + amplitude * it / 15.0 }

    /**
     * Spielt Settle + [reps] Zyklen mit [gapSamples] Ruhe dazwischen.
     * Der Abstand der Peaks ist (60 + gap) * 20 ms — bei gap=5 also 1.3 s:
     * eine mitgewanderte Refraktaerzeit (5 s * 0.3 = 1.5 s) wuerde den
     * zweiten Rep unterdruecken.
     */
    private fun feed(
        pipeline: ExerciseEnginePipeline,
        reps: Int = 2,
        gapSamples: Int = 5,
    ): List<EngineFrameResult> {
        val out = mutableListOf<EngineFrameResult>()
        var t = 0L
        (List(60) { 0.0 } + repeatToList(reps) { cycle() + List(gapSamples) { 0.0 } })
            .forEach { v ->
                out += pipeline.processSample(t, v, 0.0, 0.0)
                t += 20L
            }
        return out
    }

    private inline fun <T> repeatToList(
        times: Int,
        block: () -> List<T>,
    ): List<T> = buildList { repeat(times) { addAll(block()) } }

    @Test
    fun `qualityDurationMs seedet die Qualitaets-Erwartung ohne die Refraktaerzeit zu bewegen`() {
        val withoutSeed = pipeline(qualityDurationMs = null)
        val withSeed = pipeline(qualityDurationMs = 7_000.0)
        assertEquals(1_000.0, withoutSeed.qualityExpectedDurationMs, 1e-9)
        assertEquals(7_000.0, withSeed.qualityExpectedDurationMs, 1e-9)

        val withoutEvents = feed(withoutSeed).filter { it.repResult.repCounted }
        val withEvents = feed(withSeed).filter { it.repResult.repCounted }

        assertEquals("ohne Seed zaehlen beide Reps", 2, withoutEvents.size)
        assertEquals("mit Seed zaehlt nur der erste Rep", 1, withEvents.size)
        val withoutScore = withoutEvents.first().repResult.qualityScore ?: 0.0
        val withScore = withEvents.first().repResult.qualityScore ?: 0.0
        assertTrue(
            "die Qualitaets-Erwartung muss aus dem Seed kommen (ohne=$withoutScore mit=$withScore)",
            withScore < withoutScore,
        )
        assertTrue("der erste Rep muss mit Seed noch akzeptiert bleiben", withScore > 0.55)

        // Refraktaerzeit bleibt am Profilwert: der zweite Peak FEUERT und
        // wird von der geseedeten Qualitaets-Erwartung abgelehnt. Waere die
        // Refraktaerzeit mitgewandert (7 s * 0.3 = 2.1 s > 1.3 s Abstand),
        // gaebe es gar kein zweites Peak-Ereignis.
        assertEquals(
            "zweiter Peak muss als QUALITY abgelehnt werden (Beweis: Detektor feuerte)",
            1,
            withSeed.rejectionCountsSnapshot[RepRejectionReason.QUALITY] ?: 0,
        )
    }
}
