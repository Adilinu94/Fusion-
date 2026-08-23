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

    /**
     * Flowtimer-Integration (CONTEXT E4d): Violett traegt secondary und bleibt
     * auf 756FFA gepinnt; der Grund (background/surface) ist 141414; genau ein
     * heller Tile pro Screen laeuft in E7E6FB (secondaryContainer).
     */
    @Test
    fun `Violett bleibt 756FFA und traegt die Ziel-Rollen`() {
        assertEquals(Color(0xFF756FFA), BrandViolet)
        assertEquals(BrandViolet, DarkColors.secondary)
        assertEquals(Color(0xFF141414), BrandGround)
        assertEquals(BrandGround, DarkColors.background)
        assertEquals(BrandGround, DarkColors.surface)
        assertEquals(Color(0xFFE7E6FB), BrandLilac)
        assertEquals(BrandLilac, DarkColors.secondaryContainer)
    }

    /**
     * E4d Kontrastregel 1: Auf Violett steht dunkler Text (141414, Ratio 4,75),
     * nie weisser (nur 3,73 und damit unter WCAG AA fuer Text).
     */
    @Test
    fun `onSecondary ist dunkel statt weiss`() {
        assertEquals(BrandGround, DarkColors.onSecondary)
        assertEquals(BrandGround, DarkColors.onSecondaryContainer)
        val dunklerText = contrastRatio(BrandGround, BrandViolet)
        val weisserText = contrastRatio(BrandWhite, BrandViolet)
        org.junit.Assert.assertTrue(
            "Kontrast $dunklerText unterschreitet 4.5:1 (WCAG AA)",
            dunklerText >= 4.5,
        )
        org.junit.Assert.assertTrue(
            "Weiss auf Violett ($weisserText) erfuellt AA und koennte sich " +
                "einschleichen — dunkler Text ist Pflicht",
            weisserText < 4.5,
        )
    }

    /**
     * E4d Kontrastregel 2 (der wichtigste Fall): Lime auf dem hellen Tile ist
     * mit 1,08 praktisch unsichtbar. Solange die Kombination AA klar verfehlt,
     * kann sie sich nicht still durchsetzen.
     */
    @Test
    fun `Lime wird nie mit secondaryContainer gepaart`() {
        val ratio = contrastRatio(BrandLime, DarkColors.secondaryContainer)
        org.junit.Assert.assertTrue(
            "Kontrast $ratio ueberschreitet 4.5:1 — Lime waere auf dem hellen " +
                "Tile lesbar und damit eine verbotene Paarung",
            ratio < 4.5,
        )
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
