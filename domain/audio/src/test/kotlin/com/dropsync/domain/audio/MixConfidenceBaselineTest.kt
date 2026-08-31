package com.dropsync.domain.audio

import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/**
 * Messlauf, kein Gate: druckt die real erreichten Konfidenzwerte fuer
 * klar unterscheidbare Eingaben, damit die Schwelle in
 * [MixConfidence] auf Zahlen beruht statt auf einem Bauchwert.
 *
 * Wird nach dem Festlegen der Schwelle nicht geloescht, sondern
 * dient als Beleg: wenn eine Algorithmus-Aenderung die Verteilung
 * verschiebt, sieht man es hier zuerst.
 */
class MixConfidenceBaselineTest {
    private val sampleRate = 44_100

    @Test
    fun `konfidenz-baseline drucken`() {
        println("=== TempoAccumulator confidence baseline ===")
        report("klarer 120-BPM-Beat (Bursts)") { beatTrain(bpm = 120, seconds = 20) }
        report("klarer 160-BPM-Beat (Bursts)") { beatTrain(bpm = 160, seconds = 20) }
        report("Beat mit 8% Jitter") { jitteredBeatTrain(bpm = 128, seconds = 20, jitter = 0.08) }
        report("Beat mit 20% Jitter") { jitteredBeatTrain(bpm = 128, seconds = 20, jitter = 0.20) }
        report("weisses Rauschen (kein Puls)") { whiteNoise(seconds = 20) }
        report("Sprache-aehnlich (langsame Huellkurve)") { speechLike(seconds = 20) }
        report("Ambient-Flaeche (Dauerton)") { steadyTone(seconds = 20) }

        println("=== ChromaAccumulator confidence baseline ===")
        reportKey("A-Moll-Dreiklang") { triad(listOf(220.0, 261.63, 329.63), seconds = 12) }
        reportKey("C-Dur-Dreiklang") { triad(listOf(261.63, 329.63, 392.0), seconds = 12) }
        reportKey("weisses Rauschen") { whiteNoise(seconds = 12) }
    }

    private fun report(
        label: String,
        signal: () -> Sequence<Double>,
    ) {
        val tempo = TempoAccumulator(sampleRateHz = sampleRate)
        signal().forEach { tempo.accept(it) }
        val estimate = tempo.finishEstimate()
        if (estimate == null) {
            println("%-34s -> null (kein Estimate)".format(label))
        } else {
            println(
                "%-34s -> bpm=%6.1f confidence=%.4f".format(
                    label,
                    estimate.bpm,
                    estimate.confidence,
                ),
            )
        }
    }

    private fun reportKey(
        label: String,
        signal: () -> Sequence<Double>,
    ) {
        val chroma = ChromaAccumulator(sampleRateHz = sampleRate)
        signal().forEach { chroma.accept(it) }
        val estimate = chroma.finishEstimate()
        if (estimate == null) {
            println("%-34s -> null (kein Estimate)".format(label))
        } else {
            println(
                "%-34s -> key=%-4s confidence=%.4f".format(
                    label,
                    estimate.camelotKey,
                    estimate.confidence,
                ),
            )
        }
    }

    private fun beatTrain(
        bpm: Int,
        seconds: Int,
    ): Sequence<Double> {
        val samplesPerBeat = (sampleRate * 60.0 / bpm).toInt()
        val burst = sampleRate / 20
        return (0 until sampleRate * seconds).asSequence().map { index ->
            if (index % samplesPerBeat < burst) 1.0 else 0.0
        }
    }

    private fun jitteredBeatTrain(
        bpm: Int,
        seconds: Int,
        jitter: Double,
    ): Sequence<Double> {
        val random = Random(seed = 42)
        val nominal = sampleRate * 60.0 / bpm
        val burst = sampleRate / 20
        val onsets = mutableListOf<Int>()
        var position = 0.0
        while (position < sampleRate * seconds) {
            onsets += position.toInt()
            position += nominal * (1.0 + (random.nextDouble() * 2 - 1) * jitter)
        }
        val onsetSet = onsets.toSet()
        var remainingBurst = 0
        return (0 until sampleRate * seconds).asSequence().map { index ->
            if (index in onsetSet) remainingBurst = burst
            if (remainingBurst > 0) {
                remainingBurst--
                1.0
            } else {
                0.0
            }
        }
    }

    private fun whiteNoise(seconds: Int): Sequence<Double> {
        val random = Random(seed = 7)
        return (0 until sampleRate * seconds).asSequence().map { random.nextDouble() * 2 - 1 }
    }

    /** Langsame Amplitudenschwankung ohne festes Raster (Sprache/Podcast). */
    private fun speechLike(seconds: Int): Sequence<Double> {
        val random = Random(seed = 11)
        return (0 until sampleRate * seconds).asSequence().map { index ->
            val t = index.toDouble() / sampleRate
            val envelope = (sin(2 * PI * 0.7 * t) + sin(2 * PI * 1.3 * t + 0.4)) / 2.0
            (random.nextDouble() * 2 - 1) * envelope.coerceAtLeast(0.0)
        }
    }

    private fun steadyTone(seconds: Int): Sequence<Double> =
        (0 until sampleRate * seconds).asSequence().map { index ->
            0.5 * sin(2 * PI * 220.0 * index / sampleRate)
        }

    private fun triad(
        frequencies: List<Double>,
        seconds: Int,
    ): Sequence<Double> =
        (0 until sampleRate * seconds).asSequence().map { index ->
            frequencies.sumOf { f -> sin(2 * PI * f * index / sampleRate) } / frequencies.size
        }
}
