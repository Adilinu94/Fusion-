package com.dropsync.domain.audio

import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sin

/** Qualitaet des konfigurierbaren Resamplers (Plan Phase 2). */
enum class ResamplerQuality {
    /** Lineare Interpolation: guenstig, fuer Sprache/Podcasts ausreichend. */
    LINEAR,

    /** Windowed-Sinc (Hann, 32 Taps): Standard fuer Musik. */
    SINC,
}

/**
 * Streamender Resampler fuer interleaved 64-Bit-Doubles. Haelt je Kanal
 * eine Historie, damit Blockgrenzen keine Artefakte erzeugen. Beim
 * Heruntertakten wird die Sinc-Grenzfrequenz auf die Zielrate skaliert
 * (Anti-Aliasing).
 */
class StreamingResampler(
    private val sourceRateHz: Int,
    private val targetRateHz: Int,
    private val channelCount: Int,
    private val quality: ResamplerQuality,
) {
    private val step = sourceRateHz.toDouble() / targetRateHz.toDouble()

    /** Anzahl Nachbarsamples je Seite. */
    private val halfTaps = if (quality == ResamplerQuality.SINC) 16 else 1

    /** Anti-Aliasing: Grenzfrequenz relativ zur Quellrate. */
    private val cutoff = min(1.0, targetRateHz.toDouble() / sourceRateHz.toDouble())

    /** Historie je Kanal: die letzten 2*halfTaps Frames. */
    private val history = Array(channelCount) { DoubleArray(2 * halfTaps) }

    /** Leseposition in Frames relativ zum Beginn der Historie. */
    private var position = halfTaps.toDouble()

    private var framesBuffered = 0

    /** Maximale Ausgabeframes fuer [inputFrames] Eingabeframes. */
    fun maxOutputFrames(inputFrames: Int): Int = (inputFrames / step).toInt() + 2

    /**
     * Verarbeitet [inputFrames] Frames aus [input] (interleaved) und
     * schreibt Ausgabeframes nach [output]; liefert die Framezahl.
     */
    fun process(
        input: DoubleArray,
        inputFrames: Int,
        output: DoubleArray,
    ): Int {
        val historyFrames = 2 * halfTaps
        val totalFrames = framesBuffered.coerceAtMost(historyFrames) + inputFrames
        // Arbeitspuffer: Historie plus neuer Block, je Kanal fortlaufend.
        val work = Array(channelCount) { DoubleArray(totalFrames) }
        for (channel in 0 until channelCount) {
            val kept = framesBuffered.coerceAtMost(historyFrames)
            System.arraycopy(history[channel], historyFrames - kept, work[channel], 0, kept)
            for (frame in 0 until inputFrames) {
                work[channel][kept + frame] = input[frame * channelCount + channel]
            }
        }
        val kept = framesBuffered.coerceAtMost(historyFrames)

        var outFrames = 0
        // Es darf nur interpoliert werden, solange rechts genug Kontext da ist.
        val limit = (totalFrames - halfTaps).toDouble()
        while (position < limit) {
            for (channel in 0 until channelCount) {
                output[outFrames * channelCount + channel] = interpolate(work[channel], position)
            }
            outFrames++
            position += step
        }

        // Historie fuer den naechsten Block sichern.
        for (channel in 0 until channelCount) {
            val start = (totalFrames - historyFrames).coerceAtLeast(0)
            val copy = totalFrames - start
            System.arraycopy(work[channel], start, history[channel], historyFrames - copy, copy)
        }
        position -= (totalFrames - historyFrames).coerceAtLeast(0).toDouble()
        framesBuffered = (kept + inputFrames).coerceAtMost(historyFrames)
        return outFrames
    }

    private fun interpolate(
        data: DoubleArray,
        at: Double,
    ): Double {
        val center = floor(at).toInt()
        if (quality == ResamplerQuality.LINEAR) {
            val fraction = at - center
            val a = data.getOrElse(center) { 0.0 }
            val b = data.getOrElse(center + 1) { 0.0 }
            return a + (b - a) * fraction
        }
        var sum = 0.0
        var weightSum = 0.0
        for (k in center - halfTaps + 1..center + halfTaps) {
            if (k < 0 || k >= data.size) continue
            val x = (at - k) * cutoff
            val sinc = if (x == 0.0) 1.0 else sin(PI * x) / (PI * x)
            // Hann-Fenster ueber der Tap-Distanz.
            val distance = (at - k) / halfTaps
            if (distance <= -1.0 || distance >= 1.0) continue
            val window = 0.5 * (1.0 + kotlin.math.cos(PI * distance))
            val weight = sinc * window
            sum += data[k] * weight
            weightSum += weight
        }
        // Normalisierung ueber die Fenstersumme haelt den DC-Gain bei 1.
        return if (weightSum == 0.0) 0.0 else sum / weightSum
    }
}
