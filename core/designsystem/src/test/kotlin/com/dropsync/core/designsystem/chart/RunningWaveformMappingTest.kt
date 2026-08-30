package com.dropsync.core.designsystem.chart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifikation der laufenden Waveform (Poweramp-"Waveseek"-Prinzip):
 * die Track-Geometrie ist stabil, nur der Scroll-Offset haengt an der
 * Position. Die Tests sichern genau die Eigenschaften, die den fruehen
 * Fehler ("Form aendert sich bei jedem Fortschrittsschritt") ausschliessen.
 */
class RunningWaveformMappingTest {
    private val buckets = listOf(-0.2f to 0.2f, -0.9f to 0.5f, 0f to 0f, -0.4f to 1f)

    @Test
    fun `amplituden sind unabhaengig vom fortschritt und damit stabil`() {
        val first = RunningWaveformMapping.amplitudes(buckets, targetSize = 64)
        val second = RunningWaveformMapping.amplitudes(buckets, targetSize = 64)

        assertEquals(64, first.size)
        assertTrue(first.contentEquals(second))
    }

    @Test
    fun `amplituden nutzen den betragsmaessig groesseren kanal und bleiben in null bis eins`() {
        val exact = RunningWaveformMapping.amplitudes(buckets, targetSize = buckets.size)

        assertEquals(0.2f, exact[0], 1e-6f)
        assertEquals(0.9f, exact[1], 1e-6f)
        assertEquals(0f, exact[2], 1e-6f)
        assertEquals(1f, exact[3], 1e-6f)
        exact.forEach { assertTrue(it in 0f..1f) }
    }

    @Test
    fun `amplituden interpolieren linear auf ein feineres raster`() {
        // Zwei Quellwerte, drei Zielwerte -> Mittelwert in der Mitte.
        val fine = RunningWaveformMapping.amplitudes(listOf(0f to 0f, 0f to 1f), targetSize = 3)

        assertEquals(0f, fine[0], 1e-6f)
        assertEquals(0.5f, fine[1], 1e-6f)
        assertEquals(1f, fine[2], 1e-6f)
    }

    @Test
    fun `amplituden sind leer bei fehlender analyse oder ungueltiger groesse`() {
        assertTrue(RunningWaveformMapping.amplitudes(emptyList(), 64).isEmpty())
        assertTrue(RunningWaveformMapping.amplitudes(buckets, 0).isEmpty())
    }

    @Test
    fun `einzelner bucket fuellt das raster gleichmaessig`() {
        val flat = RunningWaveformMapping.amplitudes(listOf(-0.5f to 0.5f), targetSize = 8)

        assertEquals(8, flat.size)
        flat.forEach { assertEquals(0.5f, it, 1e-6f) }
    }

    @Test
    fun `rasterweite fuellt genau den sichtbaren ausschnitt`() {
        // 100 Balken, 25 Prozent sichtbar -> 25 Balken auf 500 px = 20 px Pitch.
        assertEquals(20f, RunningWaveformMapping.pitch(500f, 100, 0.25f), 1e-6f)
        assertEquals(0f, RunningWaveformMapping.pitch(0f, 100, 0.25f), 1e-6f)
        assertEquals(0f, RunningWaveformMapping.pitch(500f, 0, 0.25f), 1e-6f)
    }

    @Test
    fun `rasterweite bleibt konstant unabhaengig vom fortschritt`() {
        // Die Balkenbreite darf nicht von der Position abhaengen — genau das
        // liess die alte Implementierung die Form neu abtasten.
        val a = RunningWaveformMapping.pitch(500f, 320, 0.22f)
        val b = RunningWaveformMapping.pitch(500f, 320, 0.22f)

        assertEquals(a, b, 1e-6f)
    }

    @Test
    fun `scrollX setzt den playhead auf die vorgesehene position`() {
        val pitch = RunningWaveformMapping.pitch(500f, 100, 0.25f)
        val virtual = RunningWaveformMapping.virtualWidth(100, pitch)

        assertEquals(2000f, virtual, 1e-6f)
        // Bei 50 Prozent Fortschritt und mittigem Playhead liegt der Raster
        // so, dass Trackmitte in der Canvas-Mitte sitzt.
        val scroll = RunningWaveformMapping.scrollX(0.5f, virtual, 500f, 0.5f)
        assertEquals(1000f - 250f, scroll, 1e-6f)
    }

    @Test
    fun `scrollX waechst linear mit dem fortschritt`() {
        val virtual = 2000f
        val quarter = RunningWaveformMapping.scrollX(0.25f, virtual, 500f, 0.5f)
        val half = RunningWaveformMapping.scrollX(0.5f, virtual, 500f, 0.5f)
        val threeQuarter = RunningWaveformMapping.scrollX(0.75f, virtual, 500f, 0.5f)

        assertEquals(half - quarter, threeQuarter - half, 1e-4f)
    }

    @Test
    fun `sichtbarer indexbereich deckt genau den ausschnitt ab`() {
        val pitch = 20f
        val scroll = 100f

        val first = RunningWaveformMapping.firstVisibleIndex(scroll, pitch)
        val last = RunningWaveformMapping.lastVisibleIndex(scroll, width = 500f, pitch = pitch)

        assertEquals(5, first)
        assertEquals(30, last)
    }

    @Test
    fun `sichtbarer indexbereich ist am trackanfang negativ und wird vom aufrufer geklemmt`() {
        // Bei Fortschritt 0 liegt der halbe Ausschnitt vor dem Track.
        val scroll = RunningWaveformMapping.scrollX(0f, virtualWidth = 2000f, width = 500f, playheadFraction = 0.5f)

        assertEquals(-250f, scroll, 1e-6f)
        assertTrue(RunningWaveformMapping.firstVisibleIndex(scroll, 20f) < 0)
    }

    @Test
    fun `barLeft und fractionAtX sind zueinander invers`() {
        val pitch = 20f
        val virtual = RunningWaveformMapping.virtualWidth(100, pitch)
        val scroll = RunningWaveformMapping.scrollX(0.4f, virtual, 500f, 0.5f)

        val x = RunningWaveformMapping.barLeft(index = 40, pitch = pitch, scrollX = scroll)
        val fraction = RunningWaveformMapping.fractionAtX(x, scroll, virtual)

        assertEquals(40 * pitch / virtual, fraction, 1e-6f)
    }

    @Test
    fun `fractionAtX klemmt auf den gueltigen bereich`() {
        assertEquals(0f, RunningWaveformMapping.fractionAtX(-9999f, 0f, 2000f), 1e-6f)
        assertEquals(1f, RunningWaveformMapping.fractionAtX(9999f, 0f, 2000f), 1e-6f)
        assertEquals(0f, RunningWaveformMapping.fractionAtX(100f, 0f, 0f), 1e-6f)
    }

    @Test
    fun `tap auf den playhead aendert die position nicht`() {
        val pitch = RunningWaveformMapping.pitch(500f, 100, 0.25f)
        val virtual = RunningWaveformMapping.virtualWidth(100, pitch)
        val scroll = RunningWaveformMapping.scrollX(0.6f, virtual, 500f, 0.5f)

        val atPlayhead = RunningWaveformMapping.fractionAtX(500f * 0.5f, scroll, virtual)

        assertEquals(0.6f, atPlayhead, 1e-5f)
    }

    @Test
    fun `drag nach links laeuft vorwaerts im track`() {
        // Negatives dx (nach links ziehen) muss den Anteil erhoehen.
        assertTrue(RunningWaveformMapping.fractionDelta(-100f, 2000f) > 0f)
        assertTrue(RunningWaveformMapping.fractionDelta(100f, 2000f) < 0f)
        assertEquals(0.05f, RunningWaveformMapping.fractionDelta(-100f, 2000f), 1e-6f)
        assertEquals(0f, RunningWaveformMapping.fractionDelta(-100f, 0f), 1e-6f)
    }

    @Test
    fun `randabblendung ist an den kanten null und in der mitte voll`() {
        assertEquals(0f, RunningWaveformMapping.edgeAlpha(0f, 500f, 50f), 1e-6f)
        assertEquals(0f, RunningWaveformMapping.edgeAlpha(500f, 500f, 50f), 1e-6f)
        assertEquals(1f, RunningWaveformMapping.edgeAlpha(250f, 500f, 50f), 1e-6f)
        assertEquals(0.5f, RunningWaveformMapping.edgeAlpha(25f, 500f, 50f), 1e-6f)
    }

    @Test
    fun `randabblendung ohne blende bleibt voll deckend`() {
        assertEquals(1f, RunningWaveformMapping.edgeAlpha(0f, 500f, 0f), 1e-6f)
        assertEquals(1f, RunningWaveformMapping.edgeAlpha(10f, 0f, 50f), 1e-6f)
    }
}
