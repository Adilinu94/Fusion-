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
    /**
     * Angefragte MTU; das Peripheral handelt herunter. 185 statt 512:
     * HyperOS MTU-517 off-by-one boundary bug (Produktions-Praxis, siehe
     * BleSensorProvider). Der Wert ist bewusst kleiner als der
     * V2-Payload-Bedarf (53 Byte + Overhead passen in 185).
     */
    const val REQUEST_MTU = 185

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
        data class Request(
            val attempt: Int,
        ) : Decision()

        /** Verhandlung erfolgreich; Writes auf [mtu] chunken. */
        data class Negotiated(
            val mtu: Int,
        ) : Decision()

        /** Verhandlung endgueltig gescheitert; Fallback-MTU verwenden. */
        data class UseFallback(
            val mtu: Int = FALLBACK_MTU,
        ) : Decision()
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

/**
 * Stateful wrapper around [MtuNegotiator] that tracks the retry counter
 * across a negotiation sequence. The [BleSensorProvider] calls the
 * decision functions per GATT callback/timeout; this class is the pure,
 * JVM-testable part of the wiring (Testinfra-Plan 5a: "negotiateMtu als
 * pure, testbare Funktion").
 *
 * Counter semantics: [retriesUsed] counts how many retry requests have
 * been issued so far. A [MtuNegotiator.Decision.Request] means "send the
 * next request now" and increments the counter; the caller must send it.
 */
class MtuNegotiationSession {
    var retriesUsed = 0
        private set

    /** Starts a fresh negotiation (first request). */
    fun reset() {
        retriesUsed = 0
    }

    /** Handles an `onMtuChanged` callback and returns the next action. */
    fun onMtuChanged(
        mtu: Int,
        status: Int,
    ): MtuNegotiator.Decision =
        MtuNegotiator
            .onMtuChanged(mtu, status, retriesUsed)
            .also { decision ->
                if (decision is MtuNegotiator.Decision.Request) retriesUsed++
            }

    /** Handles a request timeout and returns the next action. */
    fun onTimeout(): MtuNegotiator.Decision =
        MtuNegotiator
            .onTimeout(retriesUsed)
            .also { decision ->
                if (decision is MtuNegotiator.Decision.Request) retriesUsed++
            }
}
