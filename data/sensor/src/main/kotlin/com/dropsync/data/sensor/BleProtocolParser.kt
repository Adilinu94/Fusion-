package com.dropsync.data.sensor

import com.dropsync.domain.sensor.SensorSample
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Malformed wire packet; surfaced, never swallowed (protocol.yaml). */
class BleProtocolException(
    message: String,
) : Exception("BleProtocolException: $message")

/**
 * Parses the BLE sensor-data payload defined in docs/reference/protocol.yaml.
 * This file and protocol.yaml MUST stay in sync (port of the Dart parser).
 *
 * Wire-format detection: 52 bytes = v1 (no version field, gyro 0.01),
 * 53 bytes = v2 (uint8 protocol_version at offset 4, gyro 0.02).
 * Each sample: int16 ax/ay/az (0.001 g) + int16 gx/gy/gz (v1 0.01 / v2 0.02).
 */
object BleProtocolParser {
    const val V1_TOTAL_BYTES = 52
    const val V2_TOTAL_BYTES = 53
    const val SAMPLES_PER_BATCH = 4
    const val BYTES_PER_SAMPLE = 12
    const val ACCEL_SCALE = 0.001
    const val GYRO_SCALE_V1 = 0.01
    const val GYRO_SCALE_V2 = 0.02

    /** Preferred size for new encodes (protocol v2 / current firmware). */
    const val EXPECTED_TOTAL_BYTES = V2_TOTAL_BYTES
    const val GYRO_SCALE = GYRO_SCALE_V2

    /** Sample spacing guaranteed by v2 firmware (protocol.yaml timing). */
    const val SAMPLE_INTERVAL_MS = 20L

    /**
     * Throws [BleProtocolException] on malformed packets: mis-parsed sensor
     * data would corrupt threshold calibration silently otherwise.
     */
    fun parseBatch(bytes: ByteArray): List<SensorSample> {
        val samplesStart: Int
        val gyroScaleUsed: Double
        when (bytes.size) {
            V2_TOTAL_BYTES -> {
                samplesStart = 5
                gyroScaleUsed = GYRO_SCALE_V2
            }

            V1_TOTAL_BYTES -> {
                samplesStart = 4
                gyroScaleUsed = GYRO_SCALE_V1
            }

            else -> {
                throw BleProtocolException(
                    "Erwartete $V1_TOTAL_BYTES (v1) oder $V2_TOTAL_BYTES (v2) Byte, " +
                        "aber ${bytes.size} erhalten. Pruefe BLE-MTU (protocol.yaml " +
                        "constraints.ble_mtu) und Firmware/App-Protokollversion.",
                )
            }
        }

        val data = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val timestampMs = data.getInt(0).toLong() and 0xFFFFFFFFL

        return (0 until SAMPLES_PER_BATCH).map { i ->
            val offset = samplesStart + i * BYTES_PER_SAMPLE
            SensorSample(
                // v2 firmware guarantees 20 ms between consecutive samples,
                // including across batch boundaries (protocol.yaml timing).
                timestampMs = timestampMs + i * SAMPLE_INTERVAL_MS,
                ax = data.getShort(offset) * ACCEL_SCALE,
                ay = data.getShort(offset + 2) * ACCEL_SCALE,
                az = data.getShort(offset + 4) * ACCEL_SCALE,
                gx = data.getShort(offset + 6) * gyroScaleUsed,
                gy = data.getShort(offset + 8) * gyroScaleUsed,
                gz = data.getShort(offset + 10) * gyroScaleUsed,
            )
        }
    }

    /** Wire timestamp of a batch without full parsing (for dedup). */
    fun timestampOf(bytes: ByteArray): Int {
        if (bytes.size < 4) throw BleProtocolException("Paket zu kurz: ${bytes.size}")
        return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).getInt(0)
    }

    /** Inverse of [parseBatch]; encodes v2 by default (tests, mocks). */
    fun encodeBatch(
        timestampMs: Int,
        samples: List<SensorSample>,
        protocolVersion: Int = 2,
    ): ByteArray {
        if (samples.size != SAMPLES_PER_BATCH) {
            throw BleProtocolException(
                "encodeBatch erwartet genau $SAMPLES_PER_BATCH Samples, bekam ${samples.size}.",
            )
        }
        val (total, start, gyroScale) =
            when (protocolVersion) {
                2 -> Triple(V2_TOTAL_BYTES, 5, GYRO_SCALE_V2)
                1 -> Triple(V1_TOTAL_BYTES, 4, GYRO_SCALE_V1)
                else -> throw BleProtocolException("encodeBatch: unbekannte protocolVersion=$protocolVersion")
            }
        val buffer = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(0, timestampMs)
        if (protocolVersion == 2) buffer.put(4, 2)
        samples.forEachIndexed { i, s ->
            val offset = start + i * BYTES_PER_SAMPLE
            buffer.putShort(offset, (s.ax / ACCEL_SCALE).toInt().toShort())
            buffer.putShort(offset + 2, (s.ay / ACCEL_SCALE).toInt().toShort())
            buffer.putShort(offset + 4, (s.az / ACCEL_SCALE).toInt().toShort())
            buffer.putShort(offset + 6, (s.gx / gyroScale).toInt().toShort())
            buffer.putShort(offset + 8, (s.gy / gyroScale).toInt().toShort())
            buffer.putShort(offset + 10, (s.gz / gyroScale).toInt().toShort())
        }
        return buffer.array()
    }
}
