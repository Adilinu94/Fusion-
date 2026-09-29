package com.dropsync.domain.sensor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Regressionstests fuer die Zaehlrobustheit der Rep-Pipeline (Bericht 2026-09-28):
 *
 * 1. Langsame, glatte Wiederholungen (> ca. 2 s) wurden komplett als "Negative Phase
 *    zu kurz" abgelehnt, weil das Pending-Fenster schon beim ersten negativen Sample
 *    schloss.
 * 2. Halte-Phasen von Pause-Reps (>= 400 ms Ruhe) verwarfen den offenen Pending-Rep.
 * 3. Luecken >= 250 ms verwarfen die laufende Rep und sperrten das Zaehlen ~1 s.
 *
 * Die Signale sind synthetisch (deterministisch je Seed): Sinus-Lappen fuer die
 * konzentrische und die exzentrische Phase, Rauschen, Tempo- und Amplitudenjitter,
 * Ermuedung. Sie ersetzen KEINE echten Aufnahmen (Gate 11b), sichern aber ab, dass
 * die genannten Fehlerklassen nicht zurueckkehren.
 */
class CountingRobustnessTest {
    private class Trace(val ts: LongArray, val gx: DoubleArray, val az: DoubleArray, val truth: Int)

    private fun trace(seed: Long, reps: Int = 10, tempoS: Double = 2.4, holdS: Double = 0.0, amp: Double = 70.0): Trace {
        val rnd = Random(seed)
        val fs = 50.0
        val g = ArrayList<Double>()
        repeat((1.5 * fs).toInt()) { g.add(0.0) }
        for (i in 0 until reps) {
            val t = tempoS * (1 + 0.06 * rnd.nextGaussian().coerceIn(-2.0, 2.0))
            val a = amp * (1 - 0.01 * i) * (1 + 0.08 * rnd.nextGaussian().coerceIn(-2.0, 2.0))
            val n1 = (t * 0.45 * fs).roundToInt().coerceAtLeast(6)
            val n2 = (t * 0.55 * fs).roundToInt().coerceAtLeast(6)
            for (k in 0 until n1) g.add(a * sin(PI * (k + 0.5) / n1))
            repeat((holdS * fs).toInt()) { g.add(0.0) }
            val eccentric = a * n1.toDouble() / n2
            for (k in 0 until n2) g.add(-eccentric * sin(PI * (k + 0.5) / n2))
            repeat((rnd.nextDouble() * 0.15 * fs).toInt()) { g.add(0.0) }
        }
        repeat((2.0 * fs).toInt()) { g.add(0.0) }
        val n = g.size
        return Trace(
            ts = LongArray(n) { (it * 1000.0 / fs).toLong() },
            gx = DoubleArray(n) { g[it] + 0.8 * rnd.nextGaussian() + 1.0 },
            az = DoubleArray(n) { 1.0 + 0.004 * abs(g[it]) + 0.005 * rnd.nextGaussian() },
            truth = reps,
        )
    }

    /** Entfernt alle Samples mit Zeitstempel in [fromMs, toMs) - simuliert einen BLE-Ausfall. */
    private fun withHole(t: Trace, fromMs: Long, toMs: Long): Trace {
        val keep = t.ts.indices.filter { t.ts[it] < fromMs || t.ts[it] >= toMs }
        return Trace(
            LongArray(keep.size) { t.ts[keep[it]] },
            DoubleArray(keep.size) { t.gx[keep[it]] },
            DoubleArray(keep.size) { t.az[keep[it]] },
            t.truth,
        )
    }

    private fun engine(
        tempoS: Double = 2.4,
        holdS: Double = 0.0,
        zuptAbortQuietFraction: Double = 0.5,
        gapInterpolateMaxMs: Long = 500L,
    ) = ExerciseEnginePipeline(
        ExerciseEngineConfig(
            rotationAxis = listOf(1.0, 0.0, 0.0),
            gyroBias = listOf(1.0, 0.0, 0.0),
            detectionThreshold = 32.0,
            expectedDurationMs = (tempoS + holdS) * 1000.0,
            expectedProminence = 70.0,
            hasValidCalibration = true,
            zuptAbortQuietFraction = zuptAbortQuietFraction,
            gapInterpolateMaxMs = gapInterpolateMaxMs,
        ),
    )

    private fun feed(e: ExerciseEnginePipeline, t: Trace) {
        for (i in t.ts.indices) e.processSample(t.ts[i], t.gx[i], 0.0, 0.0, 0.0, 0.0, t.az[i])
    }

    // --- 1. Langsames Tempo -------------------------------------------------

    @Test
    fun `langsame glatte Wiederholungen werden exakt gezaehlt`() {
        for (tempo in listOf(2.0, 2.4, 3.0, 4.0)) {
            for (seed in 1L..8L) {
                val t = trace(seed, tempoS = tempo)
                val e = engine(tempoS = tempo)
                feed(e, t)
                assertEquals("Tempo $tempo s, Seed $seed", t.truth, e.repCount.value)
            }
        }
    }

    // --- 2. Pause-Reps ------------------------------------------------------

    @Test
    fun `Pause-Reps mit einer Sekunde Halte-Phase bleiben erhalten`() {
        for (seed in 1L..8L) {
            val t = trace(seed, holdS = 1.0)
            val e = engine(holdS = 1.0)
            feed(e, t)
            assertEquals("Seed $seed", t.truth, e.repCount.value)
            assertEquals("kein ZUPT-Verwurf in der Halte-Phase", 0, e.zuptAbortedPending)
        }
    }

    @Test
    fun `mit zuptAbortQuietFraction 0 gilt das alte Verhalten - Halte-Phase verwirft die Rep`() {
        var lost = 0
        for (seed in 1L..8L) {
            val t = trace(seed, holdS = 1.0)
            val e = engine(holdS = 1.0, zuptAbortQuietFraction = 0.0)
            feed(e, t)
            lost += t.truth - e.repCount.value
        }
        assertTrue("Das alte Verhalten muss Pause-Reps verlieren (Regressionsanker)", lost > 0)
    }

    @Test
    fun `echte lange Ruhe verwirft einen offenen Pending weiterhin`() {
        // Halbe Rep (nur konzentrisch), danach 4 s Ruhe: laenger als 0,5 x 2,4 s.
        val e = engine()
        var ts = 0L
        fun push(gx: Double) {
            e.processSample(ts, gx, 0.0, 0.0, 0.0, 0.0, 1.0)
            ts += 20L
        }
        repeat(100) { push(1.0) }
        (0 until 54).forEach { push(1.0 + 70.0 * sin(PI * (it + 0.5) / 54)) }
        repeat(200) { push(1.0) }
        assertEquals(0, e.repCount.value)
        assertEquals(1, e.zuptAbortedPending)
    }

    // --- 3. Luecken ---------------------------------------------------------

    @Test
    fun `Luecken von 300 bis 480 ms werden ueberbrueckt und kosten keine Rep`() {
        for (holeMs in listOf(300L, 400L, 480L)) {
            for (from in 3_000L..24_000L step 1_700L) {
                val t = withHole(trace(seed = from, reps = 10), from, from + holeMs)
                val e = engine()
                feed(e, t)
                assertEquals("Loch $holeMs ms ab $from ms", t.truth, e.repCount.value)
                assertEquals(1, e.largeGapCount)
                assertTrue(e.interpolatedSamples > 0)
            }
        }
    }

    @Test
    fun `mit gapInterpolateMaxMs 0 wird nichts eingefuegt`() {
        val t = withHole(trace(seed = 7), 6_000L, 6_300L)
        val e = engine(gapInterpolateMaxMs = 0L)
        feed(e, t)
        assertEquals(1, e.largeGapCount)
        assertEquals(0, e.interpolatedSamples)
    }

    @Test
    fun `Luecke ueber dem Limit verwirft wie bisher und schwingt nach wenigen Samples ein`() {
        val e = engine()
        var ts = 0L
        repeat(120) {
            e.processSample(ts, 1.0, 0.0, 0.0, 0.0, 0.0, 1.0)
            ts += 20L
        }
        assertTrue(e.isSettled)
        ts += 1_200L
        e.processSample(ts, 1.0, 0.0, 0.0, 0.0, 0.0, 1.0)
        assertEquals(1, e.largeGapCount)
        assertEquals(0, e.interpolatedSamples)
        assertFalse("direkt nach der Luecke noch nicht eingeschwungen", e.isSettled)
        repeat(SignalChain.DEFAULT_GAP_SETTLE_SAMPLES) {
            ts += 20L
            e.processSample(ts, 1.0, 0.0, 0.0, 0.0, 0.0, 1.0)
        }
        assertTrue("nach ${SignalChain.DEFAULT_GAP_SETTLE_SAMPLES} Samples wieder bereit", e.isSettled)
    }

    @Test
    fun `Interpolation erzeugt keine zusaetzlichen Reps in der Ruhe`() {
        // Luecke mitten in der Ruhe zwischen Vorlauf und erster Rep: Zaehlung bleibt exakt.
        val t = withHole(trace(seed = 3), 400L, 800L)
        val e = engine()
        feed(e, t)
        assertEquals(t.truth, e.repCount.value)
    }
}
