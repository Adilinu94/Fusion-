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
    sampleRateHz: Double = 50.0,
    private val oneEuroMinCutoff: Double = 1.0,
    private val oneEuroBeta: Double = 0.007,
    private val envelopeCutoffHz: Double = 3.0,
    private val settleSamples: Int = 50,
    private val accelEnabled: Boolean = false,
    private val accelOneEuroMinCutoff: Double = 2.0,
    private val orientationTracker: OrientationTracker? = null,
) {
    private var samplesSeen = 0

    /**
     * Aktuell verwendete Abtastrate. P2-Fix #21: nicht mehr fix, sondern
     * ueber [updateSampleRate] aus der gemessenen Rate nachgefuehrt (siehe
     * [SampleRateEstimator]). Alle Zeitkonstanten der Kette haengen daran.
     */
    var sampleRateHz: Double = sampleRateHz
        private set

    private val oneEuro = OneEuroFilter(oneEuroMinCutoff, oneEuroBeta, sampleRateHz)
    private val envelope = EnvelopeDetector(envelopeCutoffHz, sampleRateHz)
    private val oneEuroAccel = OneEuroFilter(accelOneEuroMinCutoff, oneEuroBeta, sampleRateHz)
    private val envelopeAccel = EnvelopeDetector(envelopeCutoffHz, sampleRateHz)

    /** True once [settleSamples] frames passed (filters warmed up). */
    val isSettled: Boolean
        get() = samplesSeen >= settleSamples

    /**
     * P2-Fix #21: uebernimmt eine neue gemessene Abtastrate und reicht sie an
     * alle Zeitkonstanten weiter (One-Euro-Alpha, Envelope-Decay,
     * Madgwick-dt). Der Filter-INHALT bleibt erhalten, nur die Konstanten
     * werden neu berechnet - ein Reset waere hier schaedlich, weil er die
     * Einschwingphase mitten im Satz erneut ausloesen wuerde.
     */
    fun updateSampleRate(rateHz: Double) {
        if (!rateHz.isFinite() || rateHz <= 0.0) return
        sampleRateHz = rateHz
        oneEuro.updateSampleRate(rateHz)
        envelope.updateSampleRate(rateHz)
        oneEuroAccel.updateSampleRate(rateHz)
        envelopeAccel.updateSampleRate(rateHz)
        orientationTracker?.updateSampleRate(rateHz)
    }

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
        // Punkt 8: online axis tracking - der Madgwick-Filter dreht die
        // kalibrierte Achse in das aktuelle Sensor-Koordinatensystem.
        // WICHTIG: der Tracker bekommt die BIAS-KORRIGIERTEN Raten. Mit den
        // rohen Werten integriert ein konstanter Gyro-Bias (MPU6886: 2-5
        // deg/s typisch) zu monoton wachsender Orientierungsdrift - die
        // nachgefuehrte Achse wandert dann WEG von der echten Rotationsachse
        // und kehrt den Zweck der Nachfuehrung um.
        val tracker = orientationTracker
        tracker?.update(ax, ay, az, dx, dy, dz)
        val axis =
            if (tracker == null) {
                rotationAxis
            } else {
                val (rx, ry, rz) = tracker.rotateVector(rotationAxis[0], rotationAxis[1], rotationAxis[2])
                doubleArrayOf(rx, ry, rz)
            }
        val rawGp = dx * axis[0] + dy * axis[1] + dz * axis[2]
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

    /**
     * P2-Fix #24 (ZUPT): uebernimmt einen neu gemessenen Gyro-Bias, ohne die
     * Achse anzufassen.
     *
     * Warum die Achse ausgenommen bleibt: im Ruhezustand ist die
     * Rotationsachse nicht beobachtbar (es gibt keine Rotation). Der
     * ZUPT-Detektor kann deshalb nur den Bias liefern; fuer die Achse ist der
     * [OrientationTracker] zustaendig.
     *
     * Der Filterzustand bleibt bewusst erhalten: ein Reset wuerde mitten im
     * Satz die Einschwingphase erneut ausloesen und dabei echte Samples
     * verwerfen.
     */
    fun updateGyroBias(bias: DoubleArray) {
        require(bias.size == 3) { "bias must have 3 components" }
        if (bias.any { !it.isFinite() }) return
        gyroBias = bias.copyOf()
    }

    /** Aktuell verwendeter Gyro-Bias (Diagnose/Telemetrie). */
    fun currentGyroBias(): DoubleArray = gyroBias.copyOf()

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
    sampleRateHz: Double = 50.0,
) {
    private var xPrev: Double? = null
    private var dxPrev: Double = 0.0

    /** P2-Fix #21: gemessene Rate, ueber [updateSampleRate] nachgefuehrt. */
    var sampleRateHz: Double = sampleRateHz
        private set

    /** Uebernimmt eine neue Abtastrate; Filterzustand bleibt erhalten. */
    fun updateSampleRate(rateHz: Double) {
        if (!rateHz.isFinite() || rateHz <= 0.0) return
        sampleRateHz = rateHz
    }

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
    sampleRateHz: Double = 50.0,
) {
    private var value: Double = 0.0

    /** P2-Fix #21: gemessene Rate, ueber [updateSampleRate] nachgefuehrt. */
    var sampleRateHz: Double = sampleRateHz
        private set

    private var decay: Double = computeDecay(sampleRateHz)

    /**
     * Uebernimmt eine neue Abtastrate. Der Decay-Faktor haengt direkt an der
     * Rate: mit fix 50 Hz und real 30 Hz waere die Huellkurve zu langsam
     * abgefallen und die Aktivitaets-Gates haetten zu spaet reagiert.
     */
    fun updateSampleRate(rateHz: Double) {
        if (!rateHz.isFinite() || rateHz <= 0.0) return
        sampleRateHz = rateHz
        decay = computeDecay(rateHz)
    }

    fun process(absValue: Double): Double {
        value = max(absValue, value * decay)
        return value
    }

    fun reset() {
        value = 0.0
    }

    private fun computeDecay(rateHz: Double): Double = exp(-1.0 / (cutoffHz * rateHz))
}
