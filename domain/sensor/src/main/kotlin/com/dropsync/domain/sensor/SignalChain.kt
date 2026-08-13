package com.dropsync.domain.sensor

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Signal chain of the new rep-detection pipeline (port of
 * filters/signal_chain.dart): raw gyro -> bias-corrected projection onto the
 * calibrated rotation axis -> One-Euro filter -> envelope -> ProcessedFrame.
 *
 * Punkt 4: mit [accelEnabled] laeuft ein zweiter Zweig fuer den Accel-Kanal.
 * Die Abweichung der Magnitude von 1 g (Ruhezustand ~0) wird gefiltert und
 * als `smoothedAccel`/`accelEnvelope` in den Frame gelegt; der RepCounter
 * stimmt Gyro-Peaks per Voting gegen diesen Kanal ab.
 */
class SignalChain(
    private var rotationAxis: DoubleArray,
    private var gyroBias: DoubleArray,
    private val sampleRateHz: Double = 50.0,
    private val oneEuroMinCutoff: Double = 1.0,
    private val oneEuroBeta: Double = 0.007,
    private val envelopeCutoffHz: Double = 3.0,
    private val settleSamples: Int = 50,
    private val accelEnabled: Boolean = false,
    private val accelOneEuroMinCutoff: Double = 2.0,
) {
    private var samplesSeen = 0
    private val oneEuro = OneEuroFilter(oneEuroMinCutoff, oneEuroBeta, sampleRateHz)
    private val envelope = EnvelopeDetector(envelopeCutoffHz, sampleRateHz)
    private val oneEuroAccel = OneEuroFilter(accelOneEuroMinCutoff, oneEuroBeta, sampleRateHz)
    private val envelopeAccel = EnvelopeDetector(envelopeCutoffHz, sampleRateHz)

    /** True once [settleSamples] frames passed (filters warmed up). */
    val isSettled: Boolean
        get() = samplesSeen >= settleSamples

    /** Processes one raw sample into a [ProcessedFrame]. */
    fun process(
        timestampMs: Long,
        gx: Double,
        gy: Double,
        gz: Double,
        ax: Double = 0.0,
        ay: Double = 0.0,
        az: Double = 0.0,
    ): ProcessedFrame {
        samplesSeen++
        val dx = gx - gyroBias[0]
        val dy = gy - gyroBias[1]
        val dz = gz - gyroBias[2]
        val rawGp = dx * rotationAxis[0] + dy * rotationAxis[1] + dz * rotationAxis[2]
        val filtered = oneEuro.process(rawGp)
        val env = envelope.process(abs(filtered))
        val accelDev = if (accelEnabled) abs(sqrt(ax * ax + ay * ay + az * az) - 1.0) else 0.0
        val filteredAccel = oneEuroAccel.process(accelDev)
        val envAccel = envelopeAccel.process(abs(filteredAccel))
        return ProcessedFrame(
            timestampMs = timestampMs,
            rawGp = rawGp,
            filteredGp = filtered,
            smoothedGp = filtered,
            envelope = env,
            smoothedAccel = filteredAccel,
            accelEnvelope = envAccel,
            isSettled = isSettled,
        )
    }

    /** Adopts a new calibration axis + bias (recalibration without reset). */
    fun updateCalibration(
        rotationAxis: List<Double>,
        gyroBias: List<Double>,
    ) {
        require(rotationAxis.size == 3 && gyroBias.size == 3) { "axis and bias must have 3 components" }
        this.rotationAxis = rotationAxis.toDoubleArray()
        this.gyroBias = gyroBias.toDoubleArray()
    }

    /** Full reset (new session / exercise switch / reconnect). */
    fun reset() {
        samplesSeen = 0
        oneEuro.reset()
        envelope.reset()
        oneEuroAccel.reset()
        envelopeAccel.reset()
    }
}

/**
 * One-Euro filter (port of filters/one_euro_filter.dart): adaptive low-pass
 * whose cutoff rises with the signal's derivative - smooth at rest, responsive
 * during fast movement. Standard Casiez/Roussel/Le Beux formulation.
 */
class OneEuroFilter(
    private val minCutoff: Double = 1.0,
    private val beta: Double = 0.007,
    private val sampleRateHz: Double = 50.0,
) {
    private var xPrev: Double? = null
    private var dxPrev: Double = 0.0

    fun process(x: Double): Double {
        val prev = xPrev
        if (prev == null) {
            xPrev = x
            return x
        }
        val dx = (x - prev) * sampleRateHz
        val aD = smoothingAlpha(1.0)
        val dxHat = aD * dx + (1 - aD) * dxPrev
        val cutoff = minCutoff + beta * abs(dxHat)
        val a = smoothingAlpha(cutoff)
        val xHat = a * x + (1 - a) * prev
        xPrev = xHat
        dxPrev = dxHat
        return xHat
    }

    private fun smoothingAlpha(cutoffHz: Double): Double {
        val tau = 1.0 / (2.0 * Math.PI * cutoffHz)
        val te = 1.0 / sampleRateHz
        return 1.0 / (1.0 + tau / te)
    }

    fun reset() {
        xPrev = null
        dxPrev = 0.0
    }
}

/**
 * Envelope detector (port of filters/envelope_detector.dart): exponential
 * decay envelope of the (rectified) signal, used for diagnostics and
 * activity gating.
 */
class EnvelopeDetector(
    private val cutoffHz: Double = 3.0,
    private val sampleRateHz: Double = 50.0,
) {
    private var value: Double = 0.0
    private val alpha: Double =
        run {
            val tau = 1.0 / (2.0 * Math.PI * cutoffHz)
            val te = 1.0 / sampleRateHz
            1.0 / (1.0 + tau / te)
        }
    private val decay: Double = exp(-1.0 / (cutoffHz * sampleRateHz))

    fun process(absValue: Double): Double {
        value = max(absValue, value * decay)
        return value
    }

    fun reset() {
        value = 0.0
    }
}
