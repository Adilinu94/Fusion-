package com.dropsync.data.playback

import com.dropsync.data.audio.AudioPipeline
import com.dropsync.domain.playback.RestDuckingGate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Rest-Ducking-Gate auf dem Preamp-Knoten der DSP-Kette (Design
 * Phase 7): aktiv = [AudioPipeline.setRestDuckDb] mit dem Wert aus der
 * DSP-Konfiguration, inaktiv = 0 dB. Die Rampe (Attack/Release) laeuft
 * im Pipeline-Ticker; der Audiothread liest nur den Zielwert.
 */
@Singleton
class RestDuckingGateImpl
    @Inject
    constructor(
        private val pipeline: AudioPipeline,
    ) : RestDuckingGate {
        override suspend fun setActive(active: Boolean) {
            val config = pipeline.currentConfig.value
            pipeline.setRestDuckDb(if (active) config.restDuckDb else 0.0)
        }
    }
