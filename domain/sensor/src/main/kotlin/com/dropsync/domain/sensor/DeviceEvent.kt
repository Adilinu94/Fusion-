package com.dropsync.domain.sensor

/**
 * BLE DeviceEvent from the M5Stick (protocol.yaml DeviceEvent / fee4).
 * Port of engine/device_event.dart.
 */

enum class DeviceEventId(
    val wireValue: Int,
) {
    NONE(0x00),

    /** BtnA: app starts counting if idle, ends the set if counting. */
    COUNT_PRIMARY(0x01),
    ;

    companion object {
        fun fromWire(value: Int): DeviceEventId = entries.firstOrNull { it.wireValue == value } ?: NONE
    }
}

/** One button/device event (seq distinguishes repeats). */
data class DeviceEvent(
    val seq: Int,
    val id: DeviceEventId,
    val receivedAtEpochMs: Long,
)
