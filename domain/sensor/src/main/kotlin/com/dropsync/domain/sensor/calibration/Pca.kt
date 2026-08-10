package com.dropsync.domain.sensor.calibration

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Jacobi eigenvalue decomposition of a symmetric 3x3 matrix (port of
 * _jacobiEigen3 in calibration_controller.dart). The design doc requires no
 * extra dependency for the 3D-gyro PCA ("Neue Dependencies": 3x3 is enough).
 *
 * Returns eigenvalues [w] and eigenvectors [v] (columns).
 */
internal data class JacobiResult(
    val w: DoubleArray,
    val v: Array<DoubleArray>,
)

internal fun jacobiEigen3(aIn: Array<DoubleArray>): JacobiResult {
    val a = Array(3) { k -> aIn[k].copyOf() }
    val v =
        arrayOf(
            doubleArrayOf(1.0, 0.0, 0.0),
            doubleArrayOf(0.0, 1.0, 0.0),
            doubleArrayOf(0.0, 0.0, 1.0),
        )
    for (sweep in 0 until 64) {
        val off = sqrt(a[0][1] * a[0][1] + a[0][2] * a[0][2] + a[1][2] * a[1][2])
        if (off < 1e-12) break
        for (p in 0 until 2) {
            for (q in p + 1 until 3) {
                if (abs(a[p][q]) < 1e-15) continue
                val phi = (a[q][q] - a[p][p]) / (2 * a[p][q])
                val t = (if (phi >= 0) 1.0 else -1.0) / (abs(phi) + sqrt(phi * phi + 1))
                val c = 1 / sqrt(t * t + 1)
                val s = t * c
                for (k in 0 until 3) {
                    val akp = a[k][p]
                    val akq = a[k][q]
                    a[k][p] = c * akp - s * akq
                    a[k][q] = s * akp + c * akq
                }
                for (k in 0 until 3) {
                    val apk = a[p][k]
                    val aqk = a[q][k]
                    a[p][k] = c * apk - s * aqk
                    a[q][k] = s * apk + c * aqk
                }
                for (k in 0 until 3) {
                    val vkp = v[k][p]
                    val vkq = v[k][q]
                    v[k][p] = c * vkp - s * vkq
                    v[k][q] = s * vkp + c * vkq
                }
            }
        }
    }
    return JacobiResult(w = doubleArrayOf(a[0][0], a[1][1], a[2][2]), v = v)
}

/**
 * Principal rotation axis of a bias-corrected gyro window via 3x3 PCA.
 * Sign convention: the largest excursion of the rep is positive.
 */
internal fun principalAxis(window: List<DoubleArray>): Pair<DoubleArray, Double>? {
    if (window.isEmpty()) return null
    val m = DoubleArray(3)
    for (r in window) {
        m[0] += r[0]
        m[1] += r[1]
        m[2] += r[2]
    }
    m[0] /= window.size
    m[1] /= window.size
    m[2] /= window.size

    val cov = Array(3) { DoubleArray(3) }
    for (r in window) {
        val d = doubleArrayOf(r[0] - m[0], r[1] - m[1], r[2] - m[2])
        for (k in 0 until 3) {
            for (l in 0 until 3) {
                cov[k][l] += d[k] * d[l]
            }
        }
    }
    val denom = maxOf(window.size - 1, 1)
    for (k in 0 until 3) {
        for (l in 0 until 3) {
            cov[k][l] /= denom
        }
    }

    val eig = jacobiEigen3(cov)
    var imax = 0
    for (k in 1 until 3) {
        if (eig.w[k] > eig.w[imax]) imax = k
    }
    var axis = doubleArrayOf(eig.v[0][imax], eig.v[1][imax], eig.v[2][imax])

    val varSum = eig.w.sum()
    val varianceShare = if (varSum > 0) eig.w[imax] / varSum else 0.0

    // Sign convention: largest excursion of the projected signal is positive.
    val projected = window.map { r -> r[0] * axis[0] + r[1] * axis[1] + r[2] * axis[2] }
    val mx = projected.max()
    val mn = projected.min()
    if (mx < -mn) axis = doubleArrayOf(-axis[0], -axis[1], -axis[2])

    return axis to varianceShare
}
