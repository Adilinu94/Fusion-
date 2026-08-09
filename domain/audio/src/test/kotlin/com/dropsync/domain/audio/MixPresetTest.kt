package com.dropsync.domain.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Kurven-Invarianten der Mix-Presets (Mix-Uebergaenge-Plan Phase 2/4). */
class MixPresetTest {
    @Test
    fun `equal power invariante gilt fuer alle presets ausser slam`() {
        for (preset in MixPreset.entries.filter { it != MixPreset.SLAM }) {
            for (step in 0..GRID_STEPS) {
                val t = step.toDouble() / GRID_STEPS
                val inGain = preset.fadeInGain(t)
                val outGain = preset.fadeOutGain(t)
                assertEquals(
                    "Preset $preset verletzt Equal-Power bei t=$t",
                    1.0,
                    inGain * inGain + outGain * outGain,
                    1e-9,
                )
            }
        }
    }

    @Test
    fun `fade ist bitidentisch zum bestehenden kurvenpaar`() {
        for (step in 0..GRID_STEPS) {
            val t = step.toDouble() / GRID_STEPS
            assertEquals(CrossfadeCurves.fadeInGain(t), MixPreset.FADE.fadeInGain(t), 1e-12)
            assertEquals(CrossfadeCurves.fadeOutGain(t), MixPreset.FADE.fadeOutGain(t), 1e-9)
        }
    }

    @Test
    fun `alle presets starten bei 0 und enden bei 1`() {
        for (preset in MixPreset.entries) {
            assertEquals("$preset fadeIn(0)", 0.0, preset.fadeInGain(0.0), 1e-12)
            assertEquals("$preset fadeIn(1)", 1.0, preset.fadeInGain(1.0), 1e-12)
            assertEquals("$preset fadeOut(0)", 1.0, preset.fadeOutGain(0.0), 1e-12)
            assertEquals("$preset fadeOut(1)", 0.0, preset.fadeOutGain(1.0), 1e-9)
        }
    }

    @Test
    fun `fade in kurven sind monoton steigend`() {
        for (preset in MixPreset.entries) {
            var previous = preset.fadeInGain(0.0)
            for (step in 1..GRID_STEPS) {
                val t = step.toDouble() / GRID_STEPS
                val current = preset.fadeInGain(t)
                assertTrue(
                    "Preset $preset faellt bei t=$t ($previous -> $current)",
                    current >= previous - 1e-12,
                )
                previous = current
            }
        }
    }

    @Test
    fun `presets sind paarweise verschieden`() {
        // Bei t = 0.25 liefern alle sechs Kurven unterschiedliche Werte —
        // kein Preset ist ein verkapptes Duplikat eines anderen.
        val values = MixPreset.entries.map { it.fadeInGain(0.25) }
        assertEquals(values.size, values.distinct().size)
    }

    @Test
    fun `slam schneidet hart in der mitte`() {
        assertEquals(0.0, MixPreset.SLAM.fadeInGain(0.49), 0.0)
        assertEquals(1.0, MixPreset.SLAM.fadeInGain(0.51), 0.0)
        assertEquals(1.0, MixPreset.SLAM.fadeOutGain(0.49), 0.0)
        assertEquals(0.0, MixPreset.SLAM.fadeOutGain(0.51), 0.0)
    }

    @Test
    fun `eingaben ausserhalb 0 bis 1 werden geklemmt`() {
        for (preset in MixPreset.entries) {
            assertEquals(preset.fadeInGain(0.0), preset.fadeInGain(-0.5), 1e-12)
            assertEquals(preset.fadeInGain(1.0), preset.fadeInGain(1.5), 1e-12)
        }
    }

    companion object {
        private const val GRID_STEPS = 200
    }
}
