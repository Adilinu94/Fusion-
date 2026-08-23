package com.dropsync.core.testing

import com.dropsync.core.common.AppResult
import com.dropsync.domain.sensor.CalibrationProfile
import com.dropsync.domain.sensor.CalibrationProfileRepository
import com.dropsync.domain.sensor.DeviceEvent
import com.dropsync.domain.sensor.PromotionResult
import com.dropsync.domain.sensor.SensorConnectionState
import com.dropsync.domain.sensor.SensorHealth
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
    private val _health = MutableStateFlow(SensorHealth())

    override val connectionState = _connectionState
    override val samples: Flow<SensorSample> = _samples
    override val deviceEvents: Flow<DeviceEvent> = emptyFlow()
    override val connectedDeviceId = _connectedDeviceId
    override val health: Flow<SensorHealth> = _health

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
        // Konsistent mit der echten Provider-Strecke: der Health-Flow folgt
        // dem Verbindungszustand (sonst meldet UNRELIABLE-Health fae lschlich
        // einen Abbruch, obwohl der Test STREAMING gesetzt hat).
        _health.value = _health.value.copy(connectionState = state)
    }

    fun setConnectedDeviceId(id: String?) {
        _connectedDeviceId.value = id
    }

    fun setHealth(health: SensorHealth) {
        _health.value = health
    }

    override suspend fun connect(deviceId: String?): AppResult<Unit> = AppResult.Success(Unit)

    override suspend fun disconnect() = Unit

    override suspend fun startStreaming() = Unit

    override suspend fun stopStreaming() = Unit
}

/**
 * [CalibrationProfileRepository]-Fake: hält Profile im Speicher,
 * steuerbar über [profiles]. Lädt standardmäßig `null` (kein Profil).
 * Revision-aware: [noteValidatedSet] promoted nach 3 validierten Sets,
 * [rollback] aktiviert die parentRevision.
 */
class FakeCalibrationProfileRepository(
    private val profiles: MutableMap<Pair<Long, String>, CalibrationProfile> = mutableMapOf(),
) : CalibrationProfileRepository {
    val saved = mutableListOf<CalibrationProfile>()
    var promotionSets = 3
    var noteValidatedSetCalls = 0
    var rollbackCalls = 0

    fun put(profile: CalibrationProfile) {
        profiles[profile.exerciseId to profile.deviceId] = profile
    }

    override suspend fun load(
        exerciseId: Long,
        deviceId: String,
    ): AppResult<CalibrationProfile?> = AppResult.Success(profiles[exerciseId to deviceId])

    override suspend fun loadHistory(
        exerciseId: Long,
        deviceId: String,
    ): AppResult<List<CalibrationProfile>> =
        AppResult.Success(
            profiles.values.filter { it.exerciseId == exerciseId && it.deviceId == deviceId },
        )

    override suspend fun save(profile: CalibrationProfile): AppResult<Unit> {
        profiles[profile.exerciseId to profile.deviceId] = profile
        saved += profile
        return AppResult.Success(Unit)
    }

    override suspend fun noteValidatedSet(
        exerciseId: Long,
        deviceId: String,
    ): AppResult<PromotionResult> {
        noteValidatedSetCalls++
        val candidate = profiles[exerciseId to deviceId] ?: return AppResult.Success(PromotionResult.NO_CANDIDATE)
        if (candidate.status != com.dropsync.domain.sensor.ProfileStatus.CANDIDATE) {
            return AppResult.Success(PromotionResult.NO_CANDIDATE)
        }
        val incremented = candidate.copy(validatedSetCount = candidate.validatedSetCount + 1)
        return if (incremented.validatedSetCount >= promotionSets) {
            profiles[exerciseId to deviceId] =
                incremented.copy(
                    status = com.dropsync.domain.sensor.ProfileStatus.ACTIVE,
                    validatedSetCount = 0,
                )
            AppResult.Success(PromotionResult.PROMOTED)
        } else {
            profiles[exerciseId to deviceId] = incremented
            AppResult.Success(PromotionResult.PENDING)
        }
    }

    override suspend fun rollback(
        exerciseId: Long,
        deviceId: String,
    ): AppResult<Boolean> {
        rollbackCalls++
        val active = profiles[exerciseId to deviceId] ?: return AppResult.Success(false)
        val parentRevision = active.parentRevision ?: return AppResult.Success(false)
        // Einfacher Fake: setzt parentRevision auf ACTIVE; der Parameter des
        // Profiles bleibt als Hinweis erhalten.
        profiles[exerciseId to deviceId] =
            active.copy(revision = parentRevision, status = com.dropsync.domain.sensor.ProfileStatus.ACTIVE)
        return AppResult.Success(true)
    }

    override suspend fun delete(
        exerciseId: Long,
        deviceId: String,
    ): AppResult<Unit> {
        profiles.remove(exerciseId to deviceId)
        return AppResult.Success(Unit)
    }
}
