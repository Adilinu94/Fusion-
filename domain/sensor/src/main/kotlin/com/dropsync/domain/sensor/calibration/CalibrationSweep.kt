package com.dropsync.domain.sensor.calibration

import com.dropsync.domain.sensor.SensorSample
import com.dropsync.domain.sensor.accelMagnitude
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Signalanalyse der gefuehrten Kalibrierung (Umbauplan Phase 11, LargeClass):
 * Ruhe-Analyse, Achsen-PCA, Kandidatensignale und der bekannte-Zahl-Sweep
 * sind reine Funktionen ohne Wizard-Zustand — aus [CalibrationController]
 * herausgezogen, damit die Klasse wieder unter die Detekt-Grenze faellt.
 *
 * Alle Funktionen nehmen [sampleRateHz] explizit entgegen (vorher implizites
 * Member des Controllers).
 */

internal data class RestStats(
    val n: Int,
    val baseline: Double,
    val sigmaAccel: Double,
    val gyroBias: DoubleArray,
    val sigmaGyro: Double,
    val gyroMagMean: Double,
    val gateOk: Boolean,
)

internal data class AxisResult(
    val achse: DoubleArray,
    val t0: Double,
    val gyroPeak: Double,
    val varianceShare: Double,
)

internal data class SweepCfg(
    val signal: ChosenSignal,
    var theta: Double,
    val refractoryS: Double,
    val prominenz: Double,
    val cv: Double,
    val margin: Double,
    val peakHoehen: List<Double>,
)

internal data class Quad(
    val theta: Double,
    val k: Double,
    val med: Double,
    val mad: Double,
)

internal data class StufeCResult(
    val theta: Double,
    val ok: Boolean,
    val angepasst: Boolean,
)

// --- Stage 0: rest analysis (Referenz stufe0_ruheanalyse) -------------------

internal fun restStats(
    buf: List<SensorSample>,
    sampleRateHz: Double,
): RestStats {
    val n = buf.size
    if (n == 0) {
        return RestStats(
            0,
            0.0,
            Double.POSITIVE_INFINITY,
            DoubleArray(3),
            Double.POSITIVE_INFINITY,
            Double.POSITIVE_INFINITY,
            false,
        )
    }
    // Trim start settle + end tap motion: first ~15% and last ~0.4 s.
    val dropEnd = min(n / 4, max(1, (0.4 * sampleRateHz).toInt()))
    val dropStart = min(n / 6, max(0, n - dropEnd - 10))
    val end = max(dropStart + 1, n - dropEnd)
    val window = buf.subList(dropStart, end)

    val accelMag = window.map { it.accelMagnitude }
    val bias =
        doubleArrayOf(
            mean(window.map { it.gx }),
            mean(window.map { it.gy }),
            mean(window.map { it.gz }),
        )
    val gyroMag =
        window.map { s ->
            val dx = s.gx - bias[0]
            val dy = s.gy - bias[1]
            val dz = s.gz - bias[2]
            sqrt(dx * dx + dy * dy + dz * dz)
        }
    val sigmaAccel = std(accelMag)
    val gyroMagMean = mean(gyroMag)
    val gateOk =
        gyroMagMean < CalibrationThresholds.REST_GYRO_MEAN_MAX_DEG_PER_SEC &&
            sigmaAccel < CalibrationThresholds.REST_ACCEL_SIGMA_MAX_G
    return RestStats(
        n = window.size,
        baseline = mean(accelMag),
        sigmaAccel = sigmaAccel,
        gyroBias = bias,
        sigmaGyro = std(gyroMag),
        gyroMagMean = gyroMagMean,
        gateOk = gateOk,
    )
}

// --- Stage A: axis analysis via 3x3 PCA (Referenz stufeA) -------------------

internal fun axisAnalysis(
    buf: List<SensorSample>,
    rest: RestStats,
    sampleRateHz: Double,
): AxisResult? {
    val bias = rest.gyroBias
    val gyroZ = buf.map { s -> doubleArrayOf(s.gx - bias[0], s.gy - bias[1], s.gz - bias[2]) }
    val gyroMag = gyroZ.map { r -> sqrt(r[0] * r[0] + r[1] * r[1] + r[2] * r[2]) }
    val schwelle = max(CalibrationThresholds.MIN_ACTIVITY_GYRO_DEG_PER_SEC, 4.0 * rest.sigmaGyro)
    val aktiv = gyroMag.indices.filter { gyroMag[it] > schwelle }
    if (aktiv.size < CalibrationThresholds.MIN_ACTIVITY_SAMPLES) return null
    val i0 = aktiv.first()
    val i1 = aktiv.last()
    val fenster = gyroZ.subList(i0, i1 + 1)

    val (axis, varianceShare) = principalAxis(fenster) ?: return null
    val projected = fenster.map { r -> r[0] * axis[0] + r[1] * axis[1] + r[2] * axis[2] }
    return AxisResult(
        achse = axis,
        t0 = (i1 - i0) / sampleRateHz,
        gyroPeak = projected.max(),
        varianceShare = varianceShare,
    )
}

// --- Stage B: candidate signals + known-count sweep -------------------------

internal fun candidateSignals(
    buf: List<SensorSample>,
    achse: DoubleArray,
    bias: DoubleArray,
): Map<ChosenSignal, DoubleArray> {
    val gyroWeight = 0.05 // same formula as SignalProcessor
    val n = buf.size
    val gP = DoubleArray(n)
    val gyroMag = DoubleArray(n)
    val combinedRaw = DoubleArray(n)
    for (i in 0 until n) {
        val s = buf[i]
        val dx = s.gx - bias[0]
        val dy = s.gy - bias[1]
        val dz = s.gz - bias[2]
        gP[i] = dx * achse[0] + dy * achse[1] + dz * achse[2]
        gyroMag[i] = sqrt(dx * dx + dy * dy + dz * dz)
        combinedRaw[i] = s.accelMagnitude + gyroWeight * gyroMag[i]
    }
    return mapOf(
        ChosenSignal.GP to gP,
        ChosenSignal.COMBINED to ema(combinedRaw, 0.6),
        ChosenSignal.GYRO_MAG to gyroMag,
    )
}

/** Baseline/sigma per candidate signal from the rest edges (1 s each side). */
internal fun signalMeta(
    signals: Map<ChosenSignal, DoubleArray>,
    sampleRateHz: Double,
): Map<ChosenSignal, Pair<Double, Double>> {
    val nRest = sampleRateHz.toInt()
    return signals.mapValues { (_, sig) ->
        val restSamples =
            (sig.take(min(nRest, sig.size)) + sig.takeLast(min(nRest, sig.size)))
        median(restSamples) to std(restSamples)
    }
}

internal fun knownCountSweep(
    signals: Map<ChosenSignal, DoubleArray>,
    meta: Map<ChosenSignal, Pair<Double, Double>>,
    t0: Double,
    nSoll: Int,
    sampleRateHz: Double,
): SweepCfg? {
    var beste: SweepCfg? = null
    // Umbauplan Phase 1.3: bis GYRO_MAG/COMBINED mit echten
    // Hardware-Traces verifiziert sind, wird der Sweep ausschliesslich
    // auf dem signierten GP-Signal gefahren - die Live-Pipeline
    // verarbeitet naemlich immer GP.
    val names = listOf(ChosenSignal.GP)
    for (name in names) {
        val sig = signals[name] ?: continue
        beste = sweepBestForSignal(sig, meta[name]!!, t0, nSoll, sampleRateHz) ?: beste
    }
    return beste
}

/**
 * Sweep fuer EIN Signal: Spanne pruefen, Vorlaeufigkeit zaehlen, dann alle
 * (theta, prominenz)-Kandidaten durchprobieren und die beste gueltige
 * Konfiguration zurueckgeben (null = Signal unbrauchbar).
 */
private fun sweepBestForSignal(
    sig: DoubleArray,
    meta: Pair<Double, Double>,
    t0: Double,
    nSoll: Int,
    sampleRateHz: Double,
): SweepCfg? {
    val (baseline, sigma) = meta
    val span = percentile(sig.toList(), 99.0) - baseline
    if (span <= 0) return null
    val vorl = zaehleEdge(sig, sampleRateHz, baseline + 3 * sigma, 0.35 * t0, baseline)
    val prom = if (vorl.size >= 3) 0.2 * median(vorl.map { it.height }) else 0.0
    // Tempo probe: 3x stretched signal must still count nSoll.
    val sigLangsam = stretch3(sig)
    var beste: SweepCfg? = null
    for (frac in linspace(0.10, 1.00, 20)) {
        val theta = baseline + frac * span
        for (prominenz in listOf(0.0, prom)) {
            val kandidat =
                sweepKandidat(sig, sigLangsam, baseline, theta, prominenz, t0, nSoll, span, sigma, sampleRateHz)
                    ?: continue
            val current = beste
            if (current == null || isBetter(kandidat, current)) beste = kandidat
        }
    }
    return beste
}

/**
 * Prueft einen (theta, prominenz)-Kandidaten: Stabilitaet (Original- und
 * 3x-gestrecktes Signal zaehlen nSoll), dann Refraktor-Sweep. Liefert die
 * beste gueltige Konfiguration oder null, wenn der Kandidat durchfaellt.
 * Extraktion aus [knownCountSweep] (Detekt: LoopWithTooManyJumpStatements).
 */
private fun sweepKandidat(
    sig: DoubleArray,
    sigLangsam: DoubleArray,
    baseline: Double,
    theta: Double,
    prominenz: Double,
    t0: Double,
    nSoll: Int,
    span: Double,
    sigma: Double,
    sampleRateHz: Double,
): SweepCfg? {
    // Stability probe: shortest refractory must still count nSoll.
    if (zaehleEdge(sig, sampleRateHz, theta, 0.35 * t0, baseline, prominenz = prominenz).size != nSoll) {
        return null
    }
    if (zaehleEdge(sigLangsam, sampleRateHz, theta, 0.35 * t0, baseline, prominenz = prominenz).size != nSoll) {
        return null
    }
    var beste: SweepCfg? = null
    for (refrFaktor in linspace(0.35, 0.75, 5)) {
        val refr = refrFaktor * t0
        val cfg =
            refraktorKandidat(sig, sampleRateHz, theta, refr, baseline, prominenz, span, sigma, nSoll) ?: continue
        val current = beste
        if (current == null || isBetter(cfg, current)) beste = cfg
    }
    return beste
}

/**
 * Ein Refraktor-Kandidat: zaehlt die Reps bei [refr], verlangt genau nSoll
 * Treffer mit ausreichend hoher Median-Hoehe und berechnet daraus CV und
 * Margin. null = Kandidat ungueltig.
 */
private fun refraktorKandidat(
    sig: DoubleArray,
    sampleRateHz: Double,
    theta: Double,
    refr: Double,
    baseline: Double,
    prominenz: Double,
    span: Double,
    sigma: Double,
    nSoll: Int,
): SweepCfg? {
    val reps = zaehleEdge(sig, sampleRateHz, theta, refr, baseline, prominenz = prominenz)
    if (reps.size != nSoll) return null
    val hoehen = reps.map { it.height }
    if (median(hoehen) < baseline + 0.5 * span) return null
    val intervalle =
        (1 until reps.size).map { i ->
            (reps[i].sampleIndex - reps[i - 1].sampleIndex) / sampleRateHz
        }
    val meanI = mean(intervalle)
    val cv =
        if (intervalle.size > 1 && meanI > 0) std(intervalle) / meanI else Double.POSITIVE_INFINITY
    val margin = theta - (baseline + 3 * sigma)
    return SweepCfg(ChosenSignal.GP, theta, refr, prominenz, cv, margin, hoehen)
}

/** Tie-break: minimal interval CV, then maximal margin above noise floor. */
private fun isBetter(
    a: SweepCfg,
    b: SweepCfg,
): Boolean = if (a.cv != b.cv) a.cv < b.cv else a.margin > b.margin

/** Final threshold = median - k*MAD of validated peak heights. */
internal fun medianMinusKMad(
    peakHoehen: List<Double>,
    thetaSweep: Double,
): Quad {
    val med = median(peakHoehen)
    val mad = median(peakHoehen.map { abs(it - med) })
    if (mad < 1e-9) return Quad(thetaSweep, 0.0, med, mad)
    val k = max(0.0, (med - thetaSweep) / mad)
    return Quad(med - k * mad, k, med, mad)
}

// --- Stage C: tempo robustness (Referenz stufeC) ----------------------------

internal fun stufeC(
    sigB: DoubleArray,
    sigC: DoubleArray,
    baseline: Double,
    cfg: SweepCfg,
    nB: Int,
    nC: Int,
    sampleRateHz: Double,
): StufeCResult {
    fun zaehl(
        sig: DoubleArray,
        theta: Double,
    ) = zaehleEdge(sig, sampleRateHz, theta, cfg.refractoryS, baseline, prominenz = cfg.prominenz).size

    fun hoehen(
        sig: DoubleArray,
        theta: Double,
    ) = zaehleEdge(sig, sampleRateHz, theta, cfg.refractoryS, baseline, prominenz = cfg.prominenz).map { it.height }

    val theta0 = cfg.theta
    var thetaArbeit: Double? = null
    if (zaehl(sigC, theta0) == nC && zaehl(sigB, theta0) == nB) {
        thetaArbeit = theta0
    } else {
        for (f in linspace(0.98, 0.05, 60)) {
            val thetaT = baseline + (theta0 - baseline) * f
            if (zaehl(sigB, thetaT) == nB && zaehl(sigC, thetaT) == nC) {
                thetaArbeit = thetaT
                break
            }
        }
        if (thetaArbeit == null) return StufeCResult(theta0, ok = false, angepasst = false)
    }
    // Conservative cap: lower distribution edge of the slow peaks.
    val langsamHoehen = hoehen(sigC, thetaArbeit)
    if (langsamHoehen.isNotEmpty() && cfg.peakHoehen.isNotEmpty()) {
        val medB = median(cfg.peakHoehen)
        val madB = median(cfg.peakHoehen.map { abs(it - medB) })
        val sigmaRel = max(1.4826 * madB / max(medB, 1e-9), 0.10)
        val medC = median(langsamHoehen)
        val deckel = medC - 2.5 * sigmaRel * medC
        val thetaDeckel = min(thetaArbeit, deckel)
        if (thetaDeckel < thetaArbeit - 1e-9 && zaehl(sigB, thetaDeckel) == nB && zaehl(sigC, thetaDeckel) == nC) {
            return StufeCResult(thetaDeckel, ok = true, angepasst = true)
        }
    }
    return StufeCResult(thetaArbeit, ok = true, angepasst = abs(thetaArbeit - theta0) > 1e-9)
}
