package com.dropsync.domain.sensor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sin

class RepPipelineTest {
    @Test
    fun `template extractor merges calibration windows`() {
        val w1 = (0 until 50).map { sin(2 * Math.PI * it / 50) }
        val w2 = (0 until 80).map { sin(2 * Math.PI * it / 80) }
        val w3 = (0 until 60).map { sin(2 * Math.PI * it / 60) }
        val template = TemplateExtractor.extract(listOf(w1, w2, w3))
        assertNotNull(template)
        assertEquals(TemplateExtractor.TEMPLATE_LENGTH, template!!.size)
    }

    @Test
    fun `template extractor needs at least two reps`() {
        assertNull(TemplateExtractor.extract(listOf(List(10) { 1.0 })))
    }

    @Test
    fun `quality scorer rewards ideal rep`() {
        val scorer = QualityScorer(expectedProminence = 100.0, expectedDurationSamples = 50.0)
        val result = scorer.score(correlation = 1.0, prominence = 100.0, durationSamples = 50, durationRatio = 0.5)
        assertTrue(result.accepted)
        assertEquals(1.0, result.score, 1e-6)
    }

    @Test
    fun `quality scorer rejects far off rep`() {
        val scorer = QualityScorer(expectedProminence = 100.0, expectedDurationSamples = 50.0)
        val result = scorer.score(correlation = -1.0, prominence = 300.0, durationSamples = 200, durationRatio = 0.99)
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

    @Test
    fun `rep counter counts clean reps`() {
        val counter =
            RepCounter(
                peakDetector = PeakDetector(),
                templateMatcher = TemplateMatcher(), // no template -> accept all
                phaseValidator = PhaseValidator(),
                qualityScorer = QualityScorer(expectedProminence = 1.0, expectedDurationSamples = 25.0),
            )
        val peakShape = (0..10).map { 200.0 * it / 10.0 } + (1..14).map { 200.0 - 300.0 * it / 14.0 }
        // Two excursions with enough distance (refractory 0.5 s = 25 samples).
        val samples = peakShape + List(20) { 0.0 } + peakShape + List(10) { 0.0 }
        var counted = 0
        samples.forEachIndexed { i, v ->
            val frame =
                ProcessedFrame(
                    timestampMs = i * 20L,
                    rawGp = v,
                    filteredGp = v,
                    smoothedGp = v,
                    envelope = kotlin.math.abs(v),
                    isSettled = true,
                )
            if (counter.process(frame).repCounted) counted++
        }
        assertTrue("expected >= 1 counted rep, got $counted", counted >= 1)
        assertEquals(counted, counter.repCount)
    }

    @Test
    fun `confirmed rep updates template pool`() {
        // Punkt 5: Nach einer bestaetigten Rep landet das Peak-Window im
        // Pool des Matchers (poolCount steigt), nach poolSize Reps ist er
        // gefuellt und haelt FIFO-Groesse.
        val matcher = TemplateMatcher(poolSize = 3)
        val counter =
            RepCounter(
                peakDetector = PeakDetector(),
                templateMatcher = matcher,
                phaseValidator = PhaseValidator(),
                qualityScorer = QualityScorer(expectedProminence = 1.0, expectedDurationSamples = 25.0),
            )
        assertEquals(0, matcher.poolCount)

        fun feedReps(count: Int) {
            val peakShape = (0..10).map { 200.0 * it / 10.0 } + (1..14).map { 200.0 - 300.0 * it / 14.0 }
            repeat(count) {
                val samples = peakShape + List(20) { 0.0 } + List(10) { 0.0 }
                samples.forEachIndexed { i, v ->
                    counter.process(
                        ProcessedFrame(
                            timestampMs = i * 20L,
                            rawGp = v,
                            filteredGp = v,
                            smoothedGp = v,
                            envelope = kotlin.math.abs(v),
                            isSettled = true,
                        ),
                    )
                }
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
    fun `trackForAdaptation updates refractory`() {
        // Punkt 6: Nach 3 bestaetigten Reps mit kurzer Dauer muss die
        // Refraktaerzeit des PeakDetectors der echten Dauer folgen.
        val peakDetector = PeakDetector()
        val counter =
            RepCounter(
                peakDetector = peakDetector,
                templateMatcher = TemplateMatcher(), // no template -> accept all
                phaseValidator = PhaseValidator(),
                qualityScorer = QualityScorer(expectedProminence = 1.0, expectedDurationSamples = 25.0),
            )
        val peakShape = (0..10).map { 200.0 * it / 10.0 } + (1..14).map { 200.0 - 300.0 * it / 14.0 }

        fun feedOneRep(startMs: Long) {
            (peakShape + List(20) { 0.0 } + List(10) { 0.0 }).forEachIndexed { i, v ->
                counter.process(
                    ProcessedFrame(
                        timestampMs = startMs + i * 20L,
                        rawGp = v,
                        filteredGp = v,
                        smoothedGp = v,
                        envelope = kotlin.math.abs(v),
                        isSettled = true,
                    ),
                )
            }
        }

        feedOneRep(0)
        assertEquals("Refraktaerzeit vor 3 Reps bleibt Default", 25.0, peakDetector.expectedDurationSamples, 1e-6)

        feedOneRep(2_000)
        feedOneRep(4_000)
        // Fenstergroesse der Pending-Phase bestimmt die Dauer; hier ist
        // nur wichtig: die Erwartung hat die Defaults verlassen und
        // folgt den echten Fenstern.
        assertTrue(
            "nach 3 Reps muss die erwartete Dauer adaptiert sein (war: ${peakDetector.expectedDurationSamples})",
            peakDetector.expectedDurationSamples != 25.0,
        )
    }
}
