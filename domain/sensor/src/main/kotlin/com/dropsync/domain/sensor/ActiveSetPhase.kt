package com.dropsync.domain.sensor

/**
 * Lebensphase eines live gezaehlten Sets (Umbauplan Phase 6). Vorher als
 * [TrainViewModel.SetPhase] direkt im ViewModel definiert; jetzt Teil des
 * Sensor-Vertrags, damit [ActiveSetController] sie verwalten kann.
 */
enum class ActiveSetPhase {
    IDLE,
    COUNTDOWN,
    COUNTING,
}

/**
 * Grund, aus dem ein aktives Set abgebrochen wurde (Umbauplan Phase 6).
 * Alle Abbruchpfade muessen dieselbe atomare Reset-Funktion durchlaufen.
 */
enum class SetAbortReason {
    DISCONNECT,
    EXERCISE_CHANGED,
    EXERCISE_FINISHED,
    CALIBRATION_CHANGED,
    CLEARED,
}
