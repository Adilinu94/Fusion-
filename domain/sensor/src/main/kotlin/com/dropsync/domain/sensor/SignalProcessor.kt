package com.dropsync.domain.sensor

import kotlin.math.sqrt

/**
 * Pure signal processing (port of signal_processor.dart): takes raw
 * [SensorSample]s, applies EMA low-pass filtering and combined-signal
 * computation, and returns cleaned output. No state machine logic - that
 * belongs in the engine layer above.
 *
 * The signed gyro projection (Schritt B / g_p) separates concentric and
 * eccentric phases by SIGN instead of timing, which fixes the double-hump
 * case structurally (proven in tools/workout_engine_simulation.py).
 */
class SignalProcessor(
    /** Weight of gyroscope magnitude in the combined signal. */
    private val gyroWeight: Double = 0.05,
    /** EMA low-pass coefficient, 0 < alpha <= 1. Higher = less smoothing. */
    private val lowPassAlpha: Double = 0.6,
    /** Samples to learn the dominant axis + bias before projection is ready. */
    private val axisLearningWindowSamples: Int = 100,
) {
    private var filteredSignal: Double? = null

    /** The most recent filtered value, or 0.0 if no samples processed yet. */
    val lastFiltered: Double
        get() = filteredSignal ?: 0.0

    /** Filtered combined signal: accelMagnitude + gyroMagnitude * gyroWeight. */
    fun process(s: SensorSample): Double {
        val raw = s.accelMagnitude + s.gyroMagnitude * gyroWeight
        val prev = filteredSignal
        val next = if (prev == null) raw else prev * (1 - lowPassAlpha) + raw * lowPassAlpha
        filteredSignal = next
        return next
    }

    // --- Schritt B: signed gyro projection onto a learned dominant axis ---

    private val gyroLearningWindow = mutableListOf<DoubleArray>()
    private var gyroBias: DoubleArray? = null
    private var dominantAxis: DoubleArray? = null // unit vector [x, y, z]
    private var axisIsKnown = false

    /** True once a dominant axis is known (learned or provided). */
    val isSignedProjectionReady: Boolean
        get() = dominantAxis != null

    /**
     * Feed a raw sample toward learning the dominant gyro axis + bias.
     * No-op once learning completed or [setKnownAxis] was called. Never
     * touches [process]/[filteredSignal] - separate bookkeeping.
     */
    fun observeForAxisLearning(s: SensorSample) {
        if (dominantAxis != null) return
        gyroLearningWindow.add(doubleArrayOf(s.gx, s.gy, s.gz))
        if (gyroLearningWindow.size < axisLearningWindowSamples) return

        val n = gyroLearningWindow.size
        val means = DoubleArray(3)
        for (v in gyroLearningWindow) {
            means[0] += v[0]
            means[1] += v[1]
            means[2] += v[2]
        }
        means[0] /= n
        means[1] /= n
        means[2] /= n

        val variances = DoubleArray(3)
        for (v in gyroLearningWindow) {
            for (axis in 0 until 3) {
                val d = v[axis] - means[axis]
                variances[axis] += d * d
            }
        }
        var bestAxis = 0
        for (axis in 1 until 3) {
            if (variances[axis] > variances[bestAxis]) bestAxis = axis
        }

        gyroBias = means
        dominantAxis = DoubleArray(3) { i -> if (i == bestAxis) 1.0 else 0.0 }
        gyroLearningWindow.clear()
    }

    /**
     * Adopt an already-known rotation axis + gyro bias (from a calibration
     * profile) instead of learning at runtime. Unlike a self-learned axis, a
     * known one SURVIVES [reset]: it came from a real calibration, not from
     * this session's samples, so a reconnect has no reason to discard it.
     */
    fun setKnownAxis(
        axis: List<Double>,
        bias: List<Double>,
    ) {
        require(axis.size == 3 && bias.size == 3) { "axis and bias must have 3 components" }
        dominantAxis = axis.toDoubleArray()
        gyroBias = bias.toDoubleArray()
        gyroLearningWindow.clear()
        axisIsKnown = true
    }

    /**
     * Signed projection of the bias-corrected gyro vector onto the dominant
     * axis: (gyro - bias) . axis. NOT smoothed (matches the Python proof).
     * Null until [isSignedProjectionReady].
     */
    fun signedGyroProjection(s: SensorSample): Double? {
        val axis = dominantAxis ?: return null
        val bias = gyroBias ?: return null
        return (s.gx - bias[0]) * axis[0] + (s.gy - bias[1]) * axis[1] + (s.gz - bias[2]) * axis[2]
    }

    /**
     * Bias-corrected gyro magnitude (distinct from [SensorSample.gyroMagnitude],
     * which is NOT bias-corrected). Null until [isSignedProjectionReady].
     */
    fun biasCorrectedGyroMagnitude(s: SensorSample): Double? {
        val bias = gyroBias ?: return null
        val dx = s.gx - bias[0]
        val dy = s.gy - bias[1]
        val dz = s.gz - bias[2]
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    /**
     * Reset filter state (engine reconnect / new session). A KNOWN axis
     * ([setKnownAxis]) survives; the self-learned placeholder is discarded.
     */
    fun reset() {
        filteredSignal = null
        gyroLearningWindow.clear()
        if (!axisIsKnown) {
            gyroBias = null
            dominantAxis = null
        }
    }
}

/** Euclidean norm of the acceleration vector, in g. */
val SensorSample.accelMagnitude: Double
    get() = sqrt(ax * ax + ay * ay + az * az)

/** Euclidean norm of the gyro vector, in deg/s. */
val SensorSample.gyroMagnitude: Double
    get() = sqrt(gx * gx + gy * gy + gz * gz)
