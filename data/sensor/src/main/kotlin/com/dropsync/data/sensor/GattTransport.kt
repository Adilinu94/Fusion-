package com.dropsync.data.sensor

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.os.Build
import java.util.UUID

/**
 * Ereignisse des GATT-Transports (Audit 2026-09-23): der [BleGattClient]
 * serialisiert Operationen, Timeouts und Queue — aber kein Android-BLE-Objekt.
 * Alle `BluetoothGatt`-Aufrufe stecken hinter [GattTransport]; Tests fahren
 * die Client-Logik mit einem Fake, ohne BLE-Stack.
 */
internal interface GattTransportListener {
    fun onConnected()

    fun onDisconnected()

    fun onMtuChanged(
        mtu: Int,
        status: Int,
    )

    fun onServicesDiscovered()

    fun onCharacteristicRead(
        characteristicUuid: UUID,
        value: ByteArray?,
        status: Int,
    )

    fun onCharacteristicWrite(status: Int)

    fun onDescriptorWritten()

    fun onNotification(
        characteristicUuid: UUID,
        value: ByteArray,
    )
}

/**
 * Schmaler BLE-Port des [BleGattClient]: Verbindungsaufbau, ein ausstehender
 * Aufruf je Operation und Boolean-Ergebnisse (false = Android hat den Aufruf
 * abgelehnt, es kommt kein Callback). SDK-Verzweigungen (Tiramisu vs.
 * Legacy) leben in der echten Implementierung, nicht in der Client-Logik.
 */
internal interface GattTransport {
    fun setListener(listener: GattTransportListener)

    fun connect(
        context: Context,
        device: BluetoothDevice,
    )

    fun close()

    val isConnected: Boolean

    fun getService(uuid: UUID): BluetoothGattService?

    fun requestMtu(mtu: Int): Boolean

    fun discoverServices(): Boolean

    fun requestHighPriority()

    fun setCharacteristicNotification(
        characteristic: BluetoothGattCharacteristic,
        enable: Boolean,
    ): Boolean

    fun writeDescriptor(
        descriptor: BluetoothGattDescriptor,
        enable: Boolean,
    ): Boolean

    fun readCharacteristic(characteristic: BluetoothGattCharacteristic): Boolean

    fun writeCharacteristic(
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray,
    ): Boolean
}

/**
 * Echter Transport ueber den Android-BLE-Stack: haelt das einzige
 * `BluetoothGatt` und uebersetzt dessen Callbacks in [GattTransportListener].
 * Duenn per Design — Logik (Queue, Timeout, Matching) bleibt im Client und
 * damit testbar.
 */
@SuppressLint("MissingPermission")
internal class AndroidGattTransport : GattTransport {
    private var gatt: BluetoothGatt? = null
    private var listener: GattTransportListener? = null

    override fun setListener(listener: GattTransportListener) {
        this.listener = listener
    }

    private val callback =
        object : BluetoothGattCallback() {
            override fun onConnectionStateChange(
                g: BluetoothGatt,
                status: Int,
                newState: Int,
            ) {
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> listener?.onConnected()
                    BluetoothProfile.STATE_DISCONNECTED -> listener?.onDisconnected()
                }
            }

            override fun onMtuChanged(
                g: BluetoothGatt,
                mtu: Int,
                status: Int,
            ) {
                listener?.onMtuChanged(mtu, status)
            }

            override fun onServicesDiscovered(
                g: BluetoothGatt,
                status: Int,
            ) {
                listener?.onServicesDiscovered()
            }

            override fun onCharacteristicRead(
                g: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                value: ByteArray,
                status: Int,
            ) {
                listener?.onCharacteristicRead(characteristic.uuid, value, status)
            }

            @Deprecated("API < 33")
            override fun onCharacteristicRead(
                g: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                status: Int,
            ) {
                @Suppress("DEPRECATION")
                listener?.onCharacteristicRead(characteristic.uuid, characteristic.value, status)
            }

            override fun onCharacteristicWrite(
                g: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                status: Int,
            ) {
                listener?.onCharacteristicWrite(status)
            }

            override fun onDescriptorWrite(
                g: BluetoothGatt,
                descriptor: BluetoothGattDescriptor,
                status: Int,
            ) {
                listener?.onDescriptorWritten()
            }

            override fun onCharacteristicChanged(
                g: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                value: ByteArray,
            ) {
                listener?.onNotification(characteristic.uuid, value)
            }

            @Deprecated("API < 33")
            override fun onCharacteristicChanged(
                g: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
            ) {
                @Suppress("DEPRECATION")
                characteristic.value?.let { listener?.onNotification(characteristic.uuid, it) }
            }
        }

    override fun connect(
        context: Context,
        device: BluetoothDevice,
    ) {
        gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
    }

    override fun close() {
        gatt?.close()
        gatt = null
    }

    override val isConnected: Boolean
        get() = gatt != null

    override fun getService(uuid: UUID) = gatt?.getService(uuid)

    override fun requestMtu(mtu: Int): Boolean = gatt?.requestMtu(mtu) == true

    override fun discoverServices(): Boolean = gatt?.discoverServices() == true

    override fun requestHighPriority() {
        // Fire-and-forget: no completion callback for connection priority.
        gatt?.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
    }

    override fun setCharacteristicNotification(
        characteristic: BluetoothGattCharacteristic,
        enable: Boolean,
    ): Boolean = gatt?.setCharacteristicNotification(characteristic, enable) == true

    override fun writeDescriptor(
        descriptor: BluetoothGattDescriptor,
        enable: Boolean,
    ): Boolean {
        val value =
            if (enable) {
                BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            } else {
                BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
            }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt?.writeDescriptor(descriptor, value) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            descriptor.value = value
            @Suppress("DEPRECATION")
            gatt?.writeDescriptor(descriptor) == true
        }
    }

    override fun readCharacteristic(characteristic: BluetoothGattCharacteristic): Boolean {
        @Suppress("DEPRECATION")
        return gatt?.readCharacteristic(characteristic) == true
    }

    override fun writeCharacteristic(
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray,
    ): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt?.writeCharacteristic(characteristic, value, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) ==
                BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            characteristic.value = value
            @Suppress("DEPRECATION")
            gatt?.writeCharacteristic(characteristic) == true
        }
}
