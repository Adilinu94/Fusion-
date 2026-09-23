package com.dropsync.domain.sensor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * B1 (RC-18, Stufe 1): Ein kompletter 12-Rep-Satz mit monotoner Ermuedung
 * (Prominenz -30 %, Dauer +25 %) muss vollstaendig zaehlen. Der gleitende
 * Mittelwert in `trackForAdaptation` liegt am Satzende per Konstruktion
 * neben der aktuellen Rep; die einseitige Bewertung darf das nicht in
 * verlorene Reps uebersetzen.
 *
 * Aufbau: bewaehrte fullCycle-Form (12/12/8) mit fallender Amplitude
 * (60 -> 42) und steigendem Sample-Intervall (20 -> 25 ms) — die Dauer
 * driftet ueber die Zeitbasis, nicht ueber die Zykluslaenge. Letzteres
 * kippt die Peak-Fenster-Heuristik (Fall-Debounce endet dann noch im
 * positiven Bereich), das ist ein Fixture-Artefakt und keine B1-Frage.
 *
 * Der Referenzlauf mit den Vor-B1-Toleranzen (symmetrisch 1.0) zaehlt
 * denselben Satz ebenfalls — er dokumentiert das Delta: unter der
 * einseitigen Bewertung ist der Score der letzten (ermuedeten) Reps
 * HOEHER, weil die Ermuedungsrichtung die weite Toleranz bekommt.
 */
class RepCounterFatigueDriftTest {
    private val reps = 12

    /** Vollstaendiger Zyklus: positiv auf, negativ durch, Rueckkehr auf 0. */
    private fun fullCycle(amplitude: Double): List<Double> =
        (1..12).map { amplitude * it / 12.0 } +
            (1..12).map { amplitude - 2.0 * amplitude * it / 12.0 } +
            (1..8).map { -amplitude + amplitude * it / 8.0 }

    /** Rep i: Amplitude 60 -> 42 (-30 %), Sample-Intervall 20 -> 25 ms (+25 %). */
    private fun driftStream(): List<Pair<Double, Long>> =
        (0 until reps).map { i ->
            val amplitude = 60.0 - 18.0 * i / (reps - 1)
            val intervalMs = 20L + 5L * i / (reps - 1)
            amplitude to intervalMs
        }

    private fun counter(
        fatigueTolerance: Double,
        suspiciousTolerance: Double,
    ): RepCounter =
        RepCounter(
            peakDetector = PeakDetector(threshold = 32.5, expectedDurationMs = 600.0),
            templateMatcher = TemplateMatcher(),
            phaseValidator = PhaseValidator(),
            qualityScorer =
                QualityScorer(
                    expectedProminence = 60.0,
                    expectedDurationMs = 600.0,
                    fatigueTolerance = fatigueTolerance,
                    suspiciousTolerance = suspiciousTolerance,
                ),
        )

    /** Spielt den Driftsatz mit fortlaufender Zeitbasis; liefert die Rep-Ergebnisse. */
    private fun feed(
        counter: RepCounter,
        stream: List<Pair<Double, Long>> = driftStream(),
    ): List<RepResult> {
        val results = mutableListOf<RepResult>()
        var clockMs = 0L
        stream.forEach { (amplitude, intervalMs) ->
            val values = fullCycle(amplitude) + List(40) { 0.0 }
            values.forEachIndexed { i, v ->
                val frame =
                    ProcessedFrame(
                        timestampMs = clockMs + i * intervalMs,
                        rawGp = v,
                        filteredGp = v,
                        smoothedGp = v,
                        envelope = abs(v),
                        isSettled = true,
                    )
                val result = counter.process(frame)
                if (result.repCounted) results += result
            }
            clockMs += values.size * intervalMs
        }
        return results
    }

    @Test
    fun `einseitige bewertung zaehlt den kompletten driftsatz`() {
        val counter = counter(fatigueTolerance = 1.45, suspiciousTolerance = 0.80)
        val counted = feed(counter)
        assertEquals("alle $reps ermuedeten Reps muessen zaehlen", reps, counter.repCount)
        assertEquals(reps, counted.size)
    }

    @Test
    fun `referenzlauf mit symmetrischer toleranz dokumentiert das delta`() {
        // Vor-B1-Verhalten: beidseitig 1.0. Beide Laeufe zaehlen denselben
        // Satz; die einseitige Bewertung darf am Satzende nicht schlechter
        // sein (das war der Defekt: die letzten Reps verloren Marge).
        val old = counter(fatigueTolerance = 1.0, suspiciousTolerance = 1.0)
        val oldCounted = feed(old)
        val new = counter(fatigueTolerance = 1.45, suspiciousTolerance = 0.80)
        val newCounted = feed(new)

        assertEquals("Referenzlauf muss den Satz ebenfalls zaehlen", reps, old.repCount)
        assertEquals(reps, new.repCount)

        val oldLast = oldCounted.last().qualityScore ?: 0.0
        val newLast = newCounted.last().qualityScore ?: 0.0
        assertTrue(
            "Satzende-Score darf unter einseitiger Bewertung nicht sinken (alt=$oldLast neu=$newLast)",
            newLast >= oldLast - 1e-9,
        )
        assertTrue("Satzende muss ueber der Akzeptanzschwelle bleiben", newLast > 0.55)
    }
}
