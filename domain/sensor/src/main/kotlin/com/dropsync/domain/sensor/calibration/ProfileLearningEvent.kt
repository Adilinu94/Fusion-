package com.dropsync.domain.sensor.calibration

/**
 * Ergebnis eines Lernversuchs nach einem geloggten Satz (RC-6/P1-12).
 *
 * Der Lernpfad lief bisher stumm — Kandidat, Rollback oder Ueberspringen
 * landeten nur im Log. Die Train-UI zeigt daraus einen kurzen Hinweis; das
 * spaetere Diagnose-Panel (P2-17) nutzt dieselbe Quelle. Reine
 * Domain-Typen ohne Android-Bezug; die Texte liegen in strings.xml.
 */
sealed interface ProfileLearningEvent {
    /**
     * Neuer Kandidat gespeichert (Revision [revision]). Er wird erst nach
     * genug validierten Sets aktiv — der Hinweis sagt deshalb "verfeinert",
     * nicht "aktiv".
     */
    data class Refined(
        val revision: Int,
    ) : ProfileLearningEvent

    /**
     * Zwei schlechte validierte Sets in Folge: zurueck zur letzten guten
     * Revision.
     */
    data object RolledBack : ProfileLearningEvent

    /**
     * Nicht gelernt: die Autokorrelations-Zweitmeinung widerspricht dem
     * bestaetigten Zaehlerstand (Signal und Eingabe passen nicht zusammen).
     */
    data object SkippedImplausible : ProfileLearningEvent

    /** Nicht gelernt: der Stream war unzuverlaessig (UNRELIABLE). */
    data object SkippedUnreliable : ProfileLearningEvent

    /**
     * T-10/S-7: Nicht gelernt, weil der Satz keine reproduzierbare
     * Periodizitaet hergibt ([CalibrationRefiner.refine] fand kein
     * Kandidatenprofil). Vorher war dieser Pfad stumm (`?: return`).
     */
    data object SkippedNotReproducible : ProfileLearningEvent
}
