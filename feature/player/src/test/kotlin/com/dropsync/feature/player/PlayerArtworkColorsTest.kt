package com.dropsync.feature.player

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P3-Fix #25: die Now-Playing-Farben muessen dem THEME folgen (Helligkeit)
 * und dem COVER (Farbton) — nicht umgekehrt. Zusaetzlich muss der Akzent
 * gegen den Hintergrund lesbar bleiben.
 */
class PlayerArtworkColorsTest {
    private fun pixels(
        vararg colors: Int,
        repeat: Int = 1,
    ): IntArray {
        val out = ArrayList<Int>(colors.size * repeat)
        repeat(repeat) { colors.forEach(out::add) }
        return out.toIntArray()
    }

    private fun argb(
        r: Int,
        g: Int,
        b: Int,
    ): Int = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    @Test
    fun `leere pixel liefern den theme-fallback`() {
        val dark = colorsFromPixels(IntArray(0), darkTheme = true)
        val light = colorsFromPixels(IntArray(0), darkTheme = false)

        assertTrue(
            "Dark-Fallback muss dunkel sein",
            luminance(dark.scrim) < luminance(light.scrim),
        )
        assertTrue("Dark-Mode: helle Schrift", luminance(dark.content) > 0.5f)
        assertTrue("Light-Mode: dunkle Schrift", luminance(light.content) < 0.5f)
    }

    @Test
    fun `dunkles cover ergibt im light-mode keinen dunklen player`() {
        // Der eigentliche Bug: vorher entschied allein die Cover-Luminanz.
        // Ein fast schwarzes Cover machte den Screen im Light-Mode dunkel.
        val darkCover = pixels(argb(12, 14, 20), repeat = 400)

        val light = colorsFromPixels(darkCover, darkTheme = false)

        assertTrue(
            "Light-Mode muss auch bei dunklem Cover hell bleiben (war ${luminance(light.scrim)})",
            luminance(light.scrim) > 0.5f,
        )
        assertTrue("und dunkle Schrift tragen", luminance(light.content) < 0.5f)
    }

    @Test
    fun `helles cover ergibt im dark-mode keinen hellen player`() {
        val lightCover = pixels(argb(246, 244, 240), repeat = 400)

        val dark = colorsFromPixels(lightCover, darkTheme = true)

        assertTrue(
            "Dark-Mode muss auch bei hellem Cover dunkel bleiben (war ${luminance(dark.scrim)})",
            luminance(dark.scrim) < 0.5f,
        )
        assertTrue("und helle Schrift tragen", luminance(dark.content) > 0.5f)
    }

    @Test
    fun `akzent erreicht den mindestkontrast gegen den hintergrund`() {
        // Dunkelblauer Akzent auf dunklem Grund: ohne Anhebung unlesbar.
        val cover =
            pixels(
                argb(10, 10, 12),
                argb(10, 10, 12),
                argb(10, 10, 12),
                argb(18, 24, 96),
                repeat = 120,
            )

        val colors = colorsFromPixels(cover, darkTheme = true)
        val accent = colors.accent

        assertNotNull("saturiertes Bucket muss einen Akzent ergeben", accent)
        val ratio = contrastRatio(accent!!, colors.scrim)
        assertTrue(
            "Akzent muss WCAG AA fuer grossen Text erreichen (3:1), war $ratio",
            ratio >= 3.0f,
        )
    }

    @Test
    fun `graustufen-cover liefert keinen akzent`() {
        // Ohne Saettigung gibt es keine sinnvolle Akzentfarbe; dann muss die
        // Marken-/Einstellungsfarbe greifen (accent == null).
        val grayscale =
            pixels(
                argb(40, 40, 40),
                argb(120, 120, 120),
                argb(200, 200, 200),
                repeat = 150,
            )

        val colors = colorsFromPixels(grayscale, darkTheme = true)

        assertNull("Graustufen duerfen keinen Akzent erfinden", colors.accent)
    }

    @Test
    fun `contentMuted ist gedaempft aber gleiche basisfarbe`() {
        val colors = colorsFromPixels(pixels(argb(30, 60, 90), repeat = 200), darkTheme = true)

        assertTrue("muted muss transparenter sein", colors.contentMuted.alpha < colors.content.alpha)
        assertTrue(
            "muted behaelt den Farbkanal der Schrift",
            colors.contentMuted.red == colors.content.red &&
                colors.contentMuted.green == colors.content.green &&
                colors.contentMuted.blue == colors.content.blue,
        )
    }

    @Test
    fun `onAccent kontrastiert mit dem akzent`() {
        val colors =
            colorsFromPixels(
                pixels(argb(15, 15, 15), argb(230, 40, 40), repeat = 200),
                darkTheme = true,
            )

        colors.accent?.let { accent ->
            val ratio = contrastRatio(colors.onAccent, accent)
            assertTrue("Text auf dem Akzent braucht Kontrast, war $ratio", ratio >= 3.0f)
        }
    }

    @Test
    fun `kontrastverhaeltnis rechnet die extremwerte korrekt`() {
        // Sanity-Check der eigenen WCAG-Formel: Schwarz/Weiss ist 21:1.
        val ratio = contrastRatio(Color.White, Color.Black)
        assertTrue("Schwarz zu Weiss muss ~21:1 sein, war $ratio", ratio > 20.5f && ratio < 21.5f)
        assertTrue("gleiche Farbe ergibt 1:1", contrastRatio(Color.Red, Color.Red) in 0.99f..1.01f)
    }

    /** Vereinfachte Luminanz nur fuer die Test-Assertions. */
    private fun luminance(color: Color): Float = 0.299f * color.red + 0.587f * color.green + 0.114f * color.blue
}
