// Core sensor models (Fusion design doc Phase 4 step 1). Pure JVM: the
// values arrive from the BLE parser in :data:sensor but are defined here
// so the counting pipeline never touches Android types.
package com.dropsync.domain.sensor

/** One IMU sample: acceleration in g, gyro in deg/s, device timestamp. */
data class SensorSample(
    val timestampMs: Long,
    val ax: Double,
    val ay: Double,
    val az: Double,
    val gx: Double,
    val gy: Double,
    val gz: Double,
)

/** Connection lifecycle of a sensor device. */
enum class SensorConnectionState { DISCONNECTED, CONNECTING, CONNECTED, STREAMING }

/** Processed frame after the signal chain (port of processed_frame.dart). */
data class ProcessedFrame(
    val timestampMs: Long,
    val rawGp: Double,
    val filteredGp: Double,
    val smoothedGp: Double,
    val envelope: Double,
    /** Accel channel (Punkt 4): deviation of the magnitude from 1 g, filtered. */
    val smoothedAccel: Double = 0.0,
    val accelEnvelope: Double = 0.0,
    /** False until all filters have settled; no peaks before that. */
    val isSettled: Boolean,
)

/**
 * Calibration profile per exercise + device (Phase 4 step 3): the rotation
 * axis + gyro bias learned by Guided Calibration 2.0, the rep template, and
 * the adaptive levels the peak detector/scorer start from.
 *
 * Without [rotationAxis]/[gyroBias] the pipeline cannot project the gyro
 * signal correctly, so a profile that lacks them is treated as absent.
 */
data class CalibrationProfile(
    val exerciseId: Long,
    val deviceId: String,
    /** Dominant rotation axis of the exercise (PCA of the single rep). */
    val rotationAxis: List<Double>,
    /** Gyro rest bias measured in the rest stage. */
    val gyroBias: List<Double>,
    val repTemplate: List<Double>,
    val signalPeakLevel: Double,
    val noisePeakLevel: Double,
    val expectedProminence: Double,
    val expectedDurationSamples: Double,
    /** Calibration quality 0..1 (1 = clean sweep), for the wizard review. */
    val qualityScore: Double = 1.0,
)
