package com.dropsync.data.sensor

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothDevice
import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.UUID

/**
 * Skriptbarer Transport-Double: zeichnet Aufrufe auf, liefert
 * konfigurierbare Ergebnisse und feuert GATT-Ereignisse manuell — der
 * BLE-Stack selbst laeuft in Tests nie.
 */
internal class FakeGattTransport : GattTransport {
    var connected = true
    var mtuResult = true
    var discoverResult = true
    var readResult = true
    var writeResult = true
    var descriptorResult = true

    var connectCalls = 0
    var closeCalls = 0
    var highPriorityCalls = 0
    val mtuRequests = mutableListOf<Int>()
    val writtenValues = mutableListOf<ByteArray>()
    val notificationCalls = mutableListOf<Pair<BluetoothGattCharacteristic, Boolean>>()
    val descriptorCalls = mutableListOf<Pair<BluetoothGattDescriptor, Boolean>>()

    private var listener: GattTransportListener? = null

    override fun setListener(listener: GattTransportListener) {
        this.listener = listener
    }

    override fun connect(
        context: Context,
        device: BluetoothDevice,
    ) {
        connectCalls++
    }

    override fun close() {
        closeCalls++
        connected = false
    }

    override val isConnected: Boolean
        get() = connected

    override fun getService(uuid: UUID): BluetoothGattService? = null

    override fun requestMtu(mtu: Int): Boolean {
        mtuRequests += mtu
        return mtuResult
    }

    override fun discoverServices(): Boolean = discoverResult

    override fun requestHighPriority() {
        highPriorityCalls++
    }

    override fun setCharacteristicNotification(
        characteristic: BluetoothGattCharacteristic,
        enable: Boolean,
    ): Boolean {
        notificationCalls += characteristic to enable
        return true
    }

    override fun writeDescriptor(
        descriptor: BluetoothGattDescriptor,
        enable: Boolean,
    ): Boolean {
        descriptorCalls += descriptor to enable
        return descriptorResult
    }

    override fun readCharacteristic(characteristic: BluetoothGattCharacteristic): Boolean = readResult

    override fun writeCharacteristic(
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray,
    ): Boolean {
        writtenValues += value
        return writeResult
    }

    fun fireConnected() = listener?.onConnected()

    fun fireDisconnected() = listener?.onDisconnected()

    fun fireMtu(
        mtu: Int,
        status: Int = BluetoothGatt.GATT_SUCCESS,
    ) = listener?.onMtuChanged(mtu, status)

    fun fireDiscovered() = listener?.onServicesDiscovered()

    fun fireRead(
        uuid: UUID,
        value: ByteArray?,
        status: Int = BluetoothGatt.GATT_SUCCESS,
    ) = listener?.onCharacteristicRead(uuid, value, status)

    fun fireWrite(status: Int = BluetoothGatt.GATT_SUCCESS) = listener?.onCharacteristicWrite(status)

    fun fireDescriptorWritten() = listener?.onDescriptorWritten()

    fun fireNotification(
        uuid: UUID,
        value: ByteArray,
    ) = listener?.onNotification(uuid, value)
}

/**
 * Serialisierter GATT-Client (Umbauplan Phase 2.1/2.2): Queue-Disziplin,
 * Timeout-Freigabe, Pending-Matching und Stale-Schutz — gegen den
 * Fake-Transport, ohne BLE-Stack.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
@OptIn(ExperimentalCoroutinesApi::class)
class BleGattClientTest {
    private val transport = FakeGattTransport()
    private val events = mutableListOf<GattEvent>()

    private fun client(scope: kotlinx.coroutines.CoroutineScope): BleGattClient =
        BleGattClient(transport = transport, scope = scope).also { it.onEvent = events::add }

    private fun characteristic(uuid: UUID = UUID.randomUUID()): BluetoothGattCharacteristic {
        val ch =
            BluetoothGattCharacteristic(
                uuid,
                BluetoothGattCharacteristic.PROPERTY_READ or
                    BluetoothGattCharacteristic.PROPERTY_WRITE or
                    BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                BluetoothGattCharacteristic.PERMISSION_READ or BluetoothGattCharacteristic.PERMISSION_WRITE,
            )
        ch.addDescriptor(
            BluetoothGattDescriptor(BleGattClient.CCCD_UUID, BluetoothGattCharacteristic.PERMISSION_WRITE),
        )
        return ch
    }

    // --- Verbindung -------------------------------------------------------

    @Test
    fun `Connected und Disconnected werden als Events gemeldet`() =
        runTest {
            client(this)
            transport.fireConnected()
            transport.fireDisconnected()

            assertEquals(listOf(GattEvent.Connected, GattEvent.Disconnected), events)
        }

    @Test
    fun `Notification traegt UUID und Wert durch`() =
        runTest {
            client(this)
            val uuid = UUID.randomUUID()
            val value = byteArrayOf(1, 2, 3)

            transport.fireNotification(uuid, value)

            val event = events.single() as GattEvent.Notification
            assertEquals(uuid, event.characteristicUuid)
            assertArrayEquals(value, event.value)
        }

    @Test
    fun `requestHighConnectionPriority ist Fire-and-forget`() =
        runTest {
            client(this).requestHighConnectionPriority()

            assertEquals(1, transport.highPriorityCalls)
            assertTrue(events.isEmpty())
        }

    // --- MTU ---------------------------------------------------------------

    @Test
    fun `MTU-Erfolg meldet MtuChanged und gibt die Queue frei`() =
        runTest {
            val client = client(this)
            client.requestMtu(185)
            client.requestMtu(186)
            // Nur aktuell Faelliges pumpen: advanceUntilIdle wuerde die
            // virtuelle Zeit ueber den Op-Timeout (3 s) schieben.
            runCurrent()

            transport.fireMtu(185)
            runCurrent()

            assertEquals(listOf(185, 186), transport.mtuRequests)
            assertEquals(listOf(GattEvent.MtuChanged(185, BluetoothGatt.GATT_SUCCESS)), events)
        }

    @Test
    fun `MTU-Ablehnung gibt die Queue sofort frei`() =
        runTest {
            transport.mtuResult = false
            val client = client(this)
            client.requestMtu(185)
            client.requestMtu(186)
            advanceUntilIdle()

            assertEquals(listOf(185, 186), transport.mtuRequests)
            assertTrue("Abgelehnte Ops melden kein Event", events.isEmpty())
        }

    // --- Discover ----------------------------------------------------------

    @Test
    fun `Discover-Ablehnung meldet OperationTimedOut`() =
        runTest {
            transport.discoverResult = false
            client(this).discoverServices()
            advanceUntilIdle()

            assertEquals(listOf(GattEvent.OperationTimedOut), events)
        }

    @Test
    fun `Discover-Erfolg meldet ServicesDiscovered`() =
        runTest {
            client(this).discoverServices()
            runCurrent()
            transport.fireDiscovered()
            advanceUntilIdle()

            assertEquals(listOf(GattEvent.ServicesDiscovered), events)
        }

    // --- Read ----------------------------------------------------------------

    @Test
    fun `read liefert den Wert bei Erfolg`() =
        runTest {
            val client = client(this)
            val ch = characteristic()
            val value = byteArrayOf(9, 8, 7)

            val deferred = async { client.read(ch) }
            runCurrent()
            transport.fireRead(ch.uuid, value)
            advanceUntilIdle()

            assertArrayEquals(value, deferred.await())
        }

    @Test
    fun `read mit Fehlerstatus liefert null`() =
        runTest {
            val client = client(this)
            val ch = characteristic()

            val deferred = async { client.read(ch) }
            runCurrent()
            transport.fireRead(ch.uuid, null, status = 133)
            advanceUntilIdle()

            assertNull(deferred.await())
        }

    @Test
    fun `read mit fremder UUID bleibt haengen, richtige loest auf`() =
        runTest {
            val client = client(this)
            val ch = characteristic()

            val deferred = async { client.read(ch) }
            runCurrent()
            transport.fireRead(UUID.randomUUID(), byteArrayOf(1))
            runCurrent()
            assertFalse("Fremder Callback darf den Read nicht aufloesen", deferred.isCompleted)

            transport.fireRead(ch.uuid, byteArrayOf(2))
            advanceUntilIdle()
            assertArrayEquals(byteArrayOf(2), deferred.await())
        }

    @Test
    fun `read ohne Verbindung liefert sofort null`() =
        runTest {
            transport.connected = false

            assertNull(client(this).read(characteristic()))
        }

    @Test
    fun `read-Timeout meldet OperationTimedOut und liefert null`() =
        runTest {
            val client = client(this)
            val deferred = async { client.read(characteristic()) }
            runCurrent()

            advanceTimeBy(BleGattClient.OPERATION_TIMEOUT_MS + 1)
            runCurrent()

            assertNull(deferred.await())
            assertEquals(listOf(GattEvent.OperationTimedOut), events)
        }

    // --- Write ---------------------------------------------------------------

    @Test
    fun `write liefert true bei Erfolg und false bei Fehlerstatus`() =
        runTest {
            val client = client(this)
            val ch = characteristic()

            val ok = async { client.write(ch, byteArrayOf(1)) }
            runCurrent()
            transport.fireWrite()
            advanceUntilIdle()
            assertTrue(ok.await())

            val failed = async { client.write(ch, byteArrayOf(2)) }
            runCurrent()
            transport.fireWrite(status = 133)
            advanceUntilIdle()
            assertFalse(failed.await())

            assertEquals(listOf(byteArrayOf(1), byteArrayOf(2)).map { it.toList() }, transport.writtenValues.map { it.toList() })
        }

    @Test
    fun `write-Timeout liefert false ohne Timeout-Event`() =
        runTest {
            val client = client(this)
            val deferred = async { client.write(characteristic(), byteArrayOf(1)) }
            runCurrent()

            advanceTimeBy(BleGattClient.OPERATION_TIMEOUT_MS + 1)
            runCurrent()

            assertFalse(deferred.await())
            assertTrue("Nur READ/DISCOVER melden Haenger an den Provider", events.isEmpty())
        }

    // --- Queue-Disziplin -------------------------------------------------------

    @Test
    fun `fremder Callback-Typ gibt die Queue nicht frei`() =
        runTest {
            val client = client(this)
            val ch = characteristic()

            val deferred = async { client.read(ch) }
            runCurrent()
            // Stale: DESCRIPTOR-Callback waehrend READ aktiv ist.
            transport.fireDescriptorWritten()
            client.requestMtu(185)
            runCurrent()

            assertFalse("Fremder Typ darf die Queue nicht freigeben", deferred.isCompleted)
            assertTrue("MTU wartet hinter dem aktiven Read", transport.mtuRequests.isEmpty())

            transport.fireRead(ch.uuid, byteArrayOf(5))
            advanceUntilIdle()

            assertArrayEquals(byteArrayOf(5), deferred.await())
            assertEquals("Nach Read-Aufloesung laeuft die Queue weiter", listOf(185), transport.mtuRequests)
        }

    // --- Notifications ---------------------------------------------------------

    @Test
    fun `enableNotification setzt CCCD und nimmt nichts vorweg`() =
        runTest {
            val client = client(this)
            val ch = characteristic()

            client.enableNotification(ch)
            runCurrent()

            assertEquals(listOf(ch to true), transport.notificationCalls)
            val cccd = ch.getDescriptor(BleGattClient.CCCD_UUID)
            assertEquals(listOf(cccd to true), transport.descriptorCalls)
            assertTrue(events.isEmpty())

            transport.fireDescriptorWritten()
            runCurrent()
            assertTrue(events.isEmpty())
        }

    @Test
    fun `enableNotification ohne CCCD schreibt keinen Descriptor`() =
        runTest {
            val ch = BluetoothGattCharacteristic(UUID.randomUUID(), BluetoothGattCharacteristic.PROPERTY_NOTIFY, 0)

            client(this).enableNotification(ch)
            advanceUntilIdle()

            assertEquals(1, transport.notificationCalls.size)
            assertTrue(transport.descriptorCalls.isEmpty())
        }

    @Test
    fun `enableNotification ohne Verbindung tut nichts`() =
        runTest {
            transport.connected = false

            client(this).enableNotification(characteristic())
            advanceUntilIdle()

            assertTrue(transport.notificationCalls.isEmpty())
            assertTrue(transport.descriptorCalls.isEmpty())
        }

    @Test
    fun `disableNotification schreibt DISABLE und nimmt die Meldung zurueck`() =
        runTest {
            val client = client(this)
            val ch = characteristic()

            client.disableNotification(ch)
            runCurrent()

            val cccd = ch.getDescriptor(BleGattClient.CCCD_UUID)
            assertEquals(listOf(cccd to false), transport.descriptorCalls)
            assertEquals(listOf(ch to false), transport.notificationCalls)
        }

    // --- Cleanup ---------------------------------------------------------------

    @Test
    fun `close beendet Pending-Read mit null und leert die Queue`() =
        runTest {
            val client = client(this)
            val deferred = async { client.read(characteristic()) }
            runCurrent()

            client.close()
            runCurrent()

            assertNull(deferred.await())
            assertEquals(1, transport.closeCalls)
            assertFalse(client.isConnected)

            // Nach dem Cleanup nimmt der Client wieder Arbeit an.
            client.requestMtu(185)
            runCurrent()
            transport.fireMtu(185)
            runCurrent()
            assertEquals(listOf(185), transport.mtuRequests)
        }
}
