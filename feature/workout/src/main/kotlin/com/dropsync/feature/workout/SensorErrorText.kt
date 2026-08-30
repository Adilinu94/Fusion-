package com.dropsync.feature.workout

import androidx.annotation.StringRes
import com.dropsync.domain.sensor.SensorErrorReason

/**
 * Ordnet einen [SensorErrorReason] dem anzuzeigenden String zu — P3-Fix #27.
 *
 * Diese Zuordnung ist die einzige Stelle, an der ein Sensor-Fehlergrund zu
 * Text wird. Vorher formulierte die Datenschicht deutsche Saetze und legte
 * sie in `AppError.Unknown(debugMessage)`; ein Sprachwechsel haette sie
 * deutsch gelassen, weil sie im Kotlin-Code standen.
 *
 * Bewusst ein `when` ohne `else`: kommt ein neuer Grund dazu, bricht die
 * Kompilierung hier und erinnert daran, den String anzulegen.
 */
@StringRes
internal fun SensorErrorReason.messageRes(): Int =
    when (this) {
        SensorErrorReason.PERMISSION_DENIED -> R.string.sensor_error_permission_denied
        SensorErrorReason.BLUETOOTH_OFF -> R.string.sensor_error_bluetooth_off
        SensorErrorReason.ADAPTER_UNAVAILABLE -> R.string.sensor_error_adapter_unavailable
        SensorErrorReason.NOT_FOUND -> R.string.sensor_error_not_found
        SensorErrorReason.SERVICE_MISSING -> R.string.sensor_error_service_missing
        SensorErrorReason.TIMEOUT -> R.string.sensor_error_timeout
        SensorErrorReason.PROTOCOL -> R.string.sensor_error_protocol
        SensorErrorReason.DISCONNECTED -> R.string.sensor_error_disconnected
        SensorErrorReason.UNKNOWN -> R.string.sensor_error_generic
    }
