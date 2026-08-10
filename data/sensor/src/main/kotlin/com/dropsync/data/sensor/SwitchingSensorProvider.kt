package com.dropsync.data.sensor

import com.dropsync.core.common.AppResult
import com.dropsync.domain.sensor.DeviceEvent
import com.dropsync.domain.sensor.SensorConnectionState
import com.dropsync.domain.sensor.SensorProvider
import com.dropsync.domain.sensor.SensorSample
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest

/**
 * Runtime-switching sensor provider (Fusion Phase 4 step 4): exposes the
 * FakeSensorProvider (manual +/-) until the BleSensorProvider reports a live
 * chip connection, then transparently switches every flow over. Consumers
 * collect a single SensorProvider and never deal with the fallback logic.
 *
 * The switch key is the BLE connection state: as soon as it leaves
 * DISCONNECTED the real provider wins; on disconnect we fall back to fake.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class SwitchingSensorProvider(
    private val ble: BleSensorProvider,
    private val fake: FakeSensorProvider,
) : SensorProvider {
    /** True while the real chip is (or is about to be) connected. */
    private val useBle: Flow<Boolean> =
        kotlinx.coroutines.flow.flow {
            ble.connectionState.collect { emit(it != SensorConnectionState.DISCONNECTED) }
        }

    override val connectionState: StateFlow<SensorConnectionState>
        get() = ble.connectionState

    override val samples: Flow<SensorSample> =
        useBle.flatMapLatest { on -> if (on) ble.samples else fake.samples }

    override val deviceEvents: Flow<DeviceEvent> =
        useBle.flatMapLatest { on -> if (on) ble.deviceEvents else fake.deviceEvents }

    override suspend fun connect(deviceId: String?): AppResult<Unit> = ble.connect(deviceId)

    override suspend fun disconnect() = ble.disconnect()

    override suspend fun startStreaming() = ble.startStreaming()

    override suspend fun stopStreaming() = ble.stopStreaming()
}
