package com.dropsync.domain.audio

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/**
 * Phase-0-Baseline des Waveform-Performance-Umbauplans: wie viel Zeit
 * kosten die Akkumulatoren fuer einen typischen Track, und wie verteilt
 * sich diese Zeit auf sie?
 *
 * **Was dieser Test messen kann und was nicht.** Er misst die reine
 * Signalmathematik in `:domain:audio` auf der JVM. Der MediaCodec-Decode
 * laeuft nur auf Android und ist hier nicht enthalten. Die Messung
 * liefert damit die **Untergrenze** der Analysezeit: schneller als das
 * kann kein Lauf sein, auch mit unendlich schnellem Decoder. Der
 * Decode-Anteil bleibt offen und braucht eine Messung auf Geraet
 * (Logcat-Tag `TrackAnalysisTiming`, Plan Phase 0).
 *
 * Genau diese Untergrenze entscheidet aber Abbruchkriterium A1 des Plans:
 * kosten die Akkumulatoren nur einen Bruchteil des 1,5-s-Ziels, ist die
 * Block-API-Umstellung (Phase 1) verfrueht und Decode/Dispatch sind die
 * lohnenderen Ziele.
 *
 * Referenztrack laut Plan: 4 Minuten, 44,1 kHz. Als Mono-Downmix sind das
 * 10,58 Mio Samples — der Decode liefert Stereo, aber die Akkumulatoren
 * sehen nur den Downmix.
 */
class TrackAnalysisBaselineTest {
    private val sampleRateHz = 44_100
    private val trackSeconds = 240
    private val monoSamples = sampleRateHz * trackSeconds

    /**
     * Musikaehnliches Signal: Grundton plus Obertoene, moduliert von einer
     * Beat-Huellkurve. Nicht Stille (die kuerzte Codepfade ab) und nicht
     * Rauschen (das laesst die Chroma-Fenster anders laufen).
     */
    private fun musicLikeSamples(): DoubleArray {
        val random = Random(seed = 1337)
        val samples = DoubleArray(monoSamples)
        val beatPeriod = sampleRateHz / 2 // 120 BPM
        for (i in samples.indices) {
            val t = i.toDouble() / sampleRateHz
            val tone =
                sin(2 * PI * 220.0 * t) * 0.5 +
                    sin(2 * PI * 330.0 * t) * 0.3 +
                    sin(2 * PI * 440.0 * t) * 0.2
            val beatPhase = (i % beatPeriod).toDouble() / beatPeriod
            val envelope = if (beatPhase < 0.1) 1.0 else 0.35
            samples[i] = (tone * envelope + (random.nextDouble() - 0.5) * 0.05).coerceIn(-1.0, 1.0)
        }
        return samples
    }

    @Test
    fun `phase-0 baseline der akkumulatoren`() {
        val samples = musicLikeSamples()

        // Aufwaermen, damit die JIT-Kompilierung nicht in die Messung faellt.
        warmup(samples)

        val waveformMs = measure { feedWaveform(samples) }
        val energyMs = measure { feedEnergy(samples) }
        val tempoMs = measure { feedTempo(samples) }
        val chromaMs = measure { feedChroma(samples) }
        val loudnessMs = measure { feedLoudness(samples) }
        val combinedMs = measure { feedCombined(samples) }
        val waveformOnlyMs = measure { feedWaveform(samples) }

        val audioMs = trackSeconds * 1_000.0

        println("=== Phase-0-Baseline: Akkumulatoren, 4-min-Track @ 44,1 kHz ===")
        println("(nur Signalmathematik; MediaCodec-Decode NICHT enthalten)")
        println()
        report("Waveform + Peak", waveformMs, audioMs)
        report("Energy (RMS)", energyMs, audioMs)
        report("Tempo (Energy + Onsets)", tempoMs, audioMs)
        report("Chroma (Goertzel)", chromaMs, audioMs)
        report("Loudness (LUFS/TruePeak)", loudnessMs, audioMs)
        println()
        report("KOMBINIERT (heutiger Pfad)", combinedMs, audioMs)
        report("Stufe 1 allein (nur Waveform)", waveformOnlyMs, audioMs)
        println()
        println(
            "Ersparnis Stufe-1-Trennung: %.0f ms (%.0f %% des kombinierten Laufs)".format(
                combinedMs - waveformOnlyMs,
                (combinedMs - waveformOnlyMs) / combinedMs * 100,
            ),
        )
        println("Plan-Ziel Cache-Miss bis sichtbare Waveform: 1500 ms")

        reportChromaBreakdown()

        // Waechter, kein Benchmark: bricht der Durchsatz katastrophal ein
        // (etwa durch eine Allokation je Sample), faellt es hier auf.
        assertTrue(
            "Akkumulatoren langsamer als Echtzeit: ${"%.0f".format(combinedMs)} ms fuer ${trackSeconds}s",
            combinedMs < audioMs,
        )
    }

    /**
     * Prueft Flaschenhals #3 des Plans nach: "berechnet den
     * `cos()`-Koeffizienten pro Fenster neu (~74 000 `cos()`-Aufrufe fuer
     * 4 min), obwohl er nur von der Tonhoehe abhaengt". Die Anzahl stimmt —
     * die Frage ist, ob sie ins Gewicht faellt.
     */
    private fun reportChromaBreakdown() {
        val decimation = ChromaAccumulator.DEFAULT_DECIMATION
        val windowSamples = ChromaAccumulator.DEFAULT_WINDOW_SAMPLES
        val windows = monoSamples / decimation / windowSamples
        val goertzelRuns = windows * PITCH_CLASSES * OCTAVE_COUNT
        val cosCalls = goertzelRuns
        val innerIterations = goertzelRuns.toLong() * windowSamples

        // Kosten der cos()-Aufrufe allein, gleiche Anzahl wie im Analyselauf.
        var sink = 0.0
        val cosMs =
            measure {
                repeat(cosCalls) { sink += kotlin.math.cos(it.toDouble()) }
            }
        require(sink.isFinite())

        println()
        println("--- Chroma-Aufschluesselung ---")
        println("Fenster: $windows, Goertzel-Laeufe: $goertzelRuns, cos()-Aufrufe: $cosCalls")
        println("Innere Schleifendurchlaeufe: $innerIterations")
        println("cos()-Aufrufe allein: %.1f ms".format(cosMs))
        println(
            "=> Vorberechnung der Koeffizienten spart hoechstens %.1f ms".format(cosMs),
        )
    }

    private fun report(
        label: String,
        elapsedMs: Double,
        audioMs: Double,
    ) {
        println(
            "%-32s %8.0f ms   %6.1fx Echtzeit".format(
                label,
                elapsedMs,
                audioMs / elapsedMs,
            ),
        )
    }

    private fun measure(block: () -> Unit): Double {
        val startNs = System.nanoTime()
        block()
        return (System.nanoTime() - startNs) / 1_000_000.0
    }

    private fun warmup(samples: DoubleArray) {
        val slice = samples.copyOfRange(0, sampleRateHz * 10)
        feedCombined(slice)
        feedWaveform(slice)
    }

    private fun feedWaveform(samples: DoubleArray) {
        val waveform = WaveformAccumulator(samples.size.toLong(), BUCKET_COUNT)
        for (sample in samples) waveform.accept(sample)
        waveform.finish()
        waveform.peak()
    }

    private fun feedEnergy(samples: DoubleArray) {
        val energy = EnergyAccumulator(samplesPerWindow = sampleRateHz * 25 / 1_000)
        for (sample in samples) energy.accept(sample)
        energy.finish()
    }

    private fun feedTempo(samples: DoubleArray) {
        val tempo = TempoAccumulator(sampleRateHz = sampleRateHz)
        for (sample in samples) tempo.accept(sample)
        tempo.finishEstimate()
    }

    private fun feedChroma(samples: DoubleArray) {
        val chroma = ChromaAccumulator(sampleRateHz = sampleRateHz)
        for (sample in samples) chroma.accept(sample)
        chroma.finishEstimate()
    }

    private fun feedLoudness(samples: DoubleArray) {
        val loudness = LoudnessAccumulator(sampleRateHz = sampleRateHz)
        for (sample in samples) loudness.accept(sample)
        loudness.integratedLufs()
    }

    /** Genau die Kombination, die `TrackAnalyzerImpl.feed()` heute fuettert. */
    private fun feedCombined(samples: DoubleArray) {
        val waveform = WaveformAccumulator(samples.size.toLong(), BUCKET_COUNT)
        val tempo = TempoAccumulator(sampleRateHz = sampleRateHz)
        val chroma = ChromaAccumulator(sampleRateHz = sampleRateHz)
        val loudness = LoudnessAccumulator(sampleRateHz = sampleRateHz)
        for (sample in samples) {
            waveform.accept(sample)
            tempo.accept(sample)
            chroma.accept(sample)
            loudness.accept(sample)
        }
        waveform.finish()
        tempo.finishEstimate()
        chroma.finishEstimate()
        loudness.integratedLufs()
    }

    private companion object {
        /** Wie `TrackAnalyzerImpl.BUCKET_COUNT`. */
        const val BUCKET_COUNT = 256
        const val PITCH_CLASSES = 12
        const val OCTAVE_COUNT = 3
    }
}
