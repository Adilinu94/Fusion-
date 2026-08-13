package com.dropsync.core.testing

import com.dropsync.core.common.AppResult
import com.dropsync.domain.sensor.CalibrationProfile
import com.dropsync.domain.sensor.CalibrationProfileRepository
import com.dropsync.domain.sensor.DeviceEvent
import com.dropsync.domain.sensor.SensorConnectionState
import com.dropsync.domain.sensor.SensorProvider
import com.dropsync.domain.sensor.SensorSample
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow

/**
 * Steuerbarer [SensorProvider]-Fake (Testinfra-Umbau Schritt 2).
 *
 * Liefert einen injizierbaren Sample-Stream; der Verbindungsstatus bleibt
 * konfigurierbar. Rein JVM — kein Android, kein BLE.
 */
class FakeSensorProvider : SensorProvider {
    private val _samples = MutableSharedFlow<SensorSample>(extraBufferCapacity = 64)
    private val _connectionState = MutableStateFlow(SensorConnectionState.DISCONNECTED)
    private val _connectedDeviceId = MutableStateFlow<String?>(null)

    override val connectionState = _connectionState
    override val samples: Flow<SensorSample> = _samples
    override val deviceEvents: Flow<DeviceEvent> = emptyFlow()
    override val connectedDeviceId = _connectedDeviceId

    fun emitSample(gyro: Double) {
        _samples.tryEmit(
            SensorSample(timestampMs = 0L, ax = 0.0, ay = 0.0, az = 9.8, gx = gyro, gy = 0.0, gz = 0.0),
        )
    }

    fun emit(sample: SensorSample) {
        _samples.tryEmit(sample)
    }

    fun setConnectionState(state: SensorConnectionState) {
        _connectionState.value = state
    }

    fun setConnectedDeviceId(id: String?) {
        _connectedDeviceId.value = id
    }

    override suspend fun connect(deviceId: String?): AppResult<Unit> = AppResult.Success(Unit)

    override suspend fun disconnect() = Unit

    override suspend fun startStreaming() = Unit

    override suspend fun stopStreaming() = Unit
}

/**
 * [CalibrationProfileRepository]-Fake: hält Profile im Speicher,
 * steuerbar über [profiles]. Lädt standardmäßig `null` (kein Profil).
 */
class FakeCalibrationProfileRepository(
    private val profiles: MutableMap<Pair<Long, String>, CalibrationProfile> = mutableMapOf(),
) : CalibrationProfileRepository {
    val saved = mutableListOf<CalibrationProfile>()

    fun put(profile: CalibrationProfile) {
        profiles[profile.exerciseId to profile.deviceId] = profile
    }

    override suspend fun load(
        exerciseId: Long,
        deviceId: String,
    ): AppResult<CalibrationProfile?> = AppResult.Success(profiles[exerciseId to deviceId])

    override suspend fun save(profile: CalibrationProfile): AppResult<Unit> {
        profiles[profile.exerciseId to profile.deviceId] = profile
        saved += profile
        return AppResult.Success(Unit)
    }

    override suspend fun delete(
        exerciseId: Long,
        deviceId: String,
    ): AppResult<Unit> {
        profiles.remove(exerciseId to deviceId)
        return AppResult.Success(Unit)
    }
}
