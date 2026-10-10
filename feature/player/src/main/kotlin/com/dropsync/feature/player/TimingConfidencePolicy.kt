package com.dropsync.feature.player

import com.dropsync.domain.playback.AudioRouteProfile
import com.dropsync.domain.timer.TimingConfidence

/**
 * Wie sicher ist die geplante Drop-Landung zeitlich?
 *
 * [TimingConfidence.EXACT] nur, wenn die Latenz der Ausgabe-Route lokal eingestellt oder
 * gemessen wurde ([AudioRouteProfile.Confidence.CALIBRATED]). Ein Tabellenwert
 * (ESTIMATED) ist eine Schaetzung: die Latenz eines A2DP-Geraets streut je nach Geraet und
 * Codec um 100 ms und mehr - "Timing stabil" waere eine Behauptung ohne Beleg. Ohne
 * Latenzwert gilt ebenfalls [TimingConfidence.DEGRADED] (Landung bleibt Best Effort).
 */
internal fun timingConfidenceFor(
    latencyMs: Long?,
    routeConfidence: AudioRouteProfile.Confidence?,
): TimingConfidence =
    if (latencyMs != null && routeConfidence == AudioRouteProfile.Confidence.CALIBRATED) {
        TimingConfidence.EXACT
    } else {
        TimingConfidence.DEGRADED
    }
