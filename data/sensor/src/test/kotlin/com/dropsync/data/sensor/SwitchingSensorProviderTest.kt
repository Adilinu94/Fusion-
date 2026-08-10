package com.dropsync.data.sensor

import app.cash.turbine.test
import com.dropsync.core.common.AppResult
import com.dropsync.domain.sensor.DeviceEvent
import com.dropsync.domain.sensor.SensorConnectionState
import com.dropsync.domain.sensor.SensorProvider
import com.dropsync.domain.sensor.SensorSample
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Controllable stand-in for Ble/Fake so the switching logic is testable. */
private class StubProvider(
    initialState: SensorConnectionState,
) : SensorProvider {
    val state = MutableStateFlow(initialState)
    override val connectionState = state

    val sampleFlow = MutableSharedFlow<SensorSample>(extraBufferCapacity = 8)
    override val samples = sampleFlow

    val eventFlow = MutableSharedFlow<DeviceEvent>(extraBufferCapacity = 8)
    override val deviceEvents = eventFlow

    val deviceId = MutableStateFlow<String?>(null)
    override val connectedDeviceId = deviceId

    var connectCalls = 0
        private set

    var lastConnectId: String? = "unset"
        private set

    var disconnectCalls = 0
        private set

    var startStreamingCalls = 0
        private set

    var stopStreamingCalls = 0
        private set

    override suspend fun connect(deviceId: String?): AppResult<Unit> {
        connectCalls++
        lastConnectId = deviceId
        return AppResult.success(Unit)
    }

    override suspend fun disconnect() {
        disconnectCalls++
    }

    override suspend fun startStreaming() {
        startStreamingCalls++
    }

    override suspend fun stopStreaming() {
        stopStreamingCalls++
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class SwitchingSensorProviderTest {
    private fun sample(ms: Long) = SensorSample(ms, 1.0, 0.0, 0.0, 0.0, 0.0, 0.0)

    @Test
    fun `fake samples while ble is disconnected`() =
        runTest {
            val ble = StubProvider(SensorConnectionState.DISCONNECTED)
            val fake = StubProvider(SensorConnectionState.DISCONNECTED)
            val provider = SwitchingSensorProvider(ble, fake)
            provider.samples.test {
                fake.sampleFlow.emit(sample(1))
                ble.sampleFlow.emit(sample(2)) // ignored: fake is active
                assertEquals(1L, awaitItem().timestampMs)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `switches to ble samples once connecting`() =
        runTest {
            val ble = StubProvider(SensorConnectionState.DISCONNECTED)
            val fake = StubProvider(SensorConnectionState.DISCONNECTED)
            val provider = SwitchingSensorProvider(ble, fake)
            provider.samples.test {
                ble.state.value = SensorConnectionState.CONNECTING
                advanceUntilIdle()
                ble.sampleFlow.emit(sample(10))
                fake.sampleFlow.emit(sample(11)) // ignored: ble is active
                assertEquals(10L, awaitItem().timestampMs)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `falls back to fake on disconnect`() =
        runTest {
            val ble = StubProvider(SensorConnectionState.STREAMING)
            val fake = StubProvider(SensorConnectionState.DISCONNECTED)
            val provider = SwitchingSensorProvider(ble, fake)
            provider.samples.test {
                ble.sampleFlow.emit(sample(1))
                assertEquals(1L, awaitItem().timestampMs)
                ble.state.value = SensorConnectionState.DISCONNECTED
                advanceUntilIdle()
                fake.sampleFlow.emit(sample(2))
                assertEquals(2L, awaitItem().timestampMs)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `connectionState and connectedDeviceId always come from ble`() =
        runTest {
            val ble = StubProvider(SensorConnectionState.CONNECTED)
            val fake = StubProvider(SensorConnectionState.STREAMING)
            val provider = SwitchingSensorProvider(ble, fake)
            assertEquals(SensorConnectionState.CONNECTED, provider.connectionState.value)
            ble.deviceId.value = "AA:BB:CC"
            assertEquals("AA:BB:CC", provider.connectedDeviceId.value)
            ble.deviceId.value = null
            assertNull(provider.connectedDeviceId.value)
        }

    @Test
    fun `all commands delegate to ble`() =
        runTest {
            val ble = StubProvider(SensorConnectionState.DISCONNECTED)
            val fake = StubProvider(SensorConnectionState.DISCONNECTED)
            val provider = SwitchingSensorProvider(ble, fake)
            assertTrue(provider.connect("AA:BB") is AppResult.Success)
            provider.disconnect()
            provider.startStreaming()
            provider.stopStreaming()
            assertEquals(1, ble.connectCalls)
            assertEquals("AA:BB", ble.lastConnectId)
            assertEquals(1, ble.disconnectCalls)
            assertEquals(1, ble.startStreamingCalls)
            assertEquals(1, ble.stopStreamingCalls)
            // Fake never receives commands.
            assertEquals(0, fake.connectCalls)
            assertEquals(0, fake.disconnectCalls)
        }
}
