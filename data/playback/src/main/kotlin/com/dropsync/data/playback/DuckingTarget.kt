package com.dropsync.data.playback

import com.dropsync.data.audio.AudioPipeline
import kotlinx.coroutines.flow.StateFlow

/**
 * Schmaler Port auf den Preamp-/Ducking-Knoten der 64-Bit-DSP-Kette
 * (Audit 2026-09-22): [PlayerVolumeGateImpl] (Cue-Ducking) und
 * [RestDuckingGateImpl] (Rest-Ducking) brauchen nur Gain und
 * Rest-Ducking-Zielwert. Der Port macht die beiden Gates ohne
 * AudioPipeline-Instanz pruefbar (Fake statt Mocking-Framework).
 */
interface DuckingTarget {
    /** Aktueller Ducking-Gain des Preamp-Knotens (Basis 1.0). */
    val duckingGain: StateFlow<Double>

    fun setDuckingGain(gain: Double)

    /** Konfigurierter Rest-Ducking-Wert in dB (negativ). */
    fun restDuckDb(): Double

    fun setRestDuckDb(db: Double)
}

/** Produktions-Adapter auf die echte [AudioPipeline]. */
class AudioPipelineDuckingTarget(
    private val pipeline: AudioPipeline,
) : DuckingTarget {
    override val duckingGain: StateFlow<Double> get() = pipeline.duckingGain

    override fun setDuckingGain(gain: Double) = pipeline.setDuckingGain(gain)

    override fun restDuckDb(): Double = pipeline.currentConfig.value.restDuckDb

    override fun setRestDuckDb(db: Double) = pipeline.setRestDuckDb(db)
}
