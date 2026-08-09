package com.dropsync.core.testing

import com.dropsync.core.common.Clock

/**
 * Kontrollierbare Zeitquelle fuer Tests (Bauplan Schritt 2.5).
 *
 * Monotone und Wanduhrzeit werden getrennt vorangetrieben, damit Tests
 * Systemzeitaenderungen simulieren koennen, ohne den monotonen Verlauf zu
 * beeinflussen — exakt das Verhalten, auf dem der normale Timer beruht.
 */
class FakeClock(
    initialElapsedRealtimeMs: Long = 0L,
    initialEpochMillis: Long = 0L,
) : Clock {
    private var elapsed = initialElapsedRealtimeMs
    private var epoch = initialEpochMillis

    override fun elapsedRealtimeMs(): Long = elapsed

    override fun epochMillis(): Long = epoch

    /** Treibt beide Uhren gemeinsam voran (normaler Zeitverlauf). */
    fun advanceBy(durationMs: Long) {
        require(durationMs >= 0) { "Zeit laeuft im Test nie rueckwaerts: $durationMs" }
        elapsed += durationMs
        epoch += durationMs
    }

    /** Simuliert eine Systemzeitaenderung; die monotone Uhr bleibt unberuehrt. */
    fun setEpochMillis(newEpochMillis: Long) {
        epoch = newEpochMillis
    }

    /** Simuliert einen Reboot: die monotone Uhr beginnt wieder bei 0. */
    fun simulateReboot() {
        elapsed = 0L
    }
}
