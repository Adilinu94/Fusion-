package com.dropsync.data.sensor

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.dropsync.core.common.AppError
import com.dropsync.core.common.AppResult
import com.dropsync.core.common.DispatcherProvider
import com.dropsync.domain.sensor.DeviceEvent
import com.dropsync.domain.sensor.SensorConnectionState
import com.dropsync.domain.sensor.SensorProvider
import com.dropsync.domain.sensor.SensorSample
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * Real hardware implementation of [SensorProvider] (port of
 * ble_sensor_provider.dart). Talks to the FlowRep GATT service; UUIDs are the
 * authoritative values from docs/reference/protocol.yaml.
 *
 * Hardware quirks preserved from the Flutter implementation:
 * - Advertise names: "FlowRep" (preferred) + "GymTracker" (legacy), dual scan.
 * - MTU 185 is REQUESTED to dodge the HyperOS MTU-517 off-by-one boundary
 *   bug; HyperOS ignores client requests and negotiates 517 anyway, which
 *   fits the 53-byte v2 payload.
 * - NO CCCD on the SensorData characteristic: HyperOS caches the last
 *   notified value and returns it for every read(). Without a CCCD, Android
 *   issues real over-the-air reads; the firmware calls setValue()
 *   unconditionally so read() gets fresh data. Streaming therefore polls
 *   read() at ~30 Hz instead of waiting for notifications.
 * - DeviceEvent (fee4, M5 button): notify when available + 250 ms poll.
 *
 * All GATT operations are serialized through [BleGattClient]: the Android BLE
 * stack allows one outstanding operation at a time.
 */
@Singleton
@SuppressLint("MissingPermission") // Guarded by hasBlePermissions() before every BLE call.
class BleSensorProvider
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val dispatchers: DispatcherProvider,
    ) : SensorProvider {
        private val scope = CoroutineScope(dispatchers.default)

        private val _connectionState = MutableStateFlow(SensorConnectionState.DISCONNECTED)
        override val connectionState: StateFlow<SensorConnectionState> = _connectionState.asStateFlow()

        private val _samples = MutableSharedFlow<SensorSample>(extraBufferCapacity = 64)
        override val samples: SharedFlow<SensorSample> = _samples.asSharedFlow()

        private val _deviceEvents = MutableSharedFlow<DeviceEvent>(extraBufferCapacity = 8)
        override val deviceEvents: SharedFlow<DeviceEvent> = _deviceEvents.asSharedFlow()

        private val _connectedDeviceId = MutableStateFlow<String?>(null)
        override val connectedDeviceId: StateFlow<String?> = _connectedDeviceId.asStateFlow()

        private val jitterBuffer = JitterBuffer<SensorSample>(scope = scope, onFrame = { _samples.tryEmit(it) })
        private val dedupTracker = BatchDedupTracker(expectedBatchIntervalMs = 80)
        private val gattClient = BleGattClient()
        private val mtuNegotiation = MtuNegotiationSession()

        private var sensorDataChar: BluetoothGattCharacteristic? = null
        private var controlPointChar: BluetoothGattCharacteristic? = null
        private var batteryChar: BluetoothGattCharacteristic? = null
        private var deviceEventChar: BluetoothGattCharacteristic? = null

        private var pollJob: Job? = null
        private var deviceEventPollJob: Job? = null
        private var mtuTimeoutJob: Job? = null
        private var lastDeviceEventSeq = 0

        /** Last negotiated MTU (diagnostics). */
        var lastNegotiatedMtu = 0
            private set

        /** Connected device address; null until connected. */
        var remoteId: String? = null
            private set

        /** Batches accepted after dedup (diagnostics). */
        var receivedBatches = 0
            private set

        /** Wire-size rejections (diagnostics). */
        var parseErrors = 0
            private set

        val duplicateReads: Int
            get() = dedupTracker.duplicateSkips
        val estimatedMissedBatches: Int
            get() = dedupTracker.estimatedMissedBatches
        val jitterDroppedFrames: Int
            get() = jitterBuffer.droppedFrames

        // --- Public API -----------------------------------------------------

        override suspend fun connect(deviceId: String?): AppResult<Unit> {
            if (!hasBlePermissions()) {
                return AppResult.failure(AppError.PermissionDenied("BLUETOOTH_SCAN/BLUETOOTH_CONNECT"))
            }
            val adapter =
                bluetoothAdapter()
                    ?: return AppResult.failure(AppError.Unknown("BluetoothAdapter nicht verfuegbar"))
            if (!adapter.isEnabled) {
                return AppResult.failure(AppError.Unknown("Bluetooth ist nicht aktiv"))
            }

            _connectionState.value = SensorConnectionState.CONNECTING
            return try {
                val device =
                    if (deviceId != null) {
                        adapter.getRemoteDevice(deviceId)
                    } else {
                        scanForFlowRep(adapter)
                            ?: return AppResult.failure(
                                AppError.Unknown("FlowRep-Sensor nicht gefunden (15s Scan)"),
                            )
                    }
                connectGatt(device)
            } catch (e: Exception) {
                _connectionState.value = SensorConnectionState.DISCONNECTED
                AppResult.failure(BleErrorMapper.map(e))
            }
        }

        override suspend fun disconnect() {
            pollJob?.cancel()
            pollJob = null
            deviceEventPollJob?.cancel()
            deviceEventPollJob = null
            jitterBuffer.stop()
            runCatching { sendControlCommand(CONTROL_STOP_STREAM) }
            gattClient.close()
            remoteId = null
            _connectedDeviceId.value = null
            _connectionState.value = SensorConnectionState.DISCONNECTED
        }

        /** START_STREAM (0x01). No-op when not connected. */
        override suspend fun startStreaming() {
            sendControlCommand(CONTROL_START_STREAM)
        }

        /** STOP_STREAM (0x02). No-op when not connected. */
        override suspend fun stopStreaming() {
            sendControlCommand(CONTROL_STOP_STREAM)
        }

        /** REQUEST_BATTERY (0x03), then reads the BatteryLevel characteristic. */
        suspend fun readBatteryPercent(): Int {
            sendControlCommand(CONTROL_REQUEST_BATTERY)
            delay(200)
            val value = gattClient.read(batteryChar ?: return 0)
            return value?.firstOrNull()?.toInt()?.and(0xFF) ?: 0
        }

        // --- Scan + connect -------------------------------------------------

        private suspend fun scanForFlowRep(adapter: BluetoothAdapter): BluetoothDevice? {
            val scanner = adapter.bluetoothLeScanner ?: return null
            val filters = DEVICE_NAMES.map { ScanFilter.Builder().setDeviceName(it).build() }
            val settings =
                ScanSettings
                    .Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                    .build()
            return withTimeoutOrNull(SCAN_TIMEOUT_MS) {
                suspendCancellableCoroutine { cont ->
                    val callback =
                        object : ScanCallback() {
                            override fun onScanResult(
                                callbackType: Int,
                                result: ScanResult,
                            ) {
                                val name = result.device.name ?: return
                                if (isFlowRepDeviceName(name) && cont.isActive) cont.resume(result.device)
                            }

                            override fun onScanFailed(errorCode: Int) {
                                if (cont.isActive) cont.resume(null)
                            }
                        }
                    cont.invokeOnCancellation { runCatching { scanner.stopScan(callback) } }
                    scanner.startScan(filters, settings, callback)
                }
            }
        }

        private suspend fun connectGatt(device: BluetoothDevice): AppResult<Unit> =
            suspendCancellableCoroutine { cont ->
                gattClient.onEvent = { event ->
                    when (event) {
                        is GattEvent.Connected -> {
                            remoteId = device.address
                            _connectedDeviceId.value = device.address
                            // MTU before the CCCD sequence (HyperOS quirk).
                            // Testinfra-Plan 5a: Timeout/Retry/Fallback statt
                            // fire-and-forget - siehe startMtuNegotiation().
                            mtuNegotiation.reset()
                            runMtuNegotiation()
                        }

                        is GattEvent.MtuChanged -> {
                            handleMtuChanged(event)
                        }

                        is GattEvent.ServicesDiscovered -> {
                            onServicesDiscovered(device, cont)
                        }

                        is GattEvent.Disconnected -> {
                            mtuTimeoutJob?.cancel()
                            mtuTimeoutJob = null
                            if (cont.isActive) {
                                cont.resume(AppResult.failure(AppError.Unknown("Verbindung getrennt")))
                            }
                            _connectedDeviceId.value = null
                            if (_connectionState.value != SensorConnectionState.DISCONNECTED) {
                                _connectionState.value = SensorConnectionState.DISCONNECTED
                            }
                        }

                        is GattEvent.Notification -> {
                            onDeviceEventBytes(event.value)
                        }
                    }
                }
                cont.invokeOnCancellation { gattClient.close() }
                gattClient.connect(context, device)
            }

        /**
         * MTU-Verhandlung (Testinfra-Plan 5a): der reine Entscheidungskern
         * [MtuNegotiationSession] entscheidet pro Callback/Timeout, hier wird
         * nur ausgefuehrt. Samsung-Quirk (silent failure) wird durch den
         * Timeout abgefangen: kein `onMtuChanged` -> Retry, nie Endlos-Warten.
         */
        private fun runMtuNegotiation() {
            mtuTimeoutJob?.cancel()
            gattClient.requestMtu(MtuNegotiator.REQUEST_MTU)
            mtuTimeoutJob =
                scope.launch {
                    delay(MTU_TIMEOUT_MS)
                    when (val decision = mtuNegotiation.onTimeout()) {
                        is MtuNegotiator.Decision.Request -> runMtuNegotiation()
                        is MtuNegotiator.Decision.UseFallback -> finishMtuNegotiation(decision.mtu)
                        is MtuNegotiator.Decision.Negotiated -> Unit // unreachable
                    }
                }
        }

        private fun handleMtuChanged(event: GattEvent.MtuChanged) {
            mtuTimeoutJob?.cancel()
            mtuTimeoutJob = null
            when (val decision = mtuNegotiation.onMtuChanged(event.mtu, event.status)) {
                is MtuNegotiator.Decision.Request -> runMtuNegotiation()
                is MtuNegotiator.Decision.Negotiated -> finishMtuNegotiation(decision.mtu)
                is MtuNegotiator.Decision.UseFallback -> finishMtuNegotiation(decision.mtu)
            }
        }

        /** Success or exhausted retries: proceed to service discovery. */
        private fun finishMtuNegotiation(mtu: Int) {
            lastNegotiatedMtu = mtu
            gattClient.discoverServices()
        }

        private fun onServicesDiscovered(
            device: BluetoothDevice,
            cont: kotlinx.coroutines.CancellableContinuation<AppResult<Unit>>,
        ) {
            val service = gattClient.getService(SERVICE_UUID)
            if (service == null) {
                if (cont.isActive) cont.resume(AppResult.failure(AppError.Unknown("Service fee0 nicht gefunden")))
                return
            }
            sensorDataChar = service.getCharacteristic(SENSOR_DATA_UUID)
            controlPointChar = service.getCharacteristic(CONTROL_POINT_UUID)
            batteryChar = service.getCharacteristic(BATTERY_LEVEL_UUID)
            deviceEventChar = service.getCharacteristic(DEVICE_EVENT_UUID)
            if (sensorDataChar == null || controlPointChar == null) {
                if (cont.isActive) cont.resume(AppResult.failure(AppError.Unknown("GATT-Charakteristik fehlt")))
                return
            }
            lastDeviceEventSeq = 0
            bindDeviceEventChar()
            gattClient.requestHighConnectionPriority()
            _connectionState.value = SensorConnectionState.CONNECTED
            if (cont.isActive) cont.resume(AppResult.success(Unit))
            // Firmware auto-starts streaming in onConnect; delay the polling
            // start so the first reads don't pull stale data into the baseline.
            scope.launch {
                delay(FIRMWARE_STREAM_DELAY_MS)
                startPolling()
            }
        }

        // --- Polling (HyperOS-safe read() loop) ------------------------------

        private fun startPolling() {
            pollJob?.cancel()
            dedupTracker.reset()
            jitterBuffer.reset()
            jitterBuffer.start()
            _connectionState.value = SensorConnectionState.STREAMING

            pollJob =
                scope.launch {
                    while (isActive && gattClient.isConnected) {
                        try {
                            val bytes = gattClient.read(sensorDataChar) ?: continue
                            if (bytes.size != BleProtocolParser.V1_TOTAL_BYTES &&
                                bytes.size != BleProtocolParser.V2_TOTAL_BYTES
                            ) {
                                parseErrors++
                                continue
                            }
                            val batchTimestamp = BleProtocolParser.timestampOf(bytes)
                            if (dedupTracker.shouldSkip(batchTimestamp)) continue
                            val samples = BleProtocolParser.parseBatch(bytes)
                            receivedBatches++
                            jitterBuffer.addBatch(samples)
                        } catch (e: Exception) {
                            // Transient GATT errors are expected in a tight read
                            // loop; back off briefly instead of killing the stream.
                            Log.d("BleSensorProvider", "polling transient: ${e.message}")
                            delay(POLL_ERROR_BACKOFF_MS)
                        }
                        // No deliberate delay: the GATT round-trip governs the rate.
                    }
                }
        }

        // --- Device events (fee4, M5 button) ---------------------------------

        private fun bindDeviceEventChar() {
            val ch = deviceEventChar ?: return
            // Try notify; always poll as a HyperOS-safe fallback (rare events).
            gattClient.enableNotification(ch)
            deviceEventPollJob?.cancel()
            deviceEventPollJob =
                scope.launch {
                    while (isActive && gattClient.isConnected) {
                        runCatching { gattClient.read(ch)?.let(::onDeviceEventBytes) }
                        delay(DEVICE_EVENT_POLL_MS)
                    }
                }
        }

        private fun onDeviceEventBytes(bytes: ByteArray) {
            if (bytes.size < 2) return
            val seq = bytes[0].toInt() and 0xFF
            val id = bytes[1].toInt() and 0xFF
            if (seq == 0 || seq == lastDeviceEventSeq) return
            lastDeviceEventSeq = seq
            _deviceEvents.tryEmit(
                DeviceEvent(
                    seq = seq,
                    id =
                        com.dropsync.domain.sensor.DeviceEventId
                            .fromWire(id),
                    receivedAtEpochMs = System.currentTimeMillis(),
                ),
            )
        }

        // --- Control point ----------------------------------------------------

        private suspend fun sendControlCommand(command: Int) {
            val ch = controlPointChar ?: return
            gattClient.write(ch, byteArrayOf(command.toByte()))
        }

        // --- Permissions + adapter -------------------------------------------

        private fun hasBlePermissions(): Boolean {
            val needed =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    listOf(android.Manifest.permission.BLUETOOTH_SCAN, android.Manifest.permission.BLUETOOTH_CONNECT)
                } else {
                    listOf(android.Manifest.permission.BLUETOOTH, android.Manifest.permission.BLUETOOTH_ADMIN)
                }
            return needed.all {
                ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
            }
        }

        private fun bluetoothAdapter(): BluetoothAdapter? =
            ContextCompat.getSystemService(context, BluetoothManager::class.java)?.adapter

        companion object {
            /** Preferred BLE advertise name (new firmware / product branding). */
            const val DEVICE_NAME_PREFERRED = "FlowRep"

            /** Legacy advertise name still on many flashed sticks. */
            const val DEVICE_NAME_LEGACY = "GymTracker"

            /** All names accepted when scanning. */
            val DEVICE_NAMES = listOf(DEVICE_NAME_PREFERRED, DEVICE_NAME_LEGACY)

            /** True if [platformName] is a known FlowRep stick advertise name. */
            fun isFlowRepDeviceName(platformName: String): Boolean = DEVICE_NAMES.any { platformName.trim() == it }

            // GATT UUIDs (authoritative: docs/reference/protocol.yaml).
            val SERVICE_UUID: UUID = UUID.fromString("0000fee0-0000-1000-8000-00805f9b34fb")
            val SENSOR_DATA_UUID: UUID = UUID.fromString("0000fee1-0000-1000-8000-00805f9b34fb")
            val CONTROL_POINT_UUID: UUID = UUID.fromString("0000fee2-0000-1000-8000-00805f9b34fb")
            val BATTERY_LEVEL_UUID: UUID = UUID.fromString("0000fee3-0000-1000-8000-00805f9b34fb")
            val DEVICE_EVENT_UUID: UUID = UUID.fromString("0000fee4-0000-1000-8000-00805f9b34fb")

            const val CONTROL_START_STREAM = 0x01
            const val CONTROL_STOP_STREAM = 0x02
            const val CONTROL_REQUEST_BATTERY = 0x03

            private const val SCAN_TIMEOUT_MS = 15_000L
            private const val FIRMWARE_STREAM_DELAY_MS = 600L
            private const val DEVICE_EVENT_POLL_MS = 250L
            private const val POLL_ERROR_BACKOFF_MS = 50L

            /** MTU-Timeout: kein onMtuChanged -> Retry (Samsung silent failure). */
            private const val MTU_TIMEOUT_MS = 1_000L
        }
    }

// --- Serialized GATT client -------------------------------------------------

/** Events the [BleGattClient] surfaces to the provider. */
internal sealed interface GattEvent {
    data object Connected : GattEvent

    data object Disconnected : GattEvent

    data class MtuChanged(
        val mtu: Int,
        val status: Int,
    ) : GattEvent

    data object ServicesDiscovered : GattEvent

    data class Notification(
        val characteristicUuid: UUID,
        val value: ByteArray,
    ) : GattEvent
}

/**
 * Serialized wrapper around [BluetoothGatt] (the Android BLE stack allows one
 * outstanding operation at a time). Operations suspend until the matching
 * callback arrives; a FIFO queue paces them.
 */
@SuppressLint("MissingPermission")
internal class BleGattClient {
    var onEvent: (GattEvent) -> Unit = {}

    private var gatt: BluetoothGatt? = null

    private data class ReadRequest(
        val characteristic: BluetoothGattCharacteristic,
        val cont: kotlinx.coroutines.CancellableContinuation<ByteArray?>,
    )

    private data class WriteRequest(
        val cont: kotlinx.coroutines.CancellableContinuation<Boolean>,
    )

    private val pendingRead = AtomicReference<ReadRequest?>(null)
    private val pendingWrite = AtomicReference<WriteRequest?>(null)
    private val opInFlight = AtomicBoolean(false)
    private val opQueue = java.util.concurrent.ConcurrentLinkedQueue<suspend () -> Unit>()

    val isConnected: Boolean
        get() = gatt != null

    private val callback =
        object : BluetoothGattCallback() {
            override fun onConnectionStateChange(
                g: BluetoothGatt,
                status: Int,
                newState: Int,
            ) {
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> onEvent(GattEvent.Connected)
                    BluetoothProfile.STATE_DISCONNECTED -> onEvent(GattEvent.Disconnected)
                }
            }

            override fun onMtuChanged(
                g: BluetoothGatt,
                mtu: Int,
                status: Int,
            ) {
                onEvent(GattEvent.MtuChanged(mtu, status))
                opDone()
            }

            override fun onServicesDiscovered(
                g: BluetoothGatt,
                status: Int,
            ) {
                onEvent(GattEvent.ServicesDiscovered)
                opDone()
            }

            override fun onCharacteristicRead(
                g: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                value: ByteArray,
                status: Int,
            ) {
                val req = pendingRead.getAndSet(null)
                if (req != null && req.characteristic.uuid == characteristic.uuid && req.cont.isActive) {
                    req.cont.resume(if (status == BluetoothGatt.GATT_SUCCESS) value else null)
                }
                opDone()
            }

            @Deprecated("API < 33")
            override fun onCharacteristicRead(
                g: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                status: Int,
            ) {
                val req = pendingRead.getAndSet(null)
                if (req != null && req.characteristic.uuid == characteristic.uuid && req.cont.isActive) {
                    @Suppress("DEPRECATION")
                    req.cont.resume(if (status == BluetoothGatt.GATT_SUCCESS) characteristic.value else null)
                }
                opDone()
            }

            override fun onCharacteristicWrite(
                g: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                status: Int,
            ) {
                val req = pendingWrite.getAndSet(null)
                if (req != null && req.cont.isActive) req.cont.resume(status == BluetoothGatt.GATT_SUCCESS)
                opDone()
            }

            override fun onDescriptorWrite(
                g: BluetoothGatt,
                descriptor: BluetoothGattDescriptor,
                status: Int,
            ) {
                opDone()
            }

            override fun onCharacteristicChanged(
                g: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                value: ByteArray,
            ) {
                onEvent(GattEvent.Notification(characteristic.uuid, value))
            }

            @Deprecated("API < 33")
            override fun onCharacteristicChanged(
                g: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
            ) {
                @Suppress("DEPRECATION")
                characteristic.value?.let { onEvent(GattEvent.Notification(characteristic.uuid, it)) }
            }
        }

    fun connect(
        context: Context,
        device: BluetoothDevice,
    ) {
        gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
    }

    fun close() {
        gatt?.close()
        gatt = null
        opInFlight.set(false)
        opQueue.clear()
        pendingRead.getAndSet(null)?.cont?.resume(null)
        pendingWrite.getAndSet(null)?.cont?.resume(false)
    }

    fun getService(uuid: UUID) = gatt?.getService(uuid)

    fun requestMtu(mtu: Int) {
        enqueue { gatt?.requestMtu(mtu) }
    }

    fun discoverServices() {
        enqueue { gatt?.discoverServices() }
    }

    fun requestHighConnectionPriority() {
        // Fire-and-forget: no completion callback for connection priority.
        gatt?.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
    }

    fun enableNotification(characteristic: BluetoothGattCharacteristic) {
        val g = gatt ?: return
        runCatching {
            g.setCharacteristicNotification(characteristic, true)
            val cccd = characteristic.getDescriptor(CCCD_UUID) ?: return
            enqueue {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                } else {
                    @Suppress("DEPRECATION")
                    cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    @Suppress("DEPRECATION")
                    g.writeDescriptor(cccd)
                }
            }
        }
    }

    suspend fun read(characteristic: BluetoothGattCharacteristic?): ByteArray? {
        val g = gatt ?: return null
        val ch = characteristic ?: return null
        return kotlinx.coroutines.suspendCancellableCoroutine { cont ->
            enqueue {
                pendingRead.set(ReadRequest(ch, cont))
                val initiated: Boolean =
                    @Suppress("DEPRECATION")
                    g.readCharacteristic(ch)
                if (!initiated) {
                    pendingRead.set(null)
                    if (cont.isActive) cont.resume(null)
                    opDone()
                }
            }
        }
    }

    suspend fun write(
        characteristic: BluetoothGattCharacteristic?,
        value: ByteArray,
    ): Boolean {
        val g = gatt ?: return false
        val ch = characteristic ?: return false
        return kotlinx.coroutines.suspendCancellableCoroutine { cont ->
            enqueue {
                pendingWrite.set(WriteRequest(cont))
                val ok =
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        g.writeCharacteristic(ch, value, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) ==
                            BluetoothStatusCodes.SUCCESS
                    } else {
                        @Suppress("DEPRECATION")
                        ch.value = value
                        @Suppress("DEPRECATION")
                        g.writeCharacteristic(ch)
                    }
                if (!ok) {
                    pendingWrite.set(null)
                    if (cont.isActive) cont.resume(false)
                    opDone()
                }
            }
        }
    }

    private fun enqueue(op: suspend () -> Unit) {
        opQueue.add(op)
        drain()
    }

    private fun drain() {
        if (!opInFlight.compareAndSet(false, true)) return
        val next = opQueue.poll()
        if (next == null) {
            opInFlight.set(false)
            return
        }
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).launch { next() }
    }

    private fun opDone() {
        opInFlight.set(false)
        drain()
    }

    companion object {
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}
