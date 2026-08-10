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
}
