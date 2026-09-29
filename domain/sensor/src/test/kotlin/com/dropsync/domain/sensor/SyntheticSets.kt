package com.dropsync.domain.sensor

import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Deterministische synthetische Saetze fuer die Zaehl-Tests: Sinus-Lappen fuer die
 * konzentrische und die exzentrische Phase, Rauschen, Tempo- und Amplitudenjitter,
 * Ermuedung. Ersetzt KEINE echten Aufnahmen (Gate 11b).
 */
internal class SyntheticTrace(val ts: LongArray, val gx: DoubleArray, val az: DoubleArray, val truth: Int) {
    fun toSamples(): List<SensorSample> =
        ts.indices.map { SensorSample(ts[it], 0.0, 0.0, az[it], gx[it], 0.0, 0.0) }
}

internal fun trace(
    seed: Long,
    reps: Int = 10,
    tempoS: Double = 2.4,
    holdS: Double = 0.0,
    amp: Double = 70.0,
    ampJitter: Double = 0.08,
): SyntheticTrace {
    val rnd = Random(seed)
    val fs = 50.0
    val g = ArrayList<Double>()
    repeat((1.5 * fs).toInt()) { g.add(0.0) }
    for (i in 0 until reps) {
        val t = tempoS * (1 + 0.06 * rnd.nextGaussian().coerceIn(-2.0, 2.0))
        val a = amp * (1 - 0.01 * i) * (1 + ampJitter * rnd.nextGaussian().coerceIn(-2.0, 2.0))
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
    return SyntheticTrace(
        ts = LongArray(n) { (it * 1000.0 / fs).toLong() },
        gx = DoubleArray(n) { g[it] + 0.8 * rnd.nextGaussian() + 1.0 },
        az = DoubleArray(n) { 1.0 + 0.004 * abs(g[it]) + 0.005 * rnd.nextGaussian() },
        truth = reps,
    )
}

/** Entfernt alle Samples mit Zeitstempel in [fromMs, toMs) - simuliert einen BLE-Ausfall. */
internal fun withHole(t: SyntheticTrace, fromMs: Long, toMs: Long): SyntheticTrace {
    val keep = t.ts.indices.filter { t.ts[it] < fromMs || t.ts[it] >= toMs }
    return SyntheticTrace(
        LongArray(keep.size) { t.ts[keep[it]] },
        DoubleArray(keep.size) { t.gx[keep[it]] },
        DoubleArray(keep.size) { t.az[keep[it]] },
        t.truth,
    )
}

/** Addiert einen Sinus-Puls (eine volle Periode) der Laenge [lenSamples] ab Sample [start]. */
internal fun withPulse(t: SyntheticTrace, start: Int, lenSamples: Int, amplitude: Double): SyntheticTrace {
    val gx = t.gx.copyOf()
    for (k in 0 until lenSamples) if (start + k < gx.size) gx[start + k] += amplitude * sin(2 * PI * k / lenSamples)
    return SyntheticTrace(t.ts, gx, t.az, t.truth)
}

/** Haengt [second] nach [pauseMs] Ruhe an [first] an (Cluster-Satz). Wahrheit = Summe. */
internal fun concat(first: SyntheticTrace, second: SyntheticTrace, pauseMs: Long): SyntheticTrace {
    val offset = first.ts.last() + pauseMs
    return SyntheticTrace(
        LongArray(first.ts.size + second.ts.size) { if (it < first.ts.size) first.ts[it] else second.ts[it - first.ts.size] + offset },
        first.gx + second.gx,
        first.az + second.az,
        first.truth + second.truth,
    )
}
