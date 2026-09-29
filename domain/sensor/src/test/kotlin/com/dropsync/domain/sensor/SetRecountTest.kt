package com.dropsync.domain.sensor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Nachzaehlung ([SetRecount]) gegen synthetische Saetze. Kernanforderung: die Analyse darf
 * NIEMALS eine falsche Korrektur vorschlagen, wo die Live-Zaehlung stimmt - lieber keine
 * Aussage (Confidence LOW) als eine falsche.
 */
class SetRecountTest {
    private val axis = listOf(1.0, 0.0, 0.0)
    private val bias = listOf(1.0, 0.0, 0.0)

    private fun live(
        t: SyntheticTrace,
        thresholdFactor: Double = 0.46,
        gapInterpolateMaxMs: Long = 500L,
    ): Int {
        val e =
            ExerciseEnginePipeline(
                ExerciseEngineConfig(
                    rotationAxis = axis,
                    gyroBias = bias,
                    detectionThreshold = 70.0 * thresholdFactor,
                    expectedDurationMs = 2_400.0,
                    expectedProminence = 70.0,
                    hasValidCalibration = true,
                    gapInterpolateMaxMs = gapInterpolateMaxMs,
                ),
            )
        for (i in t.ts.indices) e.processSample(t.ts[i], t.gx[i], 0.0, 0.0, 0.0, 0.0, t.az[i])
        return e.repCount.value
    }

    private fun recount(t: SyntheticTrace, liveCount: Int) =
        SetRecount.count(t.toSamples(), axis, bias, liveCount)

    @Test
    fun `sauberer Satz - Nachzaehlung trifft die Wahrheit, stimmt mit live ueberein, kein Vorschlag`() {
        for (seed in 1L..10L) {
            val t = trace(seed)
            val liveCount = live(t)
            val r = recount(t, liveCount)!!
            assertEquals("Seed $seed", t.truth, r.count)
            assertEquals(SetRecount.Confidence.HIGH, r.confidence)
            assertTrue(r.agrees)
            assertFalse(r.isSuggestion)
            assertTrue("Periode ~2,4 s", r.periodMs!! in 2_000.0..2_900.0)
        }
    }

    @Test
    fun `veraltetes Profil - live verpasst Reps, Nachzaehlung trifft und schlaegt die Korrektur vor`() {
        var suggestions = 0
        for (seed in 1L..10L) {
            val t = trace(seed)
            val liveCount = live(t, thresholdFactor = 0.85)
            val r = recount(t, liveCount)!!
            if (liveCount != t.truth) {
                assertTrue("Seed $seed: Korrektur muss angeboten werden", r.isSuggestion)
                assertEquals("Seed $seed: Vorschlag muss stimmen", t.truth, r.count)
                suggestions++
            }
        }
        assertTrue("Der Test ist nur aussagekraeftig, wenn live oft daneben liegt", suggestions >= 5)
    }

    @Test
    fun `BLE-Luecken ohne Live-Ueberbrueckung - Nachzaehlung ueberbrueckt`() {
        var suggestions = 0
        for (seed in 1L..10L) {
            val t = withHole(withHole(trace(seed), 6_000L, 6_360L), 15_000L, 15_360L)
            val liveCount = live(t, gapInterpolateMaxMs = 0L)
            val r = recount(t, liveCount)!!
            assertEquals("Seed $seed", t.truth, r.count)
            if (liveCount != t.truth) {
                assertTrue(r.isSuggestion)
                suggestions++
            }
        }
        assertTrue(suggestions >= 3)
    }

    @Test
    fun `unueberbrueckte Luecke ueber 600 ms macht die Analyse unsicher`() {
        val t = withHole(trace(seed = 4), 9_000L, 10_000L)
        val r = recount(t, live(t))!!
        assertEquals(SetRecount.Confidence.LOW, r.confidence)
        assertTrue(r.hasUnbridgedGap)
        assertFalse("bei LOW nie ein Vorschlag", r.isSuggestion)
    }

    @Test
    fun `Stoerungen erzeugen nie eine falsche Korrektur`() {
        for (seed in 1L..20L) {
            // Zappeln vor dem Satz (Sample 5..40 = 0,1..0,8 s) und zwei schwache Teilreps am Ende
            val base = trace(seed)
            val n = base.ts.size
            val fidget = withPulse(base, start = 5, lenSamples = 32, amplitude = 56.0)
            val partial = withPulse(withPulse(base, n - 95, 45, 25.0), n - 50, 45, 25.0)
            for ((name, t) in listOf("Zappeln" to fidget, "Teilreps" to partial)) {
                val liveCount = live(t)
                val r = recount(t, liveCount)!!
                if (r.isSuggestion) {
                    assertEquals("$name, Seed $seed: Vorschlag muss der Wahrheit entsprechen", t.truth, r.count)
                }
            }
        }
    }

    @Test
    fun `Cluster-Satz mit langer Pause - beide Cluster werden gezaehlt`() {
        val t = concat(trace(seed = 1, reps = 5), trace(seed = 2, reps = 5), pauseMs = 6_000L)
        val r = recount(t, liveCount = 10)!!
        assertEquals(10, r.count)
    }

    @Test
    fun `zu wenig Signal und keine Bewegung liefern null`() {
        val short = trace(seed = 1, reps = 1).toSamples().take(100)
        assertNull(SetRecount.count(short, axis, bias, 0))
        val still = (0 until 400).map { SensorSample(it * 20L, 0.0, 0.0, 1.0, 1.0, 0.0, 0.0) }
        assertNull(SetRecount.count(still, axis, bias, 0))
    }

    @Test
    fun `ungueltige Achse liefert null`() {
        val t = trace(seed = 1)
        assertNull(SetRecount.count(t.toSamples(), listOf(0.0, 0.0, 0.0), bias, 10))
        assertNull(SetRecount.count(t.toSamples(), listOf(1.0), bias, 10))
    }
}
