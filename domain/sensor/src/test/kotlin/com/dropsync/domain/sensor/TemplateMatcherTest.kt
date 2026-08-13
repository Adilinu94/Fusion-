package com.dropsync.domain.sensor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sin

class TemplateMatcherTest {
    private fun sine(
        length: Int,
        periods: Double = 1.0,
    ): List<Double> = (0 until length).map { sin(2 * Math.PI * periods * it / length) }

    @Test
    fun `no template accepts everything`() {
        val matcher = TemplateMatcher()
        val result = matcher.match(sine(30))
        assertTrue(result.accepted)
        assertTrue(result.noTemplate)
    }

    @Test
    fun `identical signal matches with ncc 1`() {
        val matcher = TemplateMatcher()
        matcher.setTemplate(sine(64))
        val result = matcher.match(sine(64))
        assertTrue(result.accepted)
        assertEquals(1.0, result.correlation, 1e-6)
    }

    @Test
    fun `same frequency at different length still matches after resampling`() {
        val matcher = TemplateMatcher()
        matcher.setTemplate(sine(64))
        // Same frequency, more samples: linear resampling keeps the shape.
        val result = matcher.match(sine(128))
        assertTrue(result.accepted)
        assertTrue(result.correlation > 0.95)
    }

    @Test
    fun `inverted signal is rejected`() {
        val matcher = TemplateMatcher()
        matcher.setTemplate(sine(64))
        val result = matcher.match(sine(64).map { -it })
        assertFalse(result.accepted)
        assertTrue(result.correlation < 0)
    }

    @Test
    fun `constant window is rejected`() {
        val matcher = TemplateMatcher()
        matcher.setTemplate(sine(64))
        assertFalse(matcher.match(List(64) { 1.0 }).accepted)
    }

    @Test
    fun `too short template is ignored`() {
        val matcher = TemplateMatcher()
        matcher.setTemplate(listOf(1.0, 2.0, 3.0))
        assertFalse(matcher.hasTemplate)
    }

    @Test
    fun `clear template resets to noTemplate mode`() {
        val matcher = TemplateMatcher()
        matcher.setTemplate(sine(64))
        matcher.clearTemplate()
        assertFalse(matcher.hasTemplate)
        assertTrue(matcher.match(sine(20)).noTemplate)
    }

    // --- Punkt 5: Multi-Template-Pool -------------------------------------

    @Test
    fun `multi template best match wins`() {
        val matcher = TemplateMatcher(poolSize = 5)
        // Basis-Template: Sinus mit 1 Periode. Ein Fenster mit 2 Perioden
        // ist dazu (nahezu) orthogonal (NCC ~ 0 < 0.7) -> abgelehnt.
        matcher.setTemplate(sine(64, periods = 1.0))
        assertFalse(
            "2-Perioden-Fenster darf gegen das 1-Perioden-Template nicht matchen",
            matcher.match(sine(64, periods = 2.0)).accepted,
        )

        // Sobald die 2-Perioden-Form im Pool liegt, gewinnt der Best-Match.
        matcher.addToPool(sine(64, periods = 2.0))
        val result = matcher.match(sine(64, periods = 2.0))
        assertTrue("Best-Match gegen das 2-Perioden-Template im Pool muss gewinnen", result.accepted)
        assertTrue(result.correlation > 0.95)
    }

    @Test
    fun `addToPool adds and evicts FIFO`() {
        val matcher = TemplateMatcher(poolSize = 5)
        matcher.setTemplate(sine(64, periods = 1.0))
        assertEquals(1, matcher.poolCount)
        // 7 weitere Windows -> Pool waechst nur bis 5.
        repeat(7) { i ->
            matcher.addToPool(sine(64 + i, periods = 2.0))
        }
        assertEquals(5, matcher.poolCount)
        // Der Pool behaelt das juengste 2-Perioden-Template -> matcht weiter.
        assertTrue(matcher.match(sine(64, periods = 2.0)).accepted)
    }

    @Test
    fun `addToPool ignores too short windows`() {
        val matcher = TemplateMatcher(poolSize = 5)
        matcher.setTemplate(sine(64))
        matcher.addToPool(listOf(1.0, 2.0))
        assertEquals("zu kurzes Window darf den Pool nicht erweitern", 1, matcher.poolCount)
    }

    @Test
    fun `constant window is not added to pool`() {
        val matcher = TemplateMatcher(poolSize = 5)
        matcher.setTemplate(sine(64))
        matcher.addToPool(List(64) { 3.0 })
        assertEquals("konstantes Window normalisiert zu null und wird verworfen", 1, matcher.poolCount)
    }
}
