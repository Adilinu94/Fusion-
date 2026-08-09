package com.dropsync.domain.timer

/**
 * Erlaubte Zustandsuebergaenge (Bauplan Schritt 7.2). Endzustaende
 * (COMPLETED, CANCELLED, FAILED) verlassen ihren Zustand nur ueber
 * `reset` nach IDLE; das ist bewusst KEIN regulaerer Uebergang.
 */
object TimerTransitions {
    private val allowed: Map<TimerStatus, Set<TimerStatus>> =
        mapOf(
            TimerStatus.IDLE to setOf(TimerStatus.PREPARING),
            TimerStatus.PREPARING to
                setOf(TimerStatus.RUNNING, TimerStatus.FAILED, TimerStatus.CANCELLED),
            TimerStatus.RUNNING to
                setOf(
                    TimerStatus.PAUSED,
                    TimerStatus.COMPLETED,
                    TimerStatus.CANCELLED,
                    TimerStatus.FAILED,
                ),
            TimerStatus.PAUSED to setOf(TimerStatus.RUNNING, TimerStatus.CANCELLED),
            TimerStatus.COMPLETED to emptySet(),
            TimerStatus.CANCELLED to emptySet(),
            TimerStatus.FAILED to emptySet(),
        )

    fun isAllowed(
        from: TimerStatus,
        to: TimerStatus,
    ): Boolean = to in allowed.getValue(from)

    /** Nur Endzustaende duerfen per reset nach IDLE (Schritt 7.2). */
    fun isResettable(from: TimerStatus): Boolean =
        from == TimerStatus.COMPLETED ||
            from == TimerStatus.CANCELLED ||
            from == TimerStatus.FAILED
}
