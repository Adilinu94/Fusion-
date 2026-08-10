package com.dropsync.data.sensor

import com.dropsync.domain.sensor.DeviceEvent
import com.dropsync.domain.sensor.SensorConnectionState
import com.dropsync.domain.sensor.SensorProvider
import com.dropsync.domain.sensor.SensorSample
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * No-chip provider (Fusion Phase 4 step 4): bound while no FlowRep chip is
 * connected, so the Train tab falls back to manual +/- without null checks.
 * Emits no samples and no device events.
 */
@Singleton
class FakeSensorProvider
    @Inject
    constructor() : SensorProvider {
        private val _connectionState = MutableStateFlow(SensorConnectionState.DISCONNECTED)
        override val connectionState: StateFlow<SensorConnectionState> = _connectionState.asStateFlow()

        override val samples: Flow<SensorSample> = emptyFlow()
        override val deviceEvents: Flow<DeviceEvent> = emptyFlow()

        override val connectedDeviceId: StateFlow<String?> = MutableStateFlow(null)

        override suspend fun connect(deviceId: String?): com.dropsync.core.common.AppResult<Unit> =
            com.dropsync.core.common.AppResult
                .success(Unit)

        override suspend fun disconnect() = Unit

        override suspend fun startStreaming() = Unit

        override suspend fun stopStreaming() = Unit
    }
