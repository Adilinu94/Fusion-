package com.dropsync.domain.timer

/**
 * Reboot-Erkennung ohne Boot-ID (Bauplan Schritt 7.8/7.9, ADR-0002):
 * Beim Start der Timer-Infrastruktur wird `elapsedRealtime()` mit dem
 * zuletzt gespeicherten monotonen Wert verglichen. Ist der aktuelle
 * Wert kleiner, fehlt der gespeicherte Wert oder ist die Persistenz
 * inkonsistent, wird ein persistierter Timer immer verworfen
 * (CANCELLED mit DEVICE_REBOOT_OR_UNKNOWN_CLOCK). Die zugehoerige
 * Workout-Session bleibt unberuehrt.
 */
object RebootGuard {
    fun shouldDiscard(
        storedLastElapsedRealtimeMs: Long?,
        currentElapsedRealtimeMs: Long,
    ): Boolean =
        storedLastElapsedRealtimeMs == null ||
            storedLastElapsedRealtimeMs < 0 ||
            currentElapsedRealtimeMs < storedLastElapsedRealtimeMs
}
