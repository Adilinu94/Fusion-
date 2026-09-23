package com.dropsync.data.timer

import com.dropsync.domain.workout.SetLogHaptics

/**
 * A1 (5.6/5.15): bindet den Domain-Port des Satz-Loggings an den
 * [HapticsAdapter]. Kurzer Impuls (`tick()`, 35 ms); ohne Vibrator ein
 * stiller No-Op.
 */
class AndroidSetLogHaptics(
    private val haptics: HapticsAdapter,
) : SetLogHaptics {
    override fun confirm() {
        haptics.tick()
    }
}
