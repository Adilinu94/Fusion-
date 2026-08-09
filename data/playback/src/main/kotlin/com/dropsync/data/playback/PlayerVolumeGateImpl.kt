package com.dropsync.data.playback

import com.dropsync.data.audio.AudioPipeline
import com.dropsync.domain.playback.PlayerVolumeGate

/**
 * Lautstaerkezugriff des Cue-Duckings (Bauplan 5.3, Plan Phase 1.5):
 * seit dem Audio-Engine-Ausbau setzt das Gate nicht mehr die
 * Player-Lautstaerke, sondern den Ducking-Gain am Preamp-Knoten der
 * 64-Bit-DSP-Kette. Dadurch kollidiert Cue-Ducking weder mit der
 * Nutzer-/Systemlautstaerke noch mit der digitalen Lautstaerke (DVC);
 * der Basiswert ist ausserhalb einer Ansage stets 1.0.
 */
class PlayerVolumeGateImpl(
    private val pipeline: AudioPipeline,
) : PlayerVolumeGate {
    override suspend fun currentVolume(): Float = pipeline.duckingGain.value.toFloat()

    override suspend fun setVolume(volume: Float) {
        pipeline.setDuckingGain(volume.toDouble())
    }
}
