package com.dropsync.data.sensor

import com.dropsync.core.common.AppError

/** Maps technical BLE errors to user-facing German messages (P2-4 port). */
object BleErrorMapper {
    /** Maps a Throwable to a typed [AppError] for the AppResult contract. */
    fun map(error: Throwable): AppError =
        when {
            error is SecurityException -> AppError.PermissionDenied("BLUETOOTH")
            else -> AppError.Unknown(toUserMessage(error))
        }

    fun toUserMessage(error: Throwable): String {
        val msg = (error.message ?: error.toString()).lowercase()
        val bluetoothOff =
            ("bluetooth" in msg && ("off" in msg || "nicht aktiv" in msg || "disabled" in msg)) ||
                "poweredoff" in msg ||
                "adapterstate" in msg
        return when {
            bluetoothOff -> {
                "Bluetooth ist ausgeschaltet. Bitte Bluetooth in den Einstellungen aktivieren."
            }

            "nicht gefunden" in msg || "timeout" in msg || "not found" in msg -> {
                "FlowRep-Sensor nicht gefunden. Stick eingeschaltet und in der Naehe? " +
                    "(BLE-Name: FlowRep oder GymTracker)"
            }

            "mtu" in msg -> {
                "Verbindungsproblem (MTU). Bitte erneut versuchen."
            }

            "permission" in msg || "berechtigung" in msg || "securityexception" in msg -> {
                "Bluetooth-Berechtigung fehlt. Bitte in den App-Einstellungen erlauben."
            }

            "already connected" in msg || "busy" in msg -> {
                "Das Geraet ist bereits verbunden oder beschaeftigt. Bitte kurz warten und erneut versuchen."
            }

            else -> {
                "Verbindungsfehler. Bitte erneut versuchen."
            }
        }
    }
}
