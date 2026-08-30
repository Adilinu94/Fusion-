package com.dropsync.domain.sensor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

/**
 * P2-Fix #19: die Autokorrelation liefert eine vom Schwellwert UNABHAENGIGE
 * Zweitmeinung zur Rep-Zahl. Geprueft wird, dass sie eine saubere Serie
 * bestaetigt, eine klare Fehlzaehlung als verdaechtig meldet und bei
 * unbrauchbarem Signal ehrlich INCONCLUSIVE sagt statt zu raten.
 */
class RepCountPlausibilityTest {
    private val sampleRateHz = 50.0

    /** Periodisches Signal mit [reps] vollen Zyklen ueber [reps] * [periodS]. */
    private fun periodicSignal(
        reps: Int,
        periodS: Double = 2.0,
    ): DoubleArray {
        val samplesPerPeriod = (periodS * sampleRateHz).toInt()
        val total = reps * samplesPerPeriod
        return DoubleArray(total) { i ->
            60.0 * sin(2.0 * PI * i / samplesPerPeriod)
        }
    }

    @Test
    fun `saubere serie wird als konsistent bewertet`() {
        val signal = periodicSignal(reps = 8, periodS = 2.0)
        val result = RepCountPlausibility.check(signal, sampleRateHz, countedReps = 8)

        assertEquals(RepCountPlausibility.Verdict.CONSISTENT, result.verdict)
        assertNotNull(result.periodSeconds)
        assertEquals("Periode muss ~2 s sein", 2.0, result.periodSeconds!!, 0.1)
        assertEquals(8, result.estimatedReps)
        assertTrue("periodisches Signal muss hohe Periodizitaet zeigen", result.periodicityStrength > 0.5)
    }

    @Test
    fun `abweichung um eine wiederholung gilt als grenzfall`() {
        val signal = periodicSignal(reps = 8, periodS = 2.0)
        // Der Zaehler hat eine Wiederholung zu viel gemeldet - am Set-Ende ein
        // haeufiger Randeffekt, kein belastbarer Verdacht.
        val result = RepCountPlausibility.check(signal, sampleRateHz, countedReps = 9)
        assertEquals(RepCountPlausibility.Verdict.BORDERLINE, result.verdict)
    }

    @Test
    fun `doppelzaehlung wird als verdaechtig erkannt`() {
        val signal = periodicSignal(reps = 8, periodS = 2.0)
        // Klassischer Fehlerfall: zu tiefe Schwelle zaehlt jede Wiederholung
        // doppelt. Genau das soll die Autokorrelation ohne Ground Truth sehen.
        val result = RepCountPlausibility.check(signal, sampleRateHz, countedReps = 16)

        assertEquals(RepCountPlausibility.Verdict.SUSPICIOUS, result.verdict)
        assertEquals(8, result.estimatedReps)
    }

    @Test
    fun `zu kurzes signal liefert kein urteil`() {
        // MIN_SAMPLES ist 150 (~3 s).
        val signal = DoubleArray(100) { 60.0 * sin(2.0 * PI * it / 50.0) }
        val result = RepCountPlausibility.check(signal, sampleRateHz, countedReps = 2)

        assertEquals(RepCountPlausibility.Verdict.INCONCLUSIVE, result.verdict)
        assertNull(result.periodSeconds)
        assertNull(result.estimatedReps)
    }

    @Test
    fun `rauschen liefert kein urteil`() {
        // Deterministisches Pseudorauschen: keine dominante Periode.
        var seed = 42
        val signal =
            DoubleArray(600) {
                seed = seed * 1_103_515_245 + 12_345
                ((seed shr 16) and 0x7FFF) / 32_767.0 * 100.0 - 50.0
            }
        val result = RepCountPlausibility.check(signal, sampleRateHz, countedReps = 10)

        assertEquals(
            "unregelmaessiges Signal darf keine Rep-Zahl behaupten",
            RepCountPlausibility.Verdict.INCONCLUSIVE,
            result.verdict,
        )
    }

    @Test
    fun `konstantes signal liefert kein urteil`() {
        // Sensor abgerutscht: die Projektion ist flach. Die Autokorrelation
        // darf hier nicht durch Null teilen, sondern muss INCONCLUSIVE sagen.
        val signal = DoubleArray(600) { 0.0 }
        val result = RepCountPlausibility.check(signal, sampleRateHz, countedReps = 10)

        assertEquals(RepCountPlausibility.Verdict.INCONCLUSIVE, result.verdict)
        assertEquals(0.0, result.periodicityStrength, 1e-9)
    }

    @Test
    fun `ungueltige abtastrate liefert kein urteil`() {
        val signal = periodicSignal(reps = 8)
        val result = RepCountPlausibility.check(signal, sampleRateHz = 0.0, countedReps = 8)
        assertEquals(RepCountPlausibility.Verdict.INCONCLUSIVE, result.verdict)
    }

    @Test
    fun `langsame wiederholungen werden korrekt geschaetzt`() {
        // 5 s je Wiederholung (betonte Exzentrik) liegt noch im erlaubten
        // Bereich (MAX_REP_SECONDS = 8).
        val signal = periodicSignal(reps = 4, periodS = 5.0)
        val result = RepCountPlausibility.check(signal, sampleRateHz, countedReps = 4)

        assertEquals(RepCountPlausibility.Verdict.CONSISTENT, result.verdict)
        assertEquals(5.0, result.periodSeconds!!, 0.2)
    }
}
