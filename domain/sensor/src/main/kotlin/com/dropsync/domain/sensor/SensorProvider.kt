package com.dropsync.domain.sensor

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Sensor contract (Fusion design doc Phase 4 step 1). Implementations:
 * BleSensorProvider (real M5 chip) and FakeSensorProvider (manual +/- when
 * no chip is connected, Phase 4 step 4).
 */
interface SensorProvider {
    val connectionState: StateFlow<SensorConnectionState>

    /** Parsed 50 Hz sample stream (after JitterBuffer in :data:sensor). */
    val samples: Flow<SensorSample>

    /** Device button events (fee4); empty flow on the fake provider. */
    val deviceEvents: Flow<DeviceEvent>

    /** BLE address of the connected chip; null until connected. */
    val connectedDeviceId: StateFlow<String?>

    /**
     * Connects to a FlowRep chip. [deviceId] null = scan by advertise name.
     * Returns an [AppResult] so BLE failures surface as German user text
     * (BleErrorMapper), never as a thrown exception.
     */
    suspend fun connect(deviceId: String?): com.dropsync.core.common.AppResult<Unit>

    suspend fun disconnect()

    /** START_STREAM (0x01) via ControlPoint; no-op on the fake provider. */
    suspend fun startStreaming()

    /** STOP_STREAM (0x02) via ControlPoint; no-op on the fake provider. */
    suspend fun stopStreaming()
}

/**
 * Counting engine contract (Phase 4 step 1). The shadow pipeline
 * (RepCounter) implements this; it runs alongside once a calibration
 * profile exists but never counts live (design doc step 6).
 */
interface ExerciseEngine {
    val repCount: StateFlow<Int>

    fun processFrame(frame: ProcessedFrame)

    fun reset()
}
