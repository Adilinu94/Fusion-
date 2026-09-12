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
import com.dropsync.domain.sensor.SensorErrorReason
import com.dropsync.domain.sensor.SensorHealth
import com.dropsync.domain.sensor.SensorProvider
import com.dropsync.domain.sensor.SensorSample
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
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
 * - DeviceEvent (fee4, M5 button): notify when available + 250 ms poll.
 *
 * P2-Fix #23 — Sample-Transport: NOTIFY ist die Grundeinstellung,
 * `read()`-Polling nur noch der Fallback.
 *
 * Die Flutter-Implementierung nutzte durchgehend Polling, weil HyperOS bei
 * gesetztem CCCD den zuletzt notifizierten Wert cached und ihn fuer jeden
 * `read()` zurueckgibt. Dieser geraetespezifische Workaround war hier zur
 * Grundeinstellung fuer ALLE Geraete geworden — mit erheblichen Kosten: jeder
 * Sample-Batch braucht einen vollen GATT-Round-Trip, die effektive Rate haengt
 * damit an der Latenz statt am Firmware-Takt (20 ms/Sample). Notify liefert
 * die Batches ungefragt und ohne Anfrage-Overhead.
 *
 * Die Erkennung laeuft empirisch, nicht ueber eine Geraetemodell-Liste (die
 * waere immer unvollstaendig): kommt im Probe-Fenster mindestens eine
 * Notification, bleibt es bei Notify; sonst wird der CCCD wieder abgeschaltet
 * (sonst liefert HyperOS den Cache) und der Poll-Loop uebernimmt.
 *
 * All GATT operations are serialized through [BleGattClient]: the Android BLE
 * stack allows one outstanding operation at a time.
 *
 * Umbauplan Phase 2: every GATT operation has an ID and a timeout, Android
 * return values are checked, late callbacks of old operations are ignored,
 * and ALL failure paths run through the central [cleanupConnection].
 */
@Singleton
@SuppressLint("MissingPermission") // Guarded by hasBlePermissions() before every BLE call.
class BleSensorProvider
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val dispatchers: DispatcherProvider,
    ) : SensorProvider {
        // SupervisorJob (P0-Fix): ohne ihn reisst eine Exception in EINEM
        // Child (JitterBuffer-Tick, Device-Event-Poll, MTU-Timeout) den
        // gesamten Scope ab — inklusive Poll-Loop. Danach ist der Provider
        // still tot: connectionState bleibt auf STREAMING, es kommen aber
        // keine Samples mehr.
        private val scope = CoroutineScope(SupervisorJob() + dispatchers.default)

        private val _connectionState = MutableStateFlow(SensorConnectionState.DISCONNECTED)
        override val connectionState: StateFlow<SensorConnectionState> = _connectionState.asStateFlow()

        private val _samples = MutableSharedFlow<SensorSample>(extraBufferCapacity = 64)
        override val samples: SharedFlow<SensorSample> = _samples.asSharedFlow()

        private val _deviceEvents = MutableSharedFlow<DeviceEvent>(extraBufferCapacity = 8)
        override val deviceEvents: SharedFlow<DeviceEvent> = _deviceEvents.asSharedFlow()

        private val _connectedDeviceId = MutableStateFlow<String?>(null)
        override val connectedDeviceId: StateFlow<String?> = _connectedDeviceId.asStateFlow()

        private val _health = MutableStateFlow(SensorHealth())
        override val health: StateFlow<SensorHealth> = _health.asStateFlow()

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

        /** P2-Fix #23: prueft, ob Notifications ankommen (sonst Poll-Fallback). */
        private var notifyProbeJob: Job? = null

        /** Batches, die per Notification eintrafen (Transport-Erkennung). */
        private var notifiedBatches = 0

        private var lastDeviceEventSeq = 0

        /**
         * P2-Fix #23: true, sobald der Notify-Transport bestaetigt ist.
         * Diagnose-Sichtbarkeit fuer die Sensor-Health-Anzeige.
         */
        var usingNotifyTransport = false
            private set

        /** Aufeinanderfolgende Protokollfehler (Cleanup-Grund). */
        private var consecutiveProtocolErrors = 0

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
            // P3-Fix #27: nur noch klassifizierte Gruende nach oben, keine
            // fertig formulierten deutschen Saetze (die Datenschicht kennt
            // keine Locale).
            val adapter =
                bluetoothAdapter()
                    ?: return AppResult.failure(BleErrorMapper.error(SensorErrorReason.ADAPTER_UNAVAILABLE))
            if (!adapter.isEnabled) {
                return AppResult.failure(BleErrorMapper.error(SensorErrorReason.BLUETOOTH_OFF))
            }

            _connectionState.value = SensorConnectionState.CONNECTING
            return try {
                val device =
                    if (deviceId != null) {
                        adapter.getRemoteDevice(deviceId)
                    } else {
                        scanForFlowRep(adapter)
                            ?: run {
                                cleanupConnection(DisconnectReason.SCAN_FAILED)
                                return AppResult.failure(
                                    BleErrorMapper.error(SensorErrorReason.NOT_FOUND),
                                )
                            }
                    }
                connectGatt(device)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                cleanupConnection(DisconnectReason.CONNECT_FAILED)
                AppResult.failure(BleErrorMapper.map(e))
            }
        }

        override suspend fun disconnect() {
            runCatching { sendControlCommand(CONTROL_STOP_STREAM) }
            cleanupConnection(DisconnectReason.USER_REQUEST)
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

        /**
         * Umbauplan Phase 2.3: der Scan wird bei Erfolg, Fehler UND
         * Cancellation/Timeout immer mit stopScan() beendet.
         */
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
                                if (isFlowRepDeviceName(name) && cont.isActive) {
                                    // Phase 2.3: scan ends on success.
                                    runCatching { scanner.stopScan(this) }
                                    cont.resume(result.device)
                                }
                            }

                            override fun onScanFailed(errorCode: Int) {
                                runCatching { scanner.stopScan(this) }
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
                            onServicesDiscovered(cont)
                        }

                        is GattEvent.Disconnected -> {
                            // P0-Fix: Remote-Disconnect raeumt ALLES auf
                            // (Jobs, Jitterbuffer, GATT, Zustaende).
                            if (cont.isActive) {
                                cont.resume(
                                    AppResult.failure(BleErrorMapper.error(SensorErrorReason.DISCONNECTED)),
                                )
                            }
                            scope.launch { cleanupConnection(DisconnectReason.REMOTE_DISCONNECT) }
                        }

                        is GattEvent.Notification -> {
                            onNotification(event)
                        }

                        // Umbauplan Phase 2.1: GATT-Operation haengt (kein
                        // Callback) - Verbindung als fehlerhaft behandeln.
                        is GattEvent.OperationTimedOut -> {
                            if (cont.isActive) {
                                cont.resume(
                                    AppResult.failure(BleErrorMapper.error(SensorErrorReason.TIMEOUT)),
                                )
                            }
                            scope.launch { cleanupConnection(DisconnectReason.GATT_TIMEOUT) }
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
         * Umbauplan Phase 2.1: der GattClient-Operationstimeout gibt die
         * Queue zusaetzlich frei, falls gar kein Callback kommt.
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

        private fun onServicesDiscovered(cont: kotlinx.coroutines.CancellableContinuation<AppResult<Unit>>) {
            val service = gattClient.getService(SERVICE_UUID)
            if (service == null) {
                if (cont.isActive) {
                    cont.resume(AppResult.failure(BleErrorMapper.error(SensorErrorReason.SERVICE_MISSING)))
                }
                scope.launch { cleanupConnection(DisconnectReason.SERVICE_MISSING) }
                return
            }
            sensorDataChar = service.getCharacteristic(SENSOR_DATA_UUID)
            controlPointChar = service.getCharacteristic(CONTROL_POINT_UUID)
            batteryChar = service.getCharacteristic(BATTERY_LEVEL_UUID)
            deviceEventChar = service.getCharacteristic(DEVICE_EVENT_UUID)
            if (sensorDataChar == null || controlPointChar == null) {
                if (cont.isActive) {
                    cont.resume(AppResult.failure(BleErrorMapper.error(SensorErrorReason.SERVICE_MISSING)))
                }
                scope.launch { cleanupConnection(DisconnectReason.CHARACTERISTIC_MISSING) }
                return
            }
            lastDeviceEventSeq = 0
            bindDeviceEventChar()
            gattClient.requestHighConnectionPriority()
            _connectionState.value = SensorConnectionState.CONNECTED
            if (cont.isActive) cont.resume(AppResult.success(Unit))
            // Firmware auto-starts streaming in connect; delay the streaming
            // start so the first frames don't pull stale data into the baseline.
            scope.launch {
                delay(FIRMWARE_STREAM_DELAY_MS)
                startStreamingTransport()
            }
        }

        // --- Sample transport: Notify bevorzugt, Polling als Fallback ---------

        /**
         * P2-Fix #23: startet den Sample-Transport mit NOTIFY und faellt nur
         * dann auf `read()`-Polling zurueck, wenn keine Notification eintrifft.
         *
         * Warum die Umstellung: der Kommentar oben ("NO CCCD ... polls read()
         * at ~30 Hz") beschreibt einen HyperOS-spezifischen Workaround, der
         * hier zur Grundeinstellung fuer ALLE Geraete geworden war. Das kostet
         * auf normalen Android-Stacks erheblich: jeder Sample-Batch braucht
         * einen vollen GATT-Round-Trip (Request + Response), waehrend Notify
         * die Batches ungefragt und ohne Anfrage-Overhead liefert. Die
         * effektive Rate haengt beim Polling an der Round-Trip-Latenz statt am
         * Firmware-Takt — genau die Ursache, weshalb die Pipeline reale Raten
         * deutlich unter den nominalen 50 Hz sieht (siehe #21).
         *
         * Die Erkennung ist bewusst empirisch statt per Geraetemodell-Liste:
         * eine Liste ist immer unvollstaendig und veraltet. Kommt innerhalb von
         * [NOTIFY_PROBE_MS] mindestens ein Batch per Notification, bleibt es
         * bei Notify; sonst laeuft der bisherige Poll-Pfad an.
         */
        private fun startStreamingTransport() {
            notifyProbeJob?.cancel()
            pollJob?.cancel()
            dedupTracker.reset()
            consecutiveProtocolErrors = 0
            notifiedBatches = 0
            usingNotifyTransport = false
            jitterBuffer.reset()
            jitterBuffer.start()
            _connectionState.value = SensorConnectionState.STREAMING

            gattClient.enableNotification(sensorDataChar ?: return)
            notifyProbeJob =
                scope.launch {
                    delay(NOTIFY_PROBE_MS)
                    if (notifiedBatches >= MIN_NOTIFY_PROBE_BATCHES) {
                        usingNotifyTransport = true
                        Log.d(LOG_TAG, "Sample-Transport: NOTIFY ($notifiedBatches Batches im Probe-Fenster)")
                    } else {
                        // HyperOS-Fallback: die Notification bleibt aus (oder
                        // liefert nur den gecachten Wert). Notify wird
                        // abgeschaltet, damit der Stack echte Over-the-Air-Reads
                        // ausfuehrt statt den zuletzt notifizierten Wert zu
                        // wiederholen.
                        Log.w(LOG_TAG, "Sample-Transport: keine Notifications, Fallback auf read()-Polling")
                        gattClient.disableNotification(sensorDataChar)
                        startPolling()
                    }
                }
        }

        /**
         * Verteilt eine Notification auf den passenden Kanal. Die
         * Sample-Charakteristik (fee1) liefert Sample-Batches, fee4 die
         * Geraete-Events (M5-Taste).
         */
        private fun onNotification(event: GattEvent.Notification) {
            when (event.characteristicUuid) {
                SENSOR_DATA_UUID -> {
                    notifiedBatches++
                    handleSensorBatch(event.value)
                }

                DEVICE_EVENT_UUID -> {
                    onDeviceEventBytes(event.value)
                }

                else -> {
                    Unit
                }
            }
        }

        /**
         * Verarbeitet einen rohen Sample-Batch. Gemeinsamer Pfad fuer Notify
         * und Polling — Dedup, Parse-Fehlerzaehlung und Health muessen in
         * beiden Transportwegen identisch sein.
         *
         * Wirft [BleProtocolException] weiter an den Aufrufer: der Poll-Loop
         * behandelt eine Fehlerserie als Verbindungsabbruch, der
         * Notification-Pfad zaehlt nur.
         */
        private fun handleSensorBatch(bytes: ByteArray): Boolean {
            if (bytes.size != BleProtocolParser.V1_TOTAL_BYTES &&
                bytes.size != BleProtocolParser.V2_TOTAL_BYTES
            ) {
                parseErrors++
                updateHealth()
                return false
            }
            val batchTimestamp = BleProtocolParser.timestampOf(bytes)
            if (dedupTracker.shouldSkip(batchTimestamp)) {
                updateHealth()
                return false
            }
            val samples = BleProtocolParser.parseBatch(bytes)
            consecutiveProtocolErrors = 0
            receivedBatches++
            jitterBuffer.addBatch(samples)
            updateHealth()
            return true
        }

        // --- Polling (HyperOS-safe read() loop) ------------------------------

        /**
         * P2-Fix #23: NUR noch Fallback. Wird von [startStreamingTransport]
         * gestartet, wenn im Probe-Fenster keine Notification eintraf.
         *
         * Der Loop haelt bewusst kein `delay` zwischen den Reads: die
         * GATT-Round-Trip-Zeit bestimmt die Rate. Genau das ist der Grund,
         * warum dieser Pfad nur noch der Notfallweg ist.
         */
        private fun startPolling() {
            pollJob?.cancel()
            jitterBuffer.reset()
            jitterBuffer.start()
            _connectionState.value = SensorConnectionState.STREAMING

            pollJob =
                scope.launch {
                    while (isActive && gattClient.isConnected) {
                        try {
                            val bytes = gattClient.read(sensorDataChar) ?: continue
                            handleSensorBatch(bytes)
                        } catch (e: BleProtocolException) {
                            // Umbauplan Phase 2.4: unbekannte Versionen oder
                            // kaputte Pakete sind Sensorfehler - bei einer
                            // Serie davon ist die Verbindung unbrauchbar.
                            parseErrors++
                            consecutiveProtocolErrors++
                            if (consecutiveProtocolErrors >= MAX_CONSECUTIVE_PROTOCOL_ERRORS) {
                                Log.w(LOG_TAG, "Zu viele Protokollfehler, Verbindung wird beendet")
                                scope.launch { cleanupConnection(DisconnectReason.PROTOCOL_ERROR) }
                                return@launch
                            }
                            updateHealth()
                            delay(POLL_ERROR_BACKOFF_MS)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            // Transient GATT errors are expected in a tight read
                            // loop; back off briefly instead of killing the stream.
                            Log.d(LOG_TAG, "polling transient: ${e.message}")
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

        // --- Cleanup (Umbauplan Phase 2.2) -----------------------------------

        /** Warum die Verbindung beendet wurde (zentraler Cleanup). */
        enum class DisconnectReason {
            USER_REQUEST,
            REMOTE_DISCONNECT,
            SCAN_FAILED,
            CONNECT_FAILED,
            SERVICE_MISSING,
            CHARACTERISTIC_MISSING,
            GATT_TIMEOUT,
            PROTOCOL_ERROR,
        }

        /**
         * Umbauplan Phase 2.2: EINE zentrale Cleanup-Funktion fuer alle
         * Fehler- und Endpfade. Idempotent; darf aus jedem Zustand laufen.
         * Setzt garantiert Jobs, Queue, Buffer, Characteristics, GATT,
         * Device-ID und den oeffentlichen Zustand zurueck.
         */
        private suspend fun cleanupConnection(reason: DisconnectReason) {
            pollJob?.cancel()
            pollJob = null
            notifyProbeJob?.cancel()
            notifyProbeJob = null
            deviceEventPollJob?.cancel()
            deviceEventPollJob = null
            mtuTimeoutJob?.cancel()
            mtuTimeoutJob = null
            consecutiveProtocolErrors = 0
            notifiedBatches = 0
            usingNotifyTransport = false
            sensorDataChar = null
            controlPointChar = null
            batteryChar = null
            deviceEventChar = null
            lastDeviceEventSeq = 0
            jitterBuffer.stop()
            dedupTracker.reset()
            gattClient.close()
            remoteId = null
            _connectedDeviceId.value = null
            _connectionState.value = SensorConnectionState.DISCONNECTED
            updateHealth()
            Log.d(LOG_TAG, "cleanupConnection($reason)")
        }

        /** Umbauplan Phase 3: Paketverlust und Gaps als Health-Flow. */
        private fun updateHealth() {
            _health.value =
                SensorHealth(
                    connectionState = _connectionState.value,
                    receivedBatches = receivedBatches.toLong(),
                    duplicateBatches = dedupTracker.duplicateSkips.toLong(),
                    missedBatches = dedupTracker.estimatedMissedBatches.toLong(),
                    parseErrors = parseErrors.toLong(),
                    jitterBufferDrops = jitterBuffer.droppedFrames.toLong(),
                    largestGapMs = dedupTracker.largestGapMs,
                    recentPacketLossRate = dedupTracker.recentPacketLossRate,
                )
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

            private const val LOG_TAG = "BleSensorProvider"

            /**
             * P2-Fix #23: Fenster, in dem mindestens eine Notification
             * eintreffen muss. 800 ms sind bei 12.5 Batches/s (80 ms Intervall)
             * grosszuegig — reicht auch bei langsamer Verbindungsaushandlung,
             * bleibt aber unter der Zeit, die der Nutzer vor dem ersten Satz
             * braucht.
             */
            private const val NOTIFY_PROBE_MS = 800L

            /** So viele Notify-Batches gelten als bestaetigter Transport. */
            private const val MIN_NOTIFY_PROBE_BATCHES = 2

            /** MTU-Timeout: kein onMtuChanged -> Retry (Samsung silent failure). */
            private const val MTU_TIMEOUT_MS = 1_000L

            /** Serie von Protokollfehlern, nach der die Verbindung endet. */
            private const val MAX_CONSECUTIVE_PROTOCOL_ERRORS = 10
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

    /** Umbauplan Phase 2.1: eine Operation hat nie einen Callback geliefert. */
    data object OperationTimedOut : GattEvent
}

/** Umbauplan Phase 2.1: Typ einer serialisierten GATT-Operation. */
internal enum class GattOperationType {
    MTU,
    DISCOVER,
    DESCRIPTOR,
    READ,
    WRITE,
}

/**
 * Serialized wrapper around [BluetoothGatt] (the Android BLE stack allows one
 * outstanding operation at a time). Operations suspend until the matching
 * callback arrives; a FIFO queue paces them.
 *
 * Umbauplan Phase 2.1:
 * - Jede Operation hat eine ID und einen Typ.
 * - Android-Rueckgabewerte werden geprueft (false = sofort opDone).
 * - Pro Operation laeuft ein Timeout; danach wird die Queue freigegeben.
 * - Spaete Callbacks fremder Typen werden in [opDone] ignoriert.
 * - Kein frei erzeugter CoroutineScope pro Operation; ein Scope fuer alles.
 * - Haengt ein READ oder DISCOVER endgueltig (kein Callback), meldet der
 *   Client [GattEvent.OperationTimedOut]; der Provider raeumt dann die
 *   Verbindung zentral auf.
 */
@SuppressLint("MissingPermission")
internal class BleGattClient {
    var onEvent: (GattEvent) -> Unit = {}

    private var gatt: BluetoothGatt? = null

    private var nextOpId = 0L

    private data class ActiveOp(
        val id: Long,
        val type: GattOperationType,
    )

    private val activeOp = AtomicReference<ActiveOp?>(null)

    private val scope =
        CoroutineScope(
            kotlinx.coroutines.CoroutineName("BleGattClient") +
                kotlinx.coroutines.SupervisorJob() +
                kotlinx.coroutines.Dispatchers.Default,
        )

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
    private val opQueue =
        java.util.concurrent.ConcurrentLinkedQueue<Pair<GattOperationType, suspend () -> Unit>>()

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
                opDone(GattOperationType.MTU)
            }

            override fun onServicesDiscovered(
                g: BluetoothGatt,
                status: Int,
            ) {
                onEvent(GattEvent.ServicesDiscovered)
                opDone(GattOperationType.DISCOVER)
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
                opDone(GattOperationType.READ)
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
                opDone(GattOperationType.READ)
            }

            override fun onCharacteristicWrite(
                g: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                status: Int,
            ) {
                val req = pendingWrite.getAndSet(null)
                if (req != null && req.cont.isActive) req.cont.resume(status == BluetoothGatt.GATT_SUCCESS)
                opDone(GattOperationType.WRITE)
            }

            override fun onDescriptorWrite(
                g: BluetoothGatt,
                descriptor: BluetoothGattDescriptor,
                status: Int,
            ) {
                opDone(GattOperationType.DESCRIPTOR)
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

    /**
     * Umbauplan Phase 2.2: zentraler Cleanup. Beendet Pending-Reads/Writes,
     * leert die Queue, schliesst GATT und setzt alle Zustands-Bits zurueck.
     */
    fun close() {
        gatt?.close()
        gatt = null
        activeOp.set(null)
        opInFlight.set(false)
        opQueue.clear()
        pendingRead.getAndSet(null)?.cont?.resume(null)
        pendingWrite.getAndSet(null)?.cont?.resume(false)
    }

    fun getService(uuid: UUID) = gatt?.getService(uuid)

    fun requestMtu(mtu: Int) {
        enqueue(GattOperationType.MTU) {
            if (gatt?.requestMtu(mtu) != true) opDone(GattOperationType.MTU)
        }
    }

    fun discoverServices() {
        enqueue(GattOperationType.DISCOVER) {
            if (gatt?.discoverServices() != true) {
                // Android hat den Aufruf abgelehnt: es kommt kein Callback.
                opDone(GattOperationType.DISCOVER)
                onEvent(GattEvent.OperationTimedOut)
            }
        }
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
            enqueue(GattOperationType.DESCRIPTOR) {
                val ok =
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) ==
                            BluetoothStatusCodes.SUCCESS
                    } else {
                        @Suppress("DEPRECATION")
                        cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        @Suppress("DEPRECATION")
                        g.writeDescriptor(cccd)
                    }
                if (!ok) opDone(GattOperationType.DESCRIPTOR)
            }
        }
    }

    /**
     * P2-Fix #23: schaltet Notifications wieder ab.
     *
     * Notwendig fuer den HyperOS-Fallback: HyperOS liefert bei GESETZTEM CCCD
     * fuer jeden `read()` den zuletzt notifizierten (gecachten) Wert zurueck
     * statt einen echten Over-the-Air-Read auszufuehren. Ohne dieses
     * Zurueckschalten wuerde der Poll-Fallback denselben Batch endlos
     * wiederholen und der Dedup-Tracker alles verwerfen.
     */
    fun disableNotification(characteristic: BluetoothGattCharacteristic?) {
        val g = gatt ?: return
        val ch = characteristic ?: return
        runCatching {
            val cccd = ch.getDescriptor(CCCD_UUID)
            if (cccd != null) {
                enqueue(GattOperationType.DESCRIPTOR) {
                    val ok =
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            g.writeDescriptor(cccd, BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE) ==
                                BluetoothStatusCodes.SUCCESS
                        } else {
                            @Suppress("DEPRECATION")
                            cccd.value = BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
                            @Suppress("DEPRECATION")
                            g.writeDescriptor(cccd)
                        }
                    if (!ok) opDone(GattOperationType.DESCRIPTOR)
                }
            }
            g.setCharacteristicNotification(ch, false)
        }
    }

    suspend fun read(characteristic: BluetoothGattCharacteristic?): ByteArray? {
        val g = gatt ?: return null
        val ch = characteristic ?: return null
        return suspendCancellableCoroutine { cont ->
            enqueue(GattOperationType.READ) {
                pendingRead.set(ReadRequest(ch, cont))
                val initiated: Boolean =
                    @Suppress("DEPRECATION")
                    g.readCharacteristic(ch)
                if (!initiated) {
                    pendingRead.set(null)
                    if (cont.isActive) cont.resume(null)
                    opDone(GattOperationType.READ)
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
        return suspendCancellableCoroutine { cont ->
            enqueue(GattOperationType.WRITE) {
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
                    opDone(GattOperationType.WRITE)
                }
            }
        }
    }

    private fun enqueue(
        type: GattOperationType,
        op: suspend () -> Unit,
    ) {
        opQueue.add(type to op)
        drain()
    }

    private fun drain() {
        if (!opInFlight.compareAndSet(false, true)) return
        val (type, op) =
            opQueue.poll() ?: run {
                opInFlight.set(false)
                return
            }
        scope.launch {
            val opId = ++nextOpId
            activeOp.set(ActiveOp(opId, type))
            // Umbauplan Phase 2.1: Timeout pro Operation. Feuert der
            // Callback nicht, wird die Queue freigegeben; READ/DISCOVER
            // melden den Haenger an den Provider (zentraler Cleanup).
            val timeoutJob =
                scope.launch {
                    delay(OPERATION_TIMEOUT_MS)
                    if (activeOp.get()?.id == opId) {
                        when (type) {
                            GattOperationType.READ -> pendingRead.getAndSet(null)?.cont?.resume(null)
                            GattOperationType.WRITE -> pendingWrite.getAndSet(null)?.cont?.resume(false)
                            else -> Unit
                        }
                        opDone(opId)
                        if (type == GattOperationType.READ || type == GattOperationType.DISCOVER) {
                            onEvent(GattEvent.OperationTimedOut)
                        }
                    }
                }
            try {
                op()
            } finally {
                timeoutJob.cancel()
            }
        }
    }

    /**
     * Umbauplan Phase 2.1: spaete Callbacks alter Operationen duerfen weder
     * die Continuation einer neuen Operation bedienen noch deren Queue-Sperre
     * freigeben: freigegeben wird nur, wenn der Callback-Typ zur aktuell
     * aktiven Operation passt.
     */
    private fun opDone(type: GattOperationType) {
        val active = activeOp.get() ?: return
        if (active.type != type) return
        opDone(active.id)
    }

    private fun opDone(opId: Long) {
        val active = activeOp.get() ?: return
        if (active.id != opId) return
        if (!activeOp.compareAndSet(active, null)) return
        opInFlight.set(false)
        drain()
    }

    companion object {
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        /** Kein GATT-Aufruf darf die Queue dauerhaft blockieren. */
        const val OPERATION_TIMEOUT_MS = 3_000L
    }
}
