package com.dropsync.core.designsystem.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Phase 1 (A): Snapshot-Test der FlowRep-Markenfarben.
 * Stellt sicher, dass die feste Palette (#0D0D0D #FFFFFF #DFFF2F)
 * nicht unbeabsichtigt veraendert wird.
 */
class ThemeColorSnapshotTest {
    @Test
    fun `Lime bleibt DFFF2F`() {
        assertEquals(Color(0xFFDFFF2F), BrandLime)
    }

    @Test
    fun `Schwarz bleibt 101010`() {
        assertEquals(Color(0xFF101010), BrandBlack)
    }

    @Test
    fun `Weiss bleibt F7FBFF`() {
        assertEquals(Color(0xFFF7FBFF), BrandWhite)
    }

    @Test
    fun `Lime ist primary in beiden Modi`() {
        // Light
        assertEquals(BrandLime, LightColors.primary)
        assertEquals(BrandBlack, LightColors.onPrimary)
        // Dark
        assertEquals(BrandLime, DarkColors.primary)
        assertEquals(BrandBlack, DarkColors.onPrimary)
    }

    /**
     * Plan 6.2: WCAG-Kontrast des Lime-Akzents gegen die Brandflaechen.
     * Lime auf Schwarz ist der Hauptfall (Buttons, Badge); Weiss auf Lime
     * waere 1,1:1 und darf nie vorkommen — onPrimary bleibt deshalb Schwarz.
     */
    @Test
    fun `Kontrast Lime auf Schwarz erfüllt WCAG AA`() {
        val ratio = contrastRatio(BrandLime, BrandBlack)
        org.junit.Assert.assertTrue(
            "Kontrast $ratio unterschreitet 4.5:1 (WCAG AA)",
            ratio >= 4.5,
        )
    }

    @Test
    fun `onPrimary bleibt Schwarz gegen Lime`() {
        assertEquals(BrandBlack, LightColors.onPrimary)
    }

    @Test
    fun `Spacing-Skala ist 4er-Raster`() {
        assertEquals(4, Spacing.space4.value.toInt())
        assertEquals(8, Spacing.space8.value.toInt())
        assertEquals(16, Spacing.space16.value.toInt())
        assertEquals(24, Spacing.space24.value.toInt())
        assertEquals(32, Spacing.space32.value.toInt())
    }
}

/** Relative Luminanz nach WCAG 2.x (sRGB, gamma-entzerrt). */
private fun relativeLuminance(color: Color): Double {
    fun channel(v: Float): Double {
        val c = v.toDouble()
        return if (c <= 0.03928) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)
    }
    val r = channel(color.red)
    val g = channel(color.green)
    val b = channel(color.blue)
    return 0.2126 * r + 0.7152 * g + 0.0722 * b
}

/** Kontrastverhaeltnis (1..21) nach WCAG 2.x. */
private fun contrastRatio(
    a: Color,
    b: Color,
): Double {
    val la = relativeLuminance(a)
    val lb = relativeLuminance(b)
    val lighter = maxOf(la, lb)
    val darker = minOf(la, lb)
    return (lighter + 0.05) / (darker + 0.05)
}
