package com.dropsync.domain.sensor

import com.dropsync.core.common.AppError

/**
 * Klassifizierter Grund eines Sensor-Fehlers — P3-Fix #27.
 *
 * Vorher formulierten [com.dropsync.data.sensor.BleErrorMapper] und der
 * BLE-Provider fertige DEUTSCHE Saetze und legten sie in
 * `AppError.Unknown(debugMessage)`. Das ist an zwei Stellen falsch:
 *
 * 1. Lokalisierung: die Datenschicht kennt keine Ressourcen und keine
 *    Locale. Ein Wechsel der App-Sprache haette die Sensorfehler deutsch
 *    gelassen — die Texte standen ja im Kotlin-Code.
 * 2. Schichtung: `debugMessage` ist per Vertrag ("Details nur im Debug-Log",
 *    siehe [AppError.Unknown]) NICHT fuer die Anzeige gedacht. Die UI las es
 *    trotzdem direkt aus, weil es der einzige Traeger von Information war.
 *
 * Die Datenschicht liefert jetzt einen dieser stabilen Gruende als Token,
 * die UI-Schicht waehlt den passenden String aus `strings.xml`.
 *
 * Reines Kotlin, keine Android-Typen — die Zuordnung ist damit testbar.
 */
enum class SensorErrorReason {
    /** BLUETOOTH_SCAN/BLUETOOTH_CONNECT nicht erteilt. */
    PERMISSION_DENIED,

    /** Bluetooth-Adapter vorhanden, aber ausgeschaltet. */
    BLUETOOTH_OFF,

    /** Kein Bluetooth-Adapter (Emulator, Geraet ohne BLE). */
    ADAPTER_UNAVAILABLE,

    /** Scan lief durch, ohne einen FlowRep-Stick zu finden. */
    NOT_FOUND,

    /** Verbunden, aber der GATT-Dienst/die Charakteristik fehlt. */
    SERVICE_MISSING,

    /** Eine GATT-Operation hat nie geantwortet. */
    TIMEOUT,

    /** Der Chip sendet Pakete, die der Parser nicht versteht. */
    PROTOCOL,

    /** Die Verbindung wurde (remote) getrennt. */
    DISCONNECTED,

    /** Nicht klassifizierbar. */
    UNKNOWN,

    ;

    /**
     * Token fuer `AppError.Unknown(debugMessage)`. Bewusst mit Praefix, damit
     * ein Log-Leser sofort sieht, dass es kein freier Text ist.
     */
    val token: String
        get() = "$TOKEN_PREFIX$name"

    companion object {
        const val TOKEN_PREFIX = "sensor:"

        /**
         * Liest den Grund aus einem [AppError].
         *
         * [AppError.PermissionDenied] ist bereits typisiert und wird direkt
         * uebernommen. Bei [AppError.Unknown] wird das Token gelesen; ein
         * unbekannter oder fehlender Wert ergibt [UNKNOWN] — es wird NICHT
         * geraten und kein Fremdtext angezeigt.
         */
        fun from(error: AppError): SensorErrorReason =
            when (error) {
                is AppError.PermissionDenied -> PERMISSION_DENIED
                is AppError.Unknown -> fromToken(error.debugMessage)
                else -> UNKNOWN
            }

        private fun fromToken(raw: String?): SensorErrorReason {
            val value = raw?.removePrefix(TOKEN_PREFIX) ?: return UNKNOWN
            return entries.firstOrNull { it.name == value } ?: UNKNOWN
        }
    }
}
