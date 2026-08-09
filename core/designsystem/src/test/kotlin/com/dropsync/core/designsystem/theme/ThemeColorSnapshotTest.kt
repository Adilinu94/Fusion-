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
    fun `Schwarz bleibt 0D0D0D`() {
        assertEquals(Color(0xFF0D0D0D), BrandBlack)
    }

    @Test
    fun `Weiss bleibt FFFFFF`() {
        assertEquals(Color(0xFFFFFFFF), BrandWhite)
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

    @Test
    fun `Spacing-Skala ist 4er-Raster`() {
        assertEquals(4, Spacing.space4.value.toInt())
        assertEquals(8, Spacing.space8.value.toInt())
        assertEquals(16, Spacing.space16.value.toInt())
        assertEquals(24, Spacing.space24.value.toInt())
        assertEquals(32, Spacing.space32.value.toInt())
    }
}
