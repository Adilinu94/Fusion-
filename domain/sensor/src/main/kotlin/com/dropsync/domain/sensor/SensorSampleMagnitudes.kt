package com.dropsync.domain.sensor

import kotlin.math.sqrt

// A8: Die beiden Norm-Helfer lagen frueher in `SignalProcessor.kt`. Die
// SignalProcessor-Klasse selbst war produktiv tot (die Kalibrierung bringt
// ihre eigene Mathematik mit); diese Erweiterungen sind live.

/** Euclidean norm of the acceleration vector, in g. */
val SensorSample.accelMagnitude: Double
    get() = sqrt(ax * ax + ay * ay + az * az)

/** Euclidean norm of the gyro vector, in deg/s. */
val SensorSample.gyroMagnitude: Double
    get() = sqrt(gx * gx + gy * gy + gz * gz)
