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
}
