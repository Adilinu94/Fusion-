package com.dropsync.feature.workout

import com.dropsync.domain.sensor.RepCountPlausibility

/**
 * Anzeigbare Fassung der Autokorrelations-Zweitmeinung (Umbauplan 2026-09-04
 * Phase 7). Traegt beide Zahlen, damit die UI keine davon aus einer zweiten
 * Quelle nachladen muss — der Nutzer darf die Rep-Zahl vor dem Loggen
 * korrigieren, und ein Hinweis, der eine inzwischen geaenderte Zahl zitiert,
 * behauptet eine Aussage, die die Pruefung nie gemacht hat.
 *
 * Es gibt bewusst **keinen** Zustand fuer "Pruefung war ergebnislos" und
 * keinen fuer "Pruefung bestanden": ein `null`-Hint heisst **keine Aussage**.
 * Ein eigener Zustand wuerde in der UI unweigerlich als "geprueft und in
 * Ordnung" gelesen werden, und genau das darf die Anzeige nie behaupten — die
 * Pruefung ist bei kurzen oder unregelmaessigen Saetzen stumm
 * (`RepCountPlausibility`: MIN_SAMPLES = 150, MIN_PERIODICITY = 0.25), und
 * Schweigen ist dort kein Freispruch.
 */
data class PlausibilityHint(
    /** Der Stand, den die Peak-Detektion gezaehlt hat. */
    val countedReps: Int,
    /** Die aus der Signalperiodik geschaetzte Zahl. */
    val estimatedReps: Int,
)

/**
 * Wandelt ein Pruefergebnis in einen Hinweis — oder in null, wenn nichts zu
 * sagen ist.
 *
 * Gezeigt wird nur bei [RepCountPlausibility.Verdict.SUSPICIOUS], also ab zwei
 * Wiederholungen Abweichung. BORDERLINE (genau eine) bleibt stumm: die letzte
 * Wiederholung ist am Set-Ende regelmaessig unvollstaendig im Fenster
 * (`RepCountPlausibility.kt` rundet deshalb die Schaetzung), ein Hinweis bei
 * jeder Ein-Rep-Abweichung waere also meist falsch und wuerde abstumpfen.
 *
 * **Abweichung vom Umbauplan 7.6:** der Plan nannte "Abweichung > 0" als
 * Schwelle, ausdruecklich um Abstumpfung zu vermeiden. Das schliesst aber
 * BORDERLINE ein und macht damit genau den haeufigsten Normalfall zum
 * Hinweis — der Plan widerspricht an dieser Stelle seiner eigenen Begruendung.
 */
internal fun RepCountPlausibility.Result.toHintOrNull(): PlausibilityHint? {
    if (verdict != RepCountPlausibility.Verdict.SUSPICIOUS) return null
    val estimated = estimatedReps ?: return null
    return PlausibilityHint(countedReps = countedReps, estimatedReps = estimated)
}
