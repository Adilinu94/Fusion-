package com.dropsync.core.designsystem.chart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifikation Marker/Waveform-Plan Phase 3: Bucket-Array -> Canvas-
 * Koordinaten bei Rand- und Extremfaellen (1 Bucket, sehr viele Buckets,
 * leere Liste).
 */
class WaveformBucketMappingTest {
    @Test
    fun `leere Liste liefert keine Balken`() {
        assertTrue(WaveformMapping.mapToBars(emptyList(), width = 100f, height = 50f).isEmpty())
    }

    @Test
    fun `keine Balken bei ungueltiger Flaeche`() {
        val buckets = listOf(-0.5f to 0.5f)
        assertTrue(WaveformMapping.mapToBars(buckets, width = 0f, height = 50f).isEmpty())
        assertTrue(WaveformMapping.mapToBars(buckets, width = 100f, height = 0f).isEmpty())
    }

    @Test
    fun `ein Bucket fuellt die Breite und liegt symmetrisch um die Mitte`() {
        val bars = WaveformMapping.mapToBars(listOf(-1f to 1f), width = 100f, height = 60f)

        assertEquals(1, bars.size)
        val bar = bars[0]
        assertEquals(0f, bar.left, 1e-6f)
        assertEquals(75f, bar.width, 1e-6f) // 100 * (1 - 0.25) Standard-Gap
        assertEquals(0f, bar.top, 1e-6f)
        assertEquals(60f, bar.height, 1e-6f)
    }

    @Test
    fun `stiller Bucket behaelt Mindesthoehe von einem Pixel`() {
        val bars = WaveformMapping.mapToBars(listOf(0f to 0f), width = 10f, height = 40f)

        assertEquals(1, bars.size)
        assertEquals(20f, bars[0].top, 1e-6f)
        assertEquals(1f, bars[0].height, 1e-6f)
    }

    @Test
    fun `halbpegel wird linear auf die Hoehe abgebildet`() {
        val bars = WaveformMapping.mapToBars(listOf(-0.5f to 0.5f), width = 10f, height = 100f)

        assertEquals(25f, bars[0].top, 1e-6f) // 50 - 0.5 * 50
        assertEquals(50f, bars[0].height, 1e-6f)
    }

    @Test
    fun `viele Buckets bleiben mindestens einen Pixel breit und ueberlappen die Flaeche nicht`() {
        val buckets = List(10_000) { -0.3f to 0.3f }

        val bars = WaveformMapping.mapToBars(buckets, width = 500f, height = 50f)

        assertEquals(10_000, bars.size)
        bars.forEach { assertTrue(it.width >= 1f) }
        assertEquals(0f, bars.first().left, 1e-3f)
        // Slots verteilen sich exakt ueber die Breite.
        assertEquals(500f - 500f / 10_000, bars.last().left, 1e-1f)
    }

    @Test
    fun `werte ausserhalb des Bereichs werden geklemmt`() {
        val bars = WaveformMapping.mapToBars(listOf(-5f to 5f), width = 10f, height = 80f)

        assertEquals(0f, bars[0].top, 1e-6f)
        assertEquals(80f, bars[0].height, 1e-6f)
    }

    @Test
    fun `fractionAt klemmt auf den gueltigen Bereich`() {
        assertEquals(0f, WaveformMapping.fractionAt(-10f, 100f), 1e-6f)
        assertEquals(0.5f, WaveformMapping.fractionAt(50f, 100f), 1e-6f)
        assertEquals(1f, WaveformMapping.fractionAt(250f, 100f), 1e-6f)
        assertEquals(0f, WaveformMapping.fractionAt(50f, 0f), 1e-6f)
    }

    @Test
    fun `nearestMarkerIndex findet den naechsten Tick innerhalb des Slops`() {
        val markers = listOf(0.2f, 0.5f, 0.8f)

        assertEquals(1, WaveformMapping.nearestMarkerIndex(markers, 0.51f))
        assertEquals(0, WaveformMapping.nearestMarkerIndex(markers, 0.2f))
        assertEquals(2, WaveformMapping.nearestMarkerIndex(markers, 0.79f))
    }

    @Test
    fun `nearestMarkerIndex ausserhalb des Slops liefert minus eins`() {
        val markers = listOf(0.2f, 0.5f)

        assertEquals(-1, WaveformMapping.nearestMarkerIndex(markers, 0.4f))
        assertEquals(-1, WaveformMapping.nearestMarkerIndex(markers, 0.9f))
        assertEquals(-1, WaveformMapping.nearestMarkerIndex(emptyList(), 0.5f))
    }
}
