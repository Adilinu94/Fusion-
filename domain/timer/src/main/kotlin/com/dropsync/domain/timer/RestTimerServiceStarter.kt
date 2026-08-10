package com.dropsync.domain.timer

/**
 * Starts the rest-timer foreground service (Fusion Phase 3). Kept in the
 * domain so :feature:* modules never depend on :data:* (rule 3.2/4);
 * implemented in :data:timer (TimerService) and bound via Hilt.
 */
fun interface RestTimerServiceStarter {
    fun startForegroundTimerService()
}
