package com.dropsync.data.playback

import com.dropsync.domain.playback.RestDuckingGate

/**
 * Rest-Ducking-Gate auf dem Preamp-Knoten der DSP-Kette (Design
 * Phase 7): aktiv = [DuckingTarget.setRestDuckDb] mit dem Wert aus der
 * DSP-Konfiguration, inaktiv = 0 dB. Die Rampe (Attack/Release) laeuft
 * im Pipeline-Ticker; der Audiothread liest nur den Zielwert.
 */
class RestDuckingGateImpl(
    private val target: DuckingTarget,
) : RestDuckingGate {
    override suspend fun setActive(active: Boolean) {
        target.setRestDuckDb(if (active) target.restDuckDb() else 0.0)
    }
}
