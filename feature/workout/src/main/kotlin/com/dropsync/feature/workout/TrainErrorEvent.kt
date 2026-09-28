package com.dropsync.feature.workout

/**
 * T-10/S-7: sichtbare Fehler der Train-Pfade als Einmal-Ereignis.
 *
 * Vorher waren diese Pfade stumm: `createExercise` verwarf den Fehler, ein
 * Profil-Load-Failure sah in der UI aus wie "nicht kalibriert", und ein
 * fehlgeschlagener Lern-Save landete nur im Log. Der Screen zeigt daraus
 * eine Snackbar (Texte in `strings.xml`); die Quelle ist ein
 * `Channel(BUFFERED)`, damit Ereignisse auch ohne offenen Collector nicht
 * verloren gehen.
 */
sealed interface TrainErrorEvent {
    /** Uebung anlegen fehlgeschlagen (Repository-Fehler). */
    data object ExerciseCreationFailed : TrainErrorEvent

    /**
     * Kalibrierprofil laden fehlgeschlagen — bewusst NICHT dasselbe wie
     * "kein Profil vorhanden" (bisher zeigte beides "nicht kalibriert").
     */
    data object ProfileLoadFailed : TrainErrorEvent

    /** Gelerntes Kandidatenprofil konnte nicht gespeichert werden. */
    data object LearningSaveFailed : TrainErrorEvent

    /** C3: Pausen-Praeferenz der Uebung konnte nicht gespeichert werden. */
    data object RestPrefSaveFailed : TrainErrorEvent

    /** Undo des letzten Satzes fehlgeschlagen (vorher stumm). */
    data object UndoFailed : TrainErrorEvent

    /**
     * Satz-Verlauf (letzter Satz, Max-Volumen, Mini-Verlauf) konnte nicht
     * geladen werden — bewusst NICHT dasselbe wie "noch keine Saetze"
     * (bisher sah beides nach leerer Historie aus).
     */
    data object HistoryLoadFailed : TrainErrorEvent

    /**
     * 2026-09-27, Befund 5.10: `startCountedSet()` hatte drei stille
     * `return`s (kein Profil, kein streamender Chip, keine Geraete-ID).
     * Der Nutzer tippte auf "Live zaehlen starten" und **es passierte
     * nichts** — kein Toast, kein Snackbar, kein visuelles Signal. Die
     * app ist ohne Sensor voll nutzbar (Handeingabe), also war die
     * Bedienung nicht kaputt, sie war nur stumm. Das ist der groesste
     * einzelne UX-Bruch im Train-Screen.
     *
     * Die drei Gruende sind getrennt, weil sie unterschiedliche Handlungen
     * brauchen: Chip verbinden, kalibrieren, oder einfach zaehlen.
     */
    data object CountBlockedNoChip : TrainErrorEvent

    data object CountBlockedNotStreaming : TrainErrorEvent

    data object CountBlockedNoCalibration : TrainErrorEvent

    /**
     * 2026-09-27, Befund 5.12: `startRestTimer()` pruefte das Ergebnis
     * von `timerEngine.start()` nicht. Ein `TimerConflict` (es laeuft
     * bereits eine Pause) bedeutete: Satz gespeichert, **keine** Pause,
     * keine Musik, keine Meldung.
     */
    data object RestTimerStartFailed : TrainErrorEvent
}
