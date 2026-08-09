package com.dropsync.core.common

/**
 * Monotone Frist auf Basis von [Clock.elapsedRealtimeMs].
 *
 * Grundbaustein des normalen Timers (Bauplan 5.3): Das Ende ist
 * `startedElapsedRealtimeMs + durationMs`; Systemzeit-Aenderungen haben
 * keinen Einfluss. `delay()` oder UI-Ticks sind nie die Quelle eines
 * Abschlussereignisses.
 */
class MonotonicDeadline(
    private val clock: Clock,
    val durationMs: Long,
) {
    init {
        require(durationMs > 0) { "Dauer muss positiv sein: $durationMs" }
    }

    val startedElapsedRealtimeMs: Long = clock.elapsedRealtimeMs()

    val endElapsedRealtimeMs: Long = startedElapsedRealtimeMs + durationMs

    fun remainingMs(): Long = maxOf(0L, endElapsedRealtimeMs - clock.elapsedRealtimeMs())

    fun isExpired(): Boolean = clock.elapsedRealtimeMs() >= endElapsedRealtimeMs
}
