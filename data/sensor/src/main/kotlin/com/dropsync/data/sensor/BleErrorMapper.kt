package com.dropsync.data.sensor

import com.dropsync.core.common.AppError
import com.dropsync.domain.sensor.SensorErrorReason

/**
 * Bildet technische BLE-Fehler auf einen klassifizierten
 * [SensorErrorReason] ab — P3-Fix #27.
 *
 * Vorher formulierte diese Klasse fertige DEUTSCHE Saetze und legte sie in
 * `AppError.Unknown(debugMessage)`. Das war doppelt falsch: die Datenschicht
 * kennt keine Locale und keine Ressourcen (ein Sprachwechsel haette die
 * Sensorfehler deutsch gelassen), und `debugMessage` ist per Vertrag nicht
 * fuer die Anzeige gedacht. Jetzt wandert nur noch ein stabiles Token nach
 * oben; die UI-Schicht waehlt den Text aus `strings.xml`.
 *
 * Die Klassifizierung bleibt notgedrungen heuristisch: die Android-BLE-API
 * liefert fuer die meisten Fehler nur eine `Exception` mit freiem Text. Die
 * Heuristik ist damit nicht schoener geworden — sie steckt aber jetzt an der
 * einen Stelle, an der sie hingehoert, und ihr Ergebnis ist ein typisierter
 * Wert statt eines Satzes.
 */
object BleErrorMapper {
    /** Maps a Throwable to a typed [AppError] for the AppResult contract. */
    fun map(error: Throwable): AppError =
        when (val reason = classify(error)) {
            SensorErrorReason.PERMISSION_DENIED -> AppError.PermissionDenied("BLUETOOTH")
            else -> AppError.Unknown(reason.token)
        }

    /** Erzeugt einen [AppError] fuer einen bereits bekannten Grund. */
    fun error(reason: SensorErrorReason): AppError =
        when (reason) {
            SensorErrorReason.PERMISSION_DENIED -> AppError.PermissionDenied("BLUETOOTH")
            else -> AppError.Unknown(reason.token)
        }

    /**
     * Ordnet eine Exception einem Grund zu. Sichtbar fuer Tests: die
     * Heuristik ist der einzige Ort, an dem freier Fehlertext interpretiert
     * wird, und soll deshalb pruefbar bleiben.
     */
    fun classify(error: Throwable): SensorErrorReason {
        if (error is SecurityException) return SensorErrorReason.PERMISSION_DENIED
        if (error is BleProtocolException) return SensorErrorReason.PROTOCOL
        val msg = (error.message ?: error.toString()).lowercase()
        val bluetoothOff =
            ("bluetooth" in msg && ("off" in msg || "nicht aktiv" in msg || "disabled" in msg)) ||
                "poweredoff" in msg ||
                "adapterstate" in msg
        return when {
            bluetoothOff -> {
                SensorErrorReason.BLUETOOTH_OFF
            }

            "adapter nicht verfuegbar" in msg || "adapter unavailable" in msg -> {
                SensorErrorReason.ADAPTER_UNAVAILABLE
            }

            "permission" in msg || "berechtigung" in msg || "securityexception" in msg -> {
                SensorErrorReason.PERMISSION_DENIED
            }

            "timeout" in msg || "mtu" in msg -> {
                SensorErrorReason.TIMEOUT
            }

            "nicht gefunden" in msg || "not found" in msg -> {
                SensorErrorReason.NOT_FOUND
            }

            "service" in msg || "charakteristik" in msg || "characteristic" in msg -> {
                SensorErrorReason.SERVICE_MISSING
            }

            "getrennt" in msg || "disconnect" in msg -> {
                SensorErrorReason.DISCONNECTED
            }

            else -> {
                SensorErrorReason.UNKNOWN
            }
        }
    }
}
