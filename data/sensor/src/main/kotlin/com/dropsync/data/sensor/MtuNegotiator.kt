package com.dropsync.data.sensor

/**
 * MTU-Verhandlung (Testinfrastruktur-Umbauplan Schritt 2, 5a) als reiner,
 * JVM-testbarer Entscheidungskern — ohne BLE-Hardware.
 *
 * Der Kern kapselt die Produktions-Regeln aus dem Plan:
 * - Ziel-MTU 512; das Peripheral handelt selbst herunter (typisch 247).
 * - Timeout und Fehler-Status loesen einen Retry aus; max [MAX_RETRIES].
 * - Status 133 (`GATT_ERROR`, Catch-all) ist ein Retry-Trigger, kein Endzustand.
 * - Nach den Retries Fallback auf [FALLBACK_MTU] (23), nie Endlos-Warten.
 *
 * Die tatsaechliche serielle Ausfuehrung (FIFO-Queue, `requestMtu`,
 * `onMtuChanged`) liegt im Android-Teil; dieser Kern entscheidet nur,
 * welche Aktion als naechstes ansteht.
 */
object MtuNegotiator {
    /** Angefragte MTU; das Peripheral handelt herunter. */
    const val REQUEST_MTU = 512

    /** Fallback, wenn die Verhandlung endgueltig scheitert. */
    const val FALLBACK_MTU = 23

    /** Maximale Anzahl zusaetzlicher Versuche nach dem ersten Request. */
    const val MAX_RETRIES = 2

    /** Android GATT Catch-all, als Retry-Trigger behandelt. */
    const val GATT_ERROR = 133

    /** Android GATT Erfolg. */
    const val GATT_SUCCESS = 0

    /** Ergebnis eines Verhandlungsschritts. */
    sealed class Decision {
        /** `requestMtu(REQUEST_MTU)` erneut senden (Versuch [attempt], 1-basiert). */
        data class Request(val attempt: Int) : Decision()

        /** Verhandlung erfolgreich; Writes auf [mtu] chunken. */
        data class Negotiated(val mtu: Int) : Decision()

        /** Verhandlung endgueltig gescheitert; Fallback-MTU verwenden. */
        data class UseFallback(val mtu: Int = FALLBACK_MTU) : Decision()
    }

    /**
     * Entscheidet nach `onMtuChanged(mtu, status)` ueber den naechsten Schritt.
     *
     * @param mtu die vom Stack gemeldete MTU (nur bei [GATT_SUCCESS] gueltig).
     * @param status der GATT-Status des Callbacks.
     * @param retriesUsed bereits verbrauchte Retries (0 beim ersten Callback).
     */
    fun onMtuChanged(
        mtu: Int,
        status: Int,
        retriesUsed: Int,
    ): Decision =
        when {
            status == GATT_SUCCESS -> Decision.Negotiated(mtu)
            retriesUsed < MAX_RETRIES -> Decision.Request(attempt = retriesUsed + 2)
            else -> Decision.UseFallback()
        }

    /**
     * Entscheidet nach einem Timeout (kein `onMtuChanged` eingetroffen).
     * Samsung-Quirk (silent failure) wird so als Retry behandelt statt als
     * Endlos-Warten.
     */
    fun onTimeout(retriesUsed: Int): Decision =
        if (retriesUsed < MAX_RETRIES) {
            Decision.Request(attempt = retriesUsed + 2)
        } else {
            Decision.UseFallback()
        }
}
