package com.dropsync.domain.sensor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RepPipelineTest {
    @Test
    fun `quality scorer rewards ideal rep`() {
        val scorer = QualityScorer(expectedProminence = 100.0, expectedDurationMs = 1_000.0)
        val result =
            scorer.score(
                correlation = 1.0,
                prominence = 100.0,
                durationMs = 1_000,
                durationRatio = 0.5,
            )
        assertTrue(result.accepted)
        assertEquals(1.0, result.score, 1e-6)
    }

    @Test
    fun `quality scorer rejects far off rep`() {
        val scorer = QualityScorer(expectedProminence = 100.0, expectedDurationMs = 1_000.0)
        val result =
            scorer.score(
                correlation = -1.0,
                prominence = 300.0,
                durationMs = 4_000,
                durationRatio = 0.99,
            )
        assertTrue(!result.accepted)
    }

    @Test
    fun `befund c fix - template matcher receives original peak window`() {
        // The matcher must see peak.window (NOT the extended window): a
        // template equal to the original peak shape yields NCC ~1.
        val peakShape = (0..10).map { 200.0 * it / 10.0 } + (1..14).map { 200.0 - 300.0 * it / 14.0 }
        val matcher = TemplateMatcher()
        matcher.setTemplate(peakShape)
        val original = matcher.match(peakShape)
        assertTrue(original.accepted)
        assertTrue(original.correlation > 0.95)
    }

    /** Vollstaendiger Zyklus: positiv auf, negativ durch, zurueck auf 0. */
    private fun fullCycle(): List<Double> =
        (1..12).map { 200.0 * it / 12.0 } + // concentrich auf
            (1..12).map { 200.0 - 400.0 * it / 12.0 } + // Richtungswechsel + exzentrisch
            (1..8).map { -200.0 + 200.0 * it / 8.0 } // Rueckkehr Richtung Baseline

    private fun newCounter(
        expectedProminence: Double = 1.0,
        expectedDurationMs: Double = 1_000.0,
        templateMatcher: TemplateMatcher = TemplateMatcher(),
        peakDetector: PeakDetector = PeakDetector(threshold = 32.5),
    ) = RepCounter(
        peakDetector = peakDetector,
        templateMatcher = templateMatcher, // no template -> accept all
        phaseValidator = PhaseValidator(),
        qualityScorer = QualityScorer(expectedProminence = expectedProminence, expectedDurationMs = expectedDurationMs),
    )

    private fun feed(
        counter: RepCounter,
        samples: List<Double>,
        startMs: Long = 0L,
    ): Int {
        var counted = 0
        samples.forEachIndexed { i, v ->
            val frame =
                ProcessedFrame(
                    timestampMs = startMs + i * 20L,
                    rawGp = v,
                    filteredGp = v,
                    smoothedGp = v,
                    envelope = kotlin.math.abs(v),
                    isSettled = true,
                )
            if (counter.process(frame).repCounted) counted++
        }
        return counted
    }

    @Test
    fun `rep counter counts exactly two clean two-phase reps`() {
        // P0-Fix: zwei vollstaendige Reps muessen EXAKT zwei ergeben.
        val counter = newCounter(expectedDurationMs = 1_400.0)
        val samples = fullCycle() + List(40) { 0.0 } + fullCycle() + List(20) { 0.0 }
        val counted = feed(counter, samples)
        assertEquals("zwei vollstaendige Reps muessen exakt 2 zaehlen", 2, counted)
        assertEquals(counted, counter.repCount)
    }

    @Test
    fun `half rep is rejected`() {
        // P0-Fix: reines Anheben ohne Rueckbewegung darf nicht zaehlen.
        val counter = newCounter()
        val halfRep = (1..15).map { 200.0 * it / 15.0 } + List(30) { 200.0 } + List(20) { 0.0 }
        val counted = feed(counter, halfRep)
        assertEquals("halbe Rep darf nicht zaehlen", 0, counted)
    }

    @Test
    fun `confirmed rep updates template pool`() {
        // Punkt 5: Nach einer bestaetigten Rep landet das Peak-Window im
        // Pool des Matchers (poolCount steigt), nach poolSize Reps ist er
        // gefuellt und haelt FIFO-Groesse.
        val matcher = TemplateMatcher(poolSize = 3)
        val counter =
            newCounter(
                expectedDurationMs = 1_400.0,
                templateMatcher = matcher,
                peakDetector = PeakDetector(threshold = 32.5, expectedDurationMs = 1_400.0),
            )
        assertEquals(0, matcher.poolCount)

        // Fortlaufende Zeitbasis ueber alle Reps (Refraktaerzeit ist
        // timestampbasiert; ein Neustart bei 0 wuerde Reps unterdruecken).
        var clockMs = 0L

        fun feedReps(count: Int) {
            repeat(count) {
                val samples = fullCycle() + List(30) { 0.0 } + List(10) { 0.0 }
                feed(counter, samples, clockMs)
                clockMs += samples.size * 20L
            }
        }

        feedReps(1)
        assertEquals("erste Rep muss den Pool fuellen", 1, matcher.poolCount)
        feedReps(3)
        // Insgesamt 4 bestaetigte Reps, Pool groesse 3: FIFO-Eviction.
        assertEquals(3, matcher.poolCount)
        assertEquals(4, counter.repCount)
    }

    @Test
    fun `eine schwache Rep fuellt den Pool nicht`() {
        // B6 (RC-21): Der Score der Standard-Rep liegt knapp ueber der
        // Akzeptanzschwelle (0.55), aber unter einer hohen Admission-Schwelle
        // -> die Rep zaehlt, der Pool bleibt aber leer. Mit niedriger
        // Admission waechst der Pool wie bisher.
        val strict = TemplateMatcher(poolSize = 3, admissionMinScore = 0.995)
        val strictCounter =
            newCounter(
                expectedDurationMs = 1_400.0,
                templateMatcher = strict,
                peakDetector = PeakDetector(threshold = 32.5, expectedDurationMs = 1_400.0),
            )
        val strictSamples = fullCycle() + List(40) { 0.0 } + List(10) { 0.0 }
        feed(strictCounter, strictSamples)
        assertEquals("die Rep selbst zaehlt weiter", 1, strictCounter.repCount)
        assertEquals("unter der Admission-Schwelle darf der Pool nicht wachsen", 0, strict.poolCount)

        val open = TemplateMatcher(poolSize = 3, admissionMinScore = 0.5)
        val openCounter =
            newCounter(
                expectedDurationMs = 1_400.0,
                templateMatcher = open,
                peakDetector = PeakDetector(threshold = 32.5, expectedDurationMs = 1_400.0),
            )
        feed(openCounter, strictSamples)
        assertEquals(1, openCounter.repCount)
        assertEquals("ueber der Admission-Schwelle waechst der Pool", 1, open.poolCount)
    }

    @Test
    fun `trackForAdaptation updates refractory`() {
        // Punkt 6: Nach 3 bestaetigten Reps mit kurzer Dauer muss die
        // Refraktaerzeit des PeakDetectors der echten Dauer folgen.
        val peakDetector = PeakDetector(threshold = 32.5, expectedDurationMs = 1_400.0)
        val counter =
            newCounter(
                expectedDurationMs = 1_400.0,
                peakDetector = peakDetector,
            )

        var clockMs = 0L

        fun feedOneRep() {
            val samples = fullCycle() + List(40) { 0.0 } + List(10) { 0.0 }
            feed(counter, samples, clockMs)
            clockMs += samples.size * 20L
        }

        feedOneRep()
        assertEquals("Refraktaerzeit vor 3 Reps bleibt Default", 1_400.0, peakDetector.expectedDurationMs, 1e-6)

        feedOneRep()
        feedOneRep()
        assertTrue(
            "nach 3 Reps muss die erwartete Dauer adaptiert sein (war: ${peakDetector.expectedDurationMs})",
            peakDetector.expectedDurationMs != 1_400.0,
        )
    }
}
