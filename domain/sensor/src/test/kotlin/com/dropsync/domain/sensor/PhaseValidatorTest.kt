package com.dropsync.domain.sensor

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sin

class PhaseValidatorTest {
    private val validator = PhaseValidator()

    @Test
    fun `balanced two-phase window is valid`() {
        // Full sine: 32 positive + 32 negative samples -> ratio 0.5.
        val window = (0 until 64).map { sin(2 * Math.PI * it / 64) }
        val result = validator.validate(window)
        assertTrue(result.valid)
        assertTrue(result.durationRatio in 0.4..0.6)
    }

    @Test
    fun `too short window is rejected`() {
        assertFalse(validator.validate(listOf(1.0, -1.0, 1.0)).valid)
    }

    @Test
    fun `pure positive window is rejected as half rep`() {
        // Umbauplan Phase 4: ein reines Anheben (nur positiv) darf NICHT
        // mehr als vollstaendige Rep durchgehen.
        val result = validator.validate(List(10) { it.toDouble() })
        assertFalse("reine positive Phase muss abgelehnt werden", result.valid)
    }

    @Test
    fun `pure negative window is rejected as half rep`() {
        val result = validator.validate(List(10) { -it.toDouble() })
        assertFalse("reine negative Phase muss abgelehnt werden", result.valid)
    }

    @Test
    fun `extremely asymmetric window is rejected`() {
        // 100 positive, 2 negative -> ratio ~0.98 > 0.85.
        val window = List(100) { 1.0 } + List(2) { -1.0 }
        assertFalse(validator.validate(window).valid)
    }

    @Test
    fun `tiny negative phase is rejected`() {
        // 50 positive, 1 negative -> negative < minPhaseSamples.
        val window = List(50) { 1.0 } + listOf(-1.0)
        assertFalse(validator.validate(window).valid)
    }
}
