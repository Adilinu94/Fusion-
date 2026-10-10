package com.dropsync.feature.workout

import com.dropsync.domain.sensor.SetRecount

/**
 * Vorschlag aus der Nachzaehlung am Satzende ([SetRecount]): "Signalanalyse: n Wiederholungen
 * (live gezaehlt: m)" mit Uebernahme per Tipp.
 *
 * Anders als [PlausibilityHint] nennt der Vorschlag eine konkrete Zahl - er entsteht nur,
 * wenn die Analyse sich ihrer Sache sicher ist ([SetRecount.Confidence.HIGH]) und von der
 * Live-Zaehlung abweicht. Er wird nie automatisch uebernommen: erst der Tipp der Person
 * setzt die Zahl und zaehlt damit als aktive Korrektur (D3-Regel, ADR-0014).
 */
data class RecountSuggestion(
    val liveReps: Int,
    val analysisReps: Int,
)

internal fun SetRecount.Result.toSuggestionOrNull(): RecountSuggestion? =
    if (isSuggestion) RecountSuggestion(liveReps = liveCount, analysisReps = count) else null
