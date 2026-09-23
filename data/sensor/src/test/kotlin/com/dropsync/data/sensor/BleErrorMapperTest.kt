package com.dropsync.data.sensor

import com.dropsync.core.common.AppError
import com.dropsync.domain.sensor.SensorErrorReason
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Heuristik des BLE-Fehler-Mappings (P3-Fix #27): Die Klasse ist der
 * einzige Ort, an dem freier Fehlertext interpretiert wird — jede
 * Kategorie und der Token-Vertrag nach oben sind hier festgenagelt.
 */
class BleErrorMapperTest {
    @Test
    fun `SecurityException ist PERMISSION_DENIED`() {
        assertEquals(
            SensorErrorReason.PERMISSION_DENIED,
            BleErrorMapper.classify(SecurityException("no permission")),
        )
    }

    @Test
    fun `BleProtocolException ist PROTOCOL`() {
        assertEquals(
            SensorErrorReason.PROTOCOL,
            BleErrorMapper.classify(BleProtocolException("unbekanntes Frame")),
        )
    }

    @Test
    fun `Bluetooth-aus-Varianten sind BLUETOOTH_OFF`() {
        assertEquals(
            SensorErrorReason.BLUETOOTH_OFF,
            BleErrorMapper.classify(IllegalStateException("Bluetooth is off")),
        )
        assertEquals(
            SensorErrorReason.BLUETOOTH_OFF,
            BleErrorMapper.classify(IllegalStateException("poweredOff")),
        )
        assertEquals(
            SensorErrorReason.BLUETOOTH_OFF,
            BleErrorMapper.classify(IllegalStateException("adapterState changed")),
        )
    }

    @Test
    fun `fehlender Adapter ist ADAPTER_UNAVAILABLE`() {
        assertEquals(
            SensorErrorReason.ADAPTER_UNAVAILABLE,
            BleErrorMapper.classify(IllegalStateException("Adapter nicht verfuegbar")),
        )
        assertEquals(
            SensorErrorReason.ADAPTER_UNAVAILABLE,
            BleErrorMapper.classify(IllegalStateException("adapter unavailable")),
        )
    }

    @Test
    fun `Timeout und MTU sind TIMEOUT`() {
        assertEquals(
            SensorErrorReason.TIMEOUT,
            BleErrorMapper.classify(IllegalStateException("GATT operation timeout")),
        )
        assertEquals(
            SensorErrorReason.TIMEOUT,
            BleErrorMapper.classify(IllegalStateException("MTU request failed")),
        )
    }

    @Test
    fun `nicht gefunden ist NOT_FOUND`() {
        assertEquals(
            SensorErrorReason.NOT_FOUND,
            BleErrorMapper.classify(IllegalStateException("Geraet nicht gefunden")),
        )
        assertEquals(
            SensorErrorReason.NOT_FOUND,
            BleErrorMapper.classify(IllegalStateException("device not found")),
        )
    }

    @Test
    fun `fehlender Dienst ist SERVICE_MISSING`() {
        assertEquals(
            SensorErrorReason.SERVICE_MISSING,
            BleErrorMapper.classify(IllegalStateException("service missing")),
        )
        assertEquals(
            SensorErrorReason.SERVICE_MISSING,
            BleErrorMapper.classify(IllegalStateException("Charakteristik fehlt")),
        )
    }

    @Test
    fun `not found hat Vorrang vor service`() {
        assertEquals(
            SensorErrorReason.NOT_FOUND,
            BleErrorMapper.classify(IllegalStateException("service not found")),
        )
    }

    @Test
    fun `getrennte Verbindung ist DISCONNECTED`() {
        assertEquals(
            SensorErrorReason.DISCONNECTED,
            BleErrorMapper.classify(IllegalStateException("Geraet getrennt")),
        )
        assertEquals(
            SensorErrorReason.DISCONNECTED,
            BleErrorMapper.classify(IllegalStateException("disconnected from peer")),
        )
    }

    @Test
    fun `unbekannter Text ist UNKNOWN`() {
        assertEquals(
            SensorErrorReason.UNKNOWN,
            BleErrorMapper.classify(IllegalStateException("irgendwas anderes")),
        )
    }

    @Test
    fun `Exception ohne Message faellt auf den Klassennamen zurueck`() {
        assertEquals(
            SensorErrorReason.UNKNOWN,
            BleErrorMapper.classify(RuntimeException()),
        )
    }

    @Test
    fun `map liefert PermissionDenied mit Berechtigungs-Token`() {
        assertEquals(
            AppError.PermissionDenied("BLUETOOTH"),
            BleErrorMapper.map(SecurityException("denied")),
        )
    }

    @Test
    fun `map liefert Unknown mit dem Reason-Token`() {
        assertEquals(
            AppError.Unknown(SensorErrorReason.TIMEOUT.token),
            BleErrorMapper.map(IllegalStateException("timeout")),
        )
    }

    @Test
    fun `error reicht einen bekannten Grund typisiert durch`() {
        assertEquals(
            AppError.PermissionDenied("BLUETOOTH"),
            BleErrorMapper.error(SensorErrorReason.PERMISSION_DENIED),
        )
        assertEquals(
            AppError.Unknown(SensorErrorReason.DISCONNECTED.token),
            BleErrorMapper.error(SensorErrorReason.DISCONNECTED),
        )
    }
}
