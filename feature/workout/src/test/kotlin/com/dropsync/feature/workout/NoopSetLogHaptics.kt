package com.dropsync.feature.workout

import com.dropsync.domain.workout.SetLogHaptics

/**
 * Stiller No-Op fuer Tests (2026-09-27, Befund 12.3).
 *
 * `SetLogHaptics` war ein `fun interface` mit **einer** abstrakten
 * Methode, also liess sich eine Instanz schreiben als
 * `SetLogHaptics { }`. Seit [SetLogHaptics.tap] gibt es zwei — die
 * SAM-Schreibweise existiert nicht mehr.
 *
 * Statt an sieben Testdateien eine anonyme `object`-Implementierung zu
 * schreiben, liegt sie hier. Die Haptik ist in Unit-Tests per Definition
 * nicht pruefbar (kein Vibrator unter Robolectric, der ohnehin stubbt);
 * was zaehlt, ist die **Anzahl** der Aufrufe — und das testet
 * `SetLogControllerTest` ueber seine eigene `RecordingHaptics`.
 */
internal object NoopSetLogHaptics : SetLogHaptics {
    override fun confirm() = Unit

    override fun tap() = Unit
}
