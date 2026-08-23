package com.dropsync.feature.progress

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

/** Zahlenformate nach CONTEXT E4c / UI-Vertrag R2b. */
class ProgressFormattersTest {
    private val de = Locale.GERMANY

    @Test
    fun `ganzzahliges Gewicht ohne Dezimalstelle — nie 95,0 kg`() {
        assertEquals("95 kg", ProgressFormatters.weight(95.0, de))
    }

    @Test
    fun `krummer Wert mit einer Dezimalstelle`() {
        assertEquals("92,5 kg", ProgressFormatters.weight(92.5, de))
    }

    @Test
    fun `englisches Locale nutzt Punkt als Dezimaltrenner`() {
        assertEquals("92.5 kg", ProgressFormatters.weight(92.5, Locale.US))
    }

    @Test
    fun `Volumen unter 1000 kg ganzzahlig in kg`() {
        assertEquals("840 kg", ProgressFormatters.volume(840.0, de))
    }

    @Test
    fun `Volumen ab 1000 kg in Tonnen mit einer Stelle`() {
        assertEquals("12,4 t", ProgressFormatters.volume(12_400.0, de))
    }

    @Test
    fun `Volumen-Schwellwert greift am gerundeten Wert`() {
        assertEquals("1,0 t", ProgressFormatters.volume(999.6, de))
    }

    @Test
    fun `Gewicht mit Reps`() {
        assertEquals("80 kg × 5", ProgressFormatters.weightTimesReps(80.0, 5, de))
    }

    @Test
    fun `Gewicht mit Reps, krumm`() {
        assertEquals("92,5 kg × 8", ProgressFormatters.weightTimesReps(92.5, 8, de))
    }
}
