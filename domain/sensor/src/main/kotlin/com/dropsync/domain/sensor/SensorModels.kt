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

/** Version of the rep-detection pipeline a profile was calibrated with. */
enum class RepEngineVersion {
    /** Legacy pipeline (shadow-only, pre Umbauplan). */
    V1_CURRENT,

    /** Reliable pipeline: two-phase validation, timestamp-based timing. */
    V2_RELIABLE,
}

/** Which signal branch the calibration used (Umbauplan Phase 1.1). */
enum class RepSignalKind {
    SIGNED_GYRO_PROJECTION,
    GYRO_MAGNITUDE,
    COMBINED_GYRO_ACCEL,
}

/**
 * Lebenszyklus einer Profilrevision (Umbauplan Phase 7.4): aktive Profile
 * treiben die Live-Pipeline, Kandidaten muessen sich erst durch validierte
 * Sets beweisen, RETIRED-Revisionen bleiben fuer Rollbacks erhalten.
 */
enum class ProfileStatus {
    ACTIVE,
    CANDIDATE,
    RETIRED,
}

/**
 * Calibration profile per exercise + device (Phase 4 step 3): the rotation
 * axis + gyro bias learned by Guided Calibration 2.0, the rep template, and
 * the detection parameters the live pipeline starts from.
 *
 * Without [rotationAxis]/[gyroBias] the pipeline cannot project the gyro
 * signal correctly, so a profile that lacks them is treated as absent.
 *
 * Umbauplan Phase 0/1: the profile is versioned ([schemaVersion],
 * [engineVersion], [signalKind]) and stores the calibrated threshold
 * [detectionThreshold] directly - no lossy SPK/NPK reconstruction. Old
 * (unversioned) profiles are not loadable; they lead to "recalibrate".
 *
 * Umbauplan Phase 7.4: profiles carry revisions ([revision],
 * [parentRevision], [status]); the learn loop stores CANDIDATE profiles
 * that are promoted to ACTIVE only after enough validated sets.
 */
data class CalibrationProfile(
    val exerciseId: Long,
    val deviceId: String,
    /** Dominant rotation axis of the exercise (PCA of the single rep). */
    val rotationAxis: List<Double>,
    /** Gyro rest bias measured in the rest stage. */
    val gyroBias: List<Double>,
    val repTemplate: List<Double>,
    /** Expected peak prominence (deg/s) of one clean rep. */
    val expectedProminence: Double,
    /** Calibration quality 0..1 (1 = clean sweep), for the wizard review. */
    val qualityScore: Double = 1.0,
    /** Persisted schema version of this profile. */
    val schemaVersion: Int = PROFILE_SCHEMA_VERSION,
    /** Pipeline version this profile was calibrated for. */
    val engineVersion: RepEngineVersion = RepEngineVersion.V2_RELIABLE,
    /** Signal branch used during calibration (only GP is released). */
    val signalKind: RepSignalKind = RepSignalKind.SIGNED_GYRO_PROJECTION,
    /** Calibrated detection threshold theta (deg/s) - used directly live. */
    val detectionThreshold: Double,
    /** Measured noise floor (deg/s) of the rest signal. */
    val noiseFloor: Double,
    /** Expected duration of one full rep cycle in milliseconds. */
    val expectedDurationMs: Double,
    /** Umbauplan Phase 7.4: Revisionsnummer (Kalibrierung startet bei 1). */
    val revision: Int = 1,
    /** Vorgaenger-Revision; null nur bei der Erst-Kalibrierung. */
    val parentRevision: Int? = null,
    /** Lebenszyklus-Status dieser Revision. */
    val status: ProfileStatus = ProfileStatus.ACTIVE,
    /** Validierte Sets dieser Revision (Promotion bei genug Beweisen). */
    val validatedSetCount: Int = 0,
    /**
     * P2-Fix #22: kalibrierte Accel-Schwelle (Abweichung der Magnitude von
     * 1 g). 0.0 = nicht kalibriert; dann laeuft die Live-Pipeline OHNE
     * Accel-Voting weiter. Vorher stand im Code eine geratene Konstante
     * (0.1625, aus der Gyro-Schwelle geteilt durch 200), weshalb der Kanal
     * dauerhaft abgeschaltet blieb.
     */
    val accelThreshold: Double = 0.0,
    /**
     * B3 (RC-20): Template-Match-Schwelle, mit der dieses Profil live
     * gezaehlt wurde. Vorher lag der Wert nur als Code-Default (0.7) in
     * [ExerciseEngineConfig] und war fuer Sweep/Replay nicht rekonstruierbar;
     * `null`-Semantik gibt es hier bewusst nicht, der Default ist der
     * bisherige Code-Default.
     */
    val templateThreshold: Double = 0.7,
    /**
     * B3 (RC-20): Mindest-Qualitaetsscore der Live-Pipeline (Default 0.55,
     * wie [ExerciseEngineConfig.minQualityScore]).
     */
    val minQualityScore: Double = 0.55,
    /**
     * B3 (RC-20): Sakoe-Chiba-Bandbreite des DTW-Template-Vergleichs
     * (Default 8, wie [TemplateMatcher.DTW_BAND]); gueltig 1..64.
     */
    val dtwBand: Int = 8,
) {
    /**
     * P2-Fix #22: true, wenn der Accel-Kanal eine belastbare Schwelle hat und
     * das Voting deshalb live mitlaufen darf.
     */
    val accelVotingAvailable: Boolean
        get() = accelThreshold.isFinite() && accelThreshold > 0.0

    companion object {
        /**
         * Current persisted schema. Profiles with a different version are
         * rejected by the codec ("recalibrate" instead of misinterpreting).
         * v4 adds revision/parentRevision/status/validatedSetCount.
         * v5 adds accelThreshold (P2-Fix #22).
         * v6 adds templateThreshold/minQualityScore/dtwBand (B3/RC-20); die
         * Felder werden transportiert, nicht gelernt (Stufe 1).
         */
        const val PROFILE_SCHEMA_VERSION = 6
    }
}
