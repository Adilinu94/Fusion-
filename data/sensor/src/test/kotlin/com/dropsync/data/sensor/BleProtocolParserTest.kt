package com.dropsync.data.sensor

import com.dropsync.domain.sensor.SensorSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Port of ble_protocol_parser_test.dart against protocol.yaml v1/v2. */
class BleProtocolParserTest {
    private fun sample(i: Int) =
        SensorSample(
            timestampMs = 0,
            ax = 0.1 * i,
            ay = -0.2 * i,
            az = 1.0,
            gx = 10.0 * i,
            gy = -20.0 * i,
            gz = 5.0,
        )

    @Test
    fun `v2 roundtrip keeps values`() {
        val samples = (0 until 4).map(::sample)
        val bytes = BleProtocolParser.encodeBatch(1000, samples, protocolVersion = 2)
        assertEquals(BleProtocolParser.V2_TOTAL_BYTES, bytes.size)
        assertEquals(2, bytes[4].toInt())

        val parsed = BleProtocolParser.parseBatch(bytes)
        assertEquals(4, parsed.size)
        // 20 ms spacing guaranteed by v2 firmware (protocol.yaml timing).
        assertEquals(1000L, parsed[0].timestampMs)
        assertEquals(1060L, parsed[3].timestampMs)
        assertEquals(samples[1].ax, parsed[1].ax, 1e-3)
        assertEquals(samples[2].gy, parsed[2].gy, 0.02)
    }

    @Test
    fun `v1 packets parse with v1 gyro scale`() {
        val samples = (0 until 4).map(::sample)
        val bytes = BleProtocolParser.encodeBatch(500, samples, protocolVersion = 1)
        assertEquals(BleProtocolParser.V1_TOTAL_BYTES, bytes.size)

        val parsed = BleProtocolParser.parseBatch(bytes)
        assertEquals(500L, parsed[0].timestampMs)
        assertEquals(samples[0].gx, parsed[0].gx, 0.01)
    }

    @Test
    fun `wrong length throws`() {
        assertThrows(BleProtocolException::class.java) {
            BleProtocolParser.parseBatch(ByteArray(30))
        }
    }

    @Test
    fun `encode requires exactly four samples`() {
        assertThrows(BleProtocolException::class.java) {
            BleProtocolParser.encodeBatch(0, listOf(sample(0)))
        }
    }

    @Test
    fun `timestampOf reads wire timestamp little endian`() {
        val bytes = BleProtocolParser.encodeBatch(0x01020304, (0 until 4).map(::sample))
        assertEquals(0x01020304, BleProtocolParser.timestampOf(bytes))
    }

    @Test
    fun `uint32 timestamp does not go negative`() {
        val bytes = BleProtocolParser.encodeBatch(-1, (0 until 4).map(::sample))
        val parsed = BleProtocolParser.parseBatch(bytes)
        assertTrue(parsed[0].timestampMs > 0)
    }
}
