package com.dropsync.domain.timer

import kotlinx.coroutines.flow.SharedFlow

/**
 * Schmale Kopplung zwischen Trainingslog und Musik-Feature (Schritt 11):
 * Das Workout kann - wenn eine Uebung den Rest-Modus DropSync bevorzugt -
 * einen "Rest bis zum naechsten Drop" anfordern, ohne selbst Playback oder
 * Marker zu kennen. Das Player-Feature konsumiert die Anforderungen und
 * startet den DropSync-Timer, sofern das Gate offen ist (11.2).
 *
 * Reine Domainschnittstelle ohne Android-Abhaengigkeit; die Singleton-
 * Implementierung liegt in :data:timer.
 */
interface DropRestRequestBus {
    /** Kalte, konfliktfreie Anforderungen; nur der Player-Screen sammelt sie. */
    val requests: SharedFlow<Unit>

    /** Fordert genau einen Drop-Rest-Start an (fire-and-forget). */
    fun request()

    /**
     * Bricht einen laufenden Drop-Rest-Plan ab (2026-09-27, Befund 5.11).
     *
     * Aufrufer: `TrainViewModel.undoLastSet()`. Der Satz wurde
     * zurueckgenommen, aber der Plan lief weiter — die Restmusik
     * uebernahm die Queue, der Work-Titel landete zum geplanten Drop.
     * Der Nutzer hatte die Uebung fuer diesen Satz zurueckgenommen und
     * die Musik nicht.
     *
     * Getrennt von `request()`, weil es eine **Abbrechung** ist, kein
     * Wunsch. Die Semantik des Signals muss der Koordinator kennen,
     * sonst wuerde er es wie einen neuen Start behandeln.
     */
    val cancellations: SharedFlow<Unit>

    /** Meldet, dass ein laufender Drop-Rest-Plan nicht mehr gilt. */
    fun cancelForUndo()
}
