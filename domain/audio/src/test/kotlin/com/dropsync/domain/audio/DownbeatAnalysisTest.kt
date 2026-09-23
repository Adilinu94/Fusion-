package com.dropsync.domain.audio

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin

/**
 * B4/RC-22: Phase des Beat-Rasters aus der Low-Band-Energie. Synthetische
 * Fixtures mit bekanntem Kick-Zeitpunkt; die Toleranzen decken die
 * Phasen-Aufloesung ([DownbeatAccumulator.PHASE_STEPS] ≈ 10 ms bei
 * 120 BPM) und die Fensterbreite ab.
 */
class DownbeatAnalysisTest {
    @Test
    fun `vier-vier-klick mit bassdrum auf schlag eins liefert phase eins`() {
        // 120 BPM: Kick exakt auf dem Raster (Phase 1, 0-basiert 0).
        val estimate = estimateFor(track(bpm = 120f, kickOffsetMs = 0L), bpm = 120f)

        assertNotNull("Klarer Kick muss eine Aussage liefern", estimate)
        assertTrue(
            "Offset ${estimate!!.offsetMs} ms liegt nicht in der ersten Phase",
            estimate.offsetMs <= TOLERANCE_MS,
        )
        assertNotNull(
            "Konfidenz muss das Snap-Gate passieren",
            DownbeatConfidence.acceptOffset(estimate.offsetMs, estimate.confidence),
        )
    }

    @Test
    fun `verschobener bass liefert die verschobene phase`() {
        // 120 BPM, Kick 140 ms nach dem 0-ms-Raster (≈ Phase 13/48).
        val estimate = estimateFor(track(bpm = 120f, kickOffsetMs = 140L), bpm = 120f)

        assertNotNull(estimate)
        assertTrue(
            "Offset ${estimate!!.offsetMs} ms sollte bei ~140 ms liegen",
            abs(estimate.offsetMs - 140L) <= TOLERANCE_MS,
        )
    }

    @Test
    fun `gegenprobe offset null ist schlechter als die messung`() {
        val trueOffsetMs = 140L
        val estimate = estimateFor(track(bpm = 120f, kickOffsetMs = trueOffsetMs), bpm = 120f)

        assertNotNull(estimate)
        val measuredError = abs(estimate!!.offsetMs - trueOffsetMs)
        val guessedError = abs(0L - trueOffsetMs)
        assertTrue(
            "Gemessener Offset ($measuredError ms Fehler) muss besser sein als 0 ms " +
                "($guessedError ms Fehler)",
            measuredError < guessedError,
        )
    }

    @Test
    fun `ohne bass keine aussage`() {
        // Nur Hoehen-Klicks (2 kHz): kein Low-Band-Anteil -> kein Raster.
        val estimate = estimateFor(track(bpm = 120f, kickOffsetMs = 0L, withKick = false), bpm = 120f)

        assertNull(
            "Ohne Bass darf kein Offset entstehen",
            DownbeatConfidence.acceptOffset(estimate?.offsetMs, estimate?.confidence),
        )
    }

    @Test
    fun `stille liefert keine aussage`() {
        val estimate = estimateFor(DoubleArray(SAMPLE_RATE), bpm = 120f)

        assertNull(estimate)
    }

    @Test
    fun `flacher bass ohne phase wird verworfen`() {
        // Konstanter 60-Hz-Sinus: Low-Band vorhanden, aber ohne Phase.
        val samples = DoubleArray(SAMPLE_RATE * 10)
        for (index in samples.indices) {
            samples[index] = 0.5 * sin(2.0 * PI * 60.0 * index / SAMPLE_RATE)
        }

        val estimate = estimateFor(samples, bpm = 120f)

        assertNull(
            "Gleichverteilte Energie darf nicht rasten",
            DownbeatConfidence.acceptOffset(estimate?.offsetMs, estimate?.confidence),
        )
    }

    @Test
    fun `ohne bpm keine aussage`() {
        val estimate = estimateFor(track(bpm = 120f, kickOffsetMs = 0L), bpm = null)

        assertNull(estimate)
    }

    @Test
    fun `feine phase wird nicht auf viertel-beats gerundet`() {
        // 25 ms Versatz bei 120 BPM: eine 4-Phasen-Heuristik (Research)
        // haette 0 ms geliefert; das 48er-Raster loest ihn auf.
        val estimate = estimateFor(track(bpm = 120f, kickOffsetMs = 25L), bpm = 120f)

        assertNotNull(estimate)
        assertTrue(
            "Offset ${estimate!!.offsetMs} ms sollte bei ~25 ms liegen",
            estimate.offsetMs in 10L..40L,
        )
    }

    private fun estimateFor(
        samples: DoubleArray,
        bpm: Float?,
    ): DownbeatEstimate? {
        val accumulator = DownbeatAccumulator(sampleRateHz = SAMPLE_RATE)
        samples.forEach { accumulator.accept(it) }
        return accumulator.finish(bpm)
    }

    /**
     * 4/4-Klick-Spur: 2-kHz-Klick auf jedem Beat, optional Bassdrum
     * (60 Hz, exponentiell abklingend) um [kickOffsetMs] versetzt.
     */
    private fun track(
        bpm: Float,
        kickOffsetMs: Long,
        durationMs: Long = 20_000,
        withKick: Boolean = true,
    ): DoubleArray {
        val samples = DoubleArray((durationMs * SAMPLE_RATE / 1_000).toInt())
        val beatMs = 60_000.0 / bpm
        var beat = 0
        while (true) {
            val beatStartMs = beat * beatMs
            if (beatStartMs >= durationMs) break
            addClick(samples, beatStartMs)
            if (withKick) addKick(samples, beatStartMs + kickOffsetMs)
            beat++
        }
        return samples
    }

    private fun addClick(
        samples: DoubleArray,
        startMs: Double,
    ) {
        val start = (startMs * SAMPLE_RATE / 1_000).toInt()
        val length = 15 * SAMPLE_RATE / 1_000
        for (index in 0 until length) {
            val target = start + index
            if (target !in samples.indices) break
            val envelope = 1.0 - index.toDouble() / length
            samples[target] += 0.3 * envelope * sin(2.0 * PI * 2_000.0 * index / SAMPLE_RATE)
        }
    }

    private fun addKick(
        samples: DoubleArray,
        startMs: Double,
    ) {
        val start = (startMs * SAMPLE_RATE / 1_000).toInt()
        val length = 200 * SAMPLE_RATE / 1_000
        val decaySamples = 0.08 * SAMPLE_RATE
        for (index in 0 until length) {
            val target = start + index
            if (target !in samples.indices) break
            samples[target] += 0.8 * exp(-index / decaySamples) * sin(2.0 * PI * 60.0 * index / SAMPLE_RATE)
        }
    }

    private companion object {
        const val SAMPLE_RATE = 44_100

        /** Phasen-Aufloesung (≈ 10 ms) plus halbe Fensterbreite. */
        const val TOLERANCE_MS = 25L
    }
}
