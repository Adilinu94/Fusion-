package com.dropsync.domain.sensor

import kotlin.math.sqrt

/**
 * Madgwick IMU filter (6 DOF, gyro + accel), Umbauplan Punkt 8.
 * Estimates the sensor orientation as a quaternion so the calibrated
 * rotation axis can be tracked online: if the sensor shifts during
 * training (sweat, clothing, muscle movement), the projected `rawGp`
 * stays on the real rotation axis instead of fading out.
 *
 * No magnetometer (9 DOF): magnetic disturbances from iron masses in a
 * gym make it unusable. Implementation follows Madgwick 2010
 * (http://x-io.co.uk/open-source-imu-and-ahrs-algorithms/), Section III:
 * gyro integration predicts, gradient descent on the accelerometer error
 * corrects with gain [beta].
 *
 * Sign convention: active rotation, Hamilton quaternion product. A
 * positive gyro rate rotates the frame counter-clockwise around the
 * respective axis when viewed from the axis tip (right-hand rule).
 */
class OrientationTracker(
    private val sampleRateHz: Double = 50.0,
    private val beta: Double = 0.1,
) {
    private var q0 = 1.0
    private var q1 = 0.0
    private var q2 = 0.0
    private var q3 = 0.0

    /**
     * Updates the orientation with one IMU sample.
     * Returns the current quaternion.
     */
    fun update(
        ax: Double,
        ay: Double,
        az: Double,
        gx: Double,
        gy: Double,
        gz: Double,
    ): Quaternion {
        val dt = 1.0 / sampleRateHz
        val gxRad = Math.toRadians(gx)
        val gyRad = Math.toRadians(gy)
        val gzRad = Math.toRadians(gz)

        // Gyro prediction: quaternion derivative (Paper eq. 12).
        val qDot1 = 0.5 * (-q1 * gxRad - q2 * gyRad - q3 * gzRad)
        val qDot2 = 0.5 * (q0 * gxRad + q2 * gzRad - q3 * gyRad)
        val qDot3 = 0.5 * (q0 * gyRad - q1 * gzRad + q3 * gxRad)
        val qDot4 = 0.5 * (q0 * gzRad + q1 * gyRad - q2 * gxRad)

        // Accel correction via gradient descent (Paper eq. 25, 28, 29).
        val norm = sqrt(ax * ax + ay * ay + az * az)
        if (norm > 1e-10) {
            val axN = ax / norm
            val ayN = ay / norm
            val azN = az / norm

            val f1 = 2.0 * (q1 * q3 - q0 * q2) - axN
            val f2 = 2.0 * (q0 * q1 + q2 * q3) - ayN
            val f3 = 2.0 * (0.5 - q1 * q1 - q2 * q2) - azN

            val j11 = -2.0 * q2
            val j12 = 2.0 * q3
            val j13 = -2.0 * q0
            val j14 = 2.0 * q1
            val j21 = 2.0 * q1
            val j22 = 2.0 * q0
            val j23 = 2.0 * q3
            val j24 = 2.0 * q2
            val j31 = 0.0
            val j32 = -4.0 * q1
            val j33 = -4.0 * q2
            val j34 = 0.0

            val s1 = j11 * f1 + j21 * f2 + j31 * f3
            val s2 = j12 * f1 + j22 * f2 + j32 * f3
            val s3 = j13 * f1 + j23 * f2 + j33 * f3
            val s4 = j14 * f1 + j24 * f2 + j34 * f3

            val stepNorm = sqrt(s1 * s1 + s2 * s2 + s3 * s3 + s4 * s4)
            if (stepNorm > 1e-10) {
                q0 += (qDot1 - beta * s1 / stepNorm) * dt
                q1 += (qDot2 - beta * s2 / stepNorm) * dt
                q2 += (qDot3 - beta * s3 / stepNorm) * dt
                q3 += (qDot4 - beta * s4 / stepNorm) * dt
            } else {
                q0 += qDot1 * dt
                q1 += qDot2 * dt
                q2 += qDot3 * dt
                q3 += qDot4 * dt
            }
        } else {
            q0 += qDot1 * dt
            q1 += qDot2 * dt
            q2 += qDot3 * dt
            q3 += qDot4 * dt
        }

        normalize()
        return Quaternion(q0, q1, q2, q3)
    }

    /**
     * Transforms a vector from the reference frame (earth / calibration
     * pose) into the current sensor frame: v' = q_conj * v * q.
     *
     * The integrated quaternion q is the sensor's orientation relative to
     * the reference frame (the gyro equation integrates the sensor's own
     * rotation), so a reference-fixed vector is brought into sensor
     * coordinates by the INVERSE rotation.
     */
    fun rotateVector(
        vx: Double,
        vy: Double,
        vz: Double,
    ): Triple<Double, Double, Double> {
        // q_conj * v
        val r0 = q1 * vx + q2 * vy + q3 * vz
        val r1 = q0 * vx - q2 * vz + q3 * vy
        val r2 = q0 * vy + q1 * vz - q3 * vx
        val r3 = q0 * vz - q1 * vy + q2 * vx
        // (q_conj * v) * q, Hamilton product; scalar part is always 0.
        return Triple(
            r0 * q1 + r1 * q0 + r2 * q3 - r3 * q2,
            r0 * q2 - r1 * q3 + r2 * q0 + r3 * q1,
            r0 * q3 + r1 * q2 - r2 * q1 + r3 * q0,
        )
    }

    /** Returns the current orientation (unit quaternion). */
    fun orientation(): Quaternion = Quaternion(q0, q1, q2, q3)

    /**
     * Overrides the orientation directly (unit quaternion, normalized
     * internally). Useful to seed the tracker from a known pose.
     */
    fun setOrientation(orientation: Quaternion) {
        q0 = orientation.q0
        q1 = orientation.q1
        q2 = orientation.q2
        q3 = orientation.q3
        normalize()
    }

    fun reset() {
        q0 = 1.0
        q1 = 0.0
        q2 = 0.0
        q3 = 0.0
    }

    private fun normalize() {
        val n = sqrt(q0 * q0 + q1 * q1 + q2 * q2 + q3 * q3)
        if (n < 1e-12) {
            reset()
            return
        }
        q0 /= n
        q1 /= n
        q2 /= n
        q3 /= n
    }
}

data class Quaternion(
    val q0: Double,
    val q1: Double,
    val q2: Double,
    val q3: Double,
) {
    fun norm(): Double = sqrt(q0 * q0 + q1 * q1 + q2 * q2 + q3 * q3)
}
