package com.dropsync.domain.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

/**
 * Verifikation Marker/Waveform-Plan Phase 2: Bucket-Bildung und
 * Energie-Berechnung gegen synthetische PCM-Fixtures (Sinuston, Stille,
 * sprunghafter Pegelanstieg).
 */
class TrackAnalysisMathTest {
    // --- WaveformAccumulator ---

    @Test
    fun `Stille liefert nur Null-Buckets`() {
        val accumulator = WaveformAccumulator(totalSamples = 1_000L, bucketCount = 10)
        repeat(1_000) { accumulator.accept(0.0) }

        val buckets = accumulator.finish()

        assertEquals(10, buckets.size)
        buckets.forEach {
            assertEquals(0, it.min.toInt())
            assertEquals(0, it.max.toInt())
        }
    }

    @Test
    fun `Vollpegel-Sinus fuellt Buckets symmetrisch bis an die Int8-Grenzen`() {
        val totalSamples = 4_800L
        val accumulator = WaveformAccumulator(totalSamples, bucketCount = 8)
        // 100 Perioden ueber alle Samples: jeder Bucket sieht Berg und Tal.
        for (i in 0 until totalSamples) {
            accumulator.accept(sin(2.0 * PI * 100.0 * i / totalSamples))
        }

        val buckets = accumulator.finish()

        assertEquals(8, buckets.size)
        buckets.forEach {
            assertTrue("min zu hoch: ${it.min}", it.min <= -126)
            assertTrue("max zu niedrig: ${it.max}", it.max >= 126)
        }
    }

    @Test
    fun `Pegelsprung landet im richtigen Bucket`() {
        val accumulator = WaveformAccumulator(totalSamples = 1_000L, bucketCount = 10)
        // Erste Haelfte leise (0.1), zweite Haelfte laut (1.0).
        repeat(500) { accumulator.accept(0.1) }
        repeat(500) { accumulator.accept(1.0) }

        val buckets = accumulator.finish()

        assertEquals(13, buckets[4].max.toInt()) // 0.1 * 127 gerundet
        assertEquals(127, buckets[5].max.toInt())
        assertEquals(127, buckets[9].max.toInt())
    }

    @Test
    fun `Ueberzaehlige Samples landen im letzten Bucket statt zu ueberlaufen`() {
        val accumulator = WaveformAccumulator(totalSamples = 100L, bucketCount = 4)
        repeat(100) { accumulator.accept(0.0) }
        // Decoder lieferte mehr als geschaetzt: nur der letzte Bucket waechst.
        repeat(50) { accumulator.accept(1.0) }

        val buckets = accumulator.finish()

        assertEquals(0, buckets[2].max.toInt())
        assertEquals(127, buckets[3].max.toInt())
    }

    @Test
    fun `Fehlende Samples lassen hintere Buckets still`() {
        val accumulator = WaveformAccumulator(totalSamples = 1_000L, bucketCount = 10)
        repeat(300) { accumulator.accept(0.5) }

        val buckets = accumulator.finish()

        assertTrue(buckets[0].max > 0)
        assertEquals(0, buckets[9].max.toInt())
        assertEquals(0, buckets[9].min.toInt())
    }

    @Test
    fun `Einzelner Bucket buendelt den ganzen Track`() {
        val accumulator = WaveformAccumulator(totalSamples = 10L, bucketCount = 1)
        accumulator.accept(-0.5)
        accumulator.accept(0.25)

        val buckets = accumulator.finish()

        assertEquals(1, buckets.size)
        assertEquals((-0.5 * 127).toInt(), buckets[0].min.toInt())
        assertEquals(32, buckets[0].max.toInt()) // 0.25 * 127 gerundet
    }

    // --- EnergyAccumulator ---

    @Test
    fun `Stille hat Energie 0 in allen Fenstern`() {
        val accumulator = EnergyAccumulator(samplesPerWindow = 100)
        repeat(1_000) { accumulator.accept(0.0) }

        val windows = accumulator.finish()

        assertEquals(10, windows.size)
        windows.forEach { assertEquals(0.0, it, 1e-12) }
    }

    @Test
    fun `Vollpegel-Sinus hat RMS nahe 1 durch sqrt(2)`() {
        val samplesPerWindow = 1_000
        val accumulator = EnergyAccumulator(samplesPerWindow)
        // Ganzzahlige Periodenzahl je Fenster fuer exakten RMS-Wert.
        for (i in 0 until samplesPerWindow * 4) {
            accumulator.accept(sin(2.0 * PI * 10.0 * i / samplesPerWindow))
        }

        val windows = accumulator.finish()

        assertEquals(4, windows.size)
        windows.forEach { assertEquals(1.0 / kotlin.math.sqrt(2.0), it, 1e-3) }
    }

    @Test
    fun `Pegelsprung erzeugt deutlichen Energie-Anstieg im richtigen Fenster`() {
        val accumulator = EnergyAccumulator(samplesPerWindow = 100)
        repeat(500) { accumulator.accept(0.05) }
        repeat(500) { accumulator.accept(0.9) }

        val windows = accumulator.finish()

        assertEquals(10, windows.size)
        assertEquals(0.05, windows[4], 1e-9)
        assertEquals(0.9, windows[5], 1e-9)
        assertTrue(windows[5] / windows[4] > 10.0)
    }

    @Test
    fun `Restfenster zaehlt erst ab halber Fuellung`() {
        val short = EnergyAccumulator(samplesPerWindow = 100)
        repeat(130) { short.accept(0.5) } // Rest 30 < 50: verworfen
        assertEquals(1, short.finish().size)

        val long = EnergyAccumulator(samplesPerWindow = 100)
        repeat(160) { long.accept(0.5) } // Rest 60 >= 50: gezaehlt
        assertEquals(2, long.finish().size)
    }

    // --- WaveformCodec ---

    @Test
    fun `pack und unpack sind verlustfrei`() {
        val buckets =
            listOf(
                WaveformBucket(min = -128, max = 127),
                WaveformBucket(min = 0, max = 0),
                WaveformBucket(min = -1, max = 64),
            )

        assertEquals(buckets, WaveformCodec.unpack(WaveformCodec.pack(buckets)))
    }

    @Test
    fun `unpack verwirft BLOBs ungerader Laenge`() {
        assertTrue(WaveformCodec.unpack(byteArrayOf(1, 2, 3)).isEmpty())
    }
}
