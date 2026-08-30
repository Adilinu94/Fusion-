package com.dropsync.feature.player

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalContext
import com.dropsync.core.designsystem.component.CoverArtLoader

/**
 * Artwork-adaptive Farben des Now-Playing-Screens (Recherche 2026:
 * Player-Farben aus dem Cover statt fests `Color.White` — Vorbild
 * Apple Liquid Glass / Spotify). Bewusst OHNE neue Abhaengigkeit
 * (Plan-Regel "keine neue Dependency" fuer Cover-Arbeit): die dominante
 * und die vibrierendste Farbe werden per Histogramm aus dem bereits im
 * LRU-Cache liegenden 512er-Cover-Bitmap gelesen.
 *
 * P3-Fix #25: die Ableitung richtet sich jetzt nach dem AKTIVEN THEME statt
 * nach der Helligkeit des Covers. Vorher entschied allein die Cover-Luminanz,
 * ob der Screen hell oder dunkel wird — ein dunkles Cover ergab im
 * Light-Mode einen schwarzen Player, ein helles im Dark-Mode einen weissen.
 * Das Cover bestimmt nur noch den FARBTON, das Theme die Helligkeit.
 */
data class PlayerArtworkColors(
    /** Getoenter Hintergrund-Scrim (dominante Coverfarbe, Richtung Theme gezogen). */
    val scrim: Color,
    /** Primaere Text-/Iconfarbe (kontrastiert garantiert mit [scrim]). */
    val content: Color,
    /** Sekundaere Textfarbe (Interpret, Zeiten). */
    val contentMuted: Color,
    /** Adaptiver Akzent (vibrierendste Deckfarbe); null = Marken-Akzent nutzen. */
    val accent: Color?,
    /** Textfarbe auf dem Akzent. */
    val onAccent: Color,
)

/**
 * Neutraler Fallback, solange kein Cover geladen/kein Bild vorhanden.
 *
 * [darkTheme] entscheidet ueber Grund und Textfarbe — im Light-Mode ist der
 * Fallback hell, nicht mehr pauschal schwarz.
 */
fun defaultArtworkColors(darkTheme: Boolean = true): PlayerArtworkColors {
    val content = contentColorFor(darkTheme)
    return PlayerArtworkColors(
        scrim = if (darkTheme) NEUTRAL_DARK else NEUTRAL_LIGHT,
        content = content,
        contentMuted = content.copy(alpha = MUTED_ALPHA),
        accent = null,
        onAccent = if (darkTheme) NEUTRAL_DARK else NEUTRAL_LIGHT,
    )
}

/**
 * Laedt das 512er-Cover (geteilt mit dem Blur-Hintergrund — gleiche
 * Cache-Klasse) und leitet die Player-Farben ab. Neutraler Fallback bis
 * die Bitmap da ist; kein Flackern, weil der Uebergang ueber das naechste
 * Composition-Ergebnis laeuft.
 */
@Composable
fun rememberArtworkColors(contentUri: String?): State<PlayerArtworkColors> {
    val context = LocalContext.current
    val darkTheme = isSystemInDarkTheme()
    return produceState(
        initialValue = defaultArtworkColors(darkTheme),
        key1 = contentUri,
        key2 = darkTheme,
    ) {
        value =
            contentUri?.let { uri ->
                CoverArtLoader.load(context, uri, ARTWORK_COLOR_DIM_PX)?.let {
                    colorsFromBitmap(it, darkTheme)
                }
            } ?: defaultArtworkColors(darkTheme)
    }
}

private fun colorsFromBitmap(
    bitmap: ImageBitmap,
    darkTheme: Boolean,
): PlayerArtworkColors = colorsFromPixels(readSampledPixels(bitmap.asAndroidBitmap()), darkTheme)

/** Liest maximal [MAX_SAMPLES] Pixel gleichmaessig verteilt aus der Bitmap. */
private fun readSampledPixels(bitmap: android.graphics.Bitmap): IntArray {
    val width = bitmap.width
    val height = bitmap.height
    if (width <= 0 || height <= 0) return IntArray(0)
    // P1-Fix: EIN getPixels-Aufruf statt bis zu 8192 Einzelaufrufe von
    // getPixel. Jeder Einzelaufruf kostet einen JNI-Uebergang plus
    // Bounds-Check; zusaetzlich fielen pro Pixel ein Modulo und eine
    // Division fuer die Koordinatenrechnung an. Zeilenweises Sampling
    // liefert die gleiche Farbverteilung deutlich guenstiger.
    val rowStride = maxOf(1, height / MAX_SAMPLE_ROWS)
    val rows = (height + rowStride - 1) / rowStride
    val row = IntArray(width)
    val colStride = maxOf(1, width * rows / MAX_SAMPLES)
    val out = IntArray(rows * ((width + colStride - 1) / colStride))
    var index = 0
    var y = 0
    while (y < height && index < out.size) {
        bitmap.getPixels(row, 0, width, 0, y, width, 1)
        var x = 0
        while (x < width && index < out.size) {
            out[index++] = row[x]
            x += colStride
        }
        y += rowStride
    }
    return if (index == out.size) out else out.copyOf(index)
}

/**
 * Reine Farb-Mathematik (JVM-testbar): 4-Bit-Quantisierung je Kanal in
 * 4096 Buckets; dominant = staerkstes Bucket (Mittel), Akzent = Bucket
 * mit dem besten Score aus Haeufigkeit x Saettigung x Mitten-Luminanz.
 *
 * P3-Fix #25: [darkTheme] gibt die Helligkeitsrichtung vor. Der Akzent wird
 * zusaetzlich so lange Richtung Theme-Gegenfarbe verschoben, bis er den
 * Kontrast [MIN_ACCENT_CONTRAST] gegen den Scrim erreicht — sonst waere ein
 * dunkelblauer Akzent auf dunklem Grund (oder ein pastellgelber auf hellem)
 * als Titelfarbe faktisch unlesbar.
 */
internal fun colorsFromPixels(
    pixels: IntArray,
    darkTheme: Boolean = true,
): PlayerArtworkColors {
    if (pixels.isEmpty()) return defaultArtworkColors(darkTheme)

    val count = HashMap<Int, Int>(256)
    val sums = HashMap<Int, LongArray>(256)
    for (argb in pixels) {
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        val bucket = ((r shr 4) shl 8) or ((g shr 4) shl 4) or (b shr 4)
        count[bucket] = (count[bucket] ?: 0) + 1
        val sum = sums.getOrPut(bucket) { LongArray(3) }
        sum[0] += r
        sum[1] += g
        sum[2] += b
    }

    var dominantBucket = -1
    var dominantCount = -1
    var accentBucket = -1
    var bestScore = -1f
    for ((bucket, bucketCount) in count) {
        if (bucketCount > dominantCount) {
            dominantCount = bucketCount
            dominantBucket = bucket
        }
        val sum = sums.getValue(bucket)
        val r = (sum[0] / bucketCount).toInt()
        val g = (sum[1] / bucketCount).toInt()
        val b = (sum[2] / bucketCount).toInt()
        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        val saturation = if (max == 0) 0f else (max - min) / max.toFloat()
        val luminance = relativeLuminance(r, g, b)
        val score = bucketCount * saturation * (1f - kotlin.math.abs(luminance - 0.5f))
        if (saturation >= MIN_ACCENT_SATURATION && score > bestScore) {
            bestScore = score
            accentBucket = bucket
        }
    }

    // Der Scrim traegt den Cover-Farbton, seine Helligkeit kommt aber vom
    // Theme: im Dark-Mode Richtung Schwarz, im Light-Mode Richtung Weiss.
    val (dr, dg, db) = averageOf(dominantBucket, sums, count)
    val scrimBase = Color(dr, dg, db)
    val scrim =
        if (darkTheme) {
            lerp(scrimBase, Color.Black, SCRIM_DARKEN)
        } else {
            lerp(scrimBase, Color.White, SCRIM_LIGHTEN)
        }
    val content = contentColorFor(darkTheme)

    val accent =
        if (accentBucket >= 0 && accentBucket != dominantBucket) {
            val (ar, ag, ab) = averageOf(accentBucket, sums, count)
            val readable = ensureContrast(Color(ar, ag, ab), scrim, darkTheme)
            PlayerAccent(readable, onColorFor(readable))
        } else {
            null
        }

    return PlayerArtworkColors(
        scrim = scrim,
        content = content,
        contentMuted = content.copy(alpha = MUTED_ALPHA),
        accent = accent?.color,
        onAccent = accent?.onColor ?: if (darkTheme) NEUTRAL_DARK else NEUTRAL_LIGHT,
    )
}

private data class PlayerAccent(
    val color: Color,
    val onColor: Color,
)

/** Textfarbe des Themes: hell auf dunklem Grund, dunkel auf hellem. */
private fun contentColorFor(darkTheme: Boolean): Color = if (darkTheme) NEUTRAL_LIGHT else NEUTRAL_DARK

/** Lesbare Farbe AUF der uebergebenen Flaeche. */
private fun onColorFor(color: Color): Color =
    if (luminanceOf(color) < ON_COLOR_THRESHOLD) NEUTRAL_LIGHT else NEUTRAL_DARK

/**
 * Hebt (Dark-Mode) bzw. senkt (Light-Mode) die Akzent-Helligkeit, bis der
 * WCAG-Kontrast gegen [against] erreicht ist. Bricht nach
 * [CONTRAST_STEPS] Schritten ab und liefert den besten erreichten Wert —
 * ein garantiert unerreichbares Ziel (Cover in genau der Scrim-Farbe) darf
 * die Funktion nicht endlos drehen lassen.
 */
private fun ensureContrast(
    accent: Color,
    against: Color,
    darkTheme: Boolean,
): Color {
    val target = if (darkTheme) Color.White else Color.Black
    var current = accent
    repeat(CONTRAST_STEPS) {
        if (contrastRatio(current, against) >= MIN_ACCENT_CONTRAST) return current
        current = lerp(current, target, CONTRAST_STEP_FRACTION)
    }
    return current
}

/** WCAG-2.1-Kontrastverhaeltnis (1:1 bis 21:1). */
internal fun contrastRatio(
    a: Color,
    b: Color,
): Float {
    val la = luminanceOf(a)
    val lb = luminanceOf(b)
    val lighter = maxOf(la, lb)
    val darker = minOf(la, lb)
    return (lighter + 0.05f) / (darker + 0.05f)
}

/** Relative Luminanz nach WCAG (mit sRGB-Linearisierung). */
private fun luminanceOf(color: Color): Float =
    relativeLuminance(
        (color.red * 255f).toInt(),
        (color.green * 255f).toInt(),
        (color.blue * 255f).toInt(),
    )

private fun relativeLuminance(
    r: Int,
    g: Int,
    b: Int,
): Float = 0.2126f * linearize(r) + 0.7152f * linearize(g) + 0.0722f * linearize(b)

private fun linearize(channel: Int): Float {
    val c = channel / 255f
    return if (c <= 0.03928f) c / 12.92f else Math.pow(((c + 0.055f) / 1.055f).toDouble(), 2.4).toFloat()
}

private fun averageOf(
    bucket: Int,
    sums: Map<Int, LongArray>,
    count: Map<Int, Int>,
): Triple<Int, Int, Int> {
    if (bucket < 0) return Triple(16, 16, 16)
    val sum = sums.getValue(bucket)
    val n = count.getValue(bucket)
    return Triple((sum[0] / n).toInt(), (sum[1] / n).toInt(), (sum[2] / n).toInt())
}

private fun lerp(
    a: Color,
    b: Color,
    fraction: Float,
): Color =
    Color(
        red = a.red + (b.red - a.red) * fraction,
        green = a.green + (b.green - a.green) * fraction,
        blue = a.blue + (b.blue - a.blue) * fraction,
        alpha = 1f,
    )

private const val ARTWORK_COLOR_DIM_PX = 512
private const val MAX_SAMPLES = 8_192

/** Zeilen, die maximal abgetastet werden (Farbverteilung braucht nicht mehr). */
private const val MAX_SAMPLE_ROWS = 96
private const val MIN_ACCENT_SATURATION = 0.25f
private const val SCRIM_DARKEN = 0.72f
private const val SCRIM_LIGHTEN = 0.78f
private const val MUTED_ALPHA = 0.72f

/** Neutrale Textfarben (identisch zu BrandWhite/BrandBlack im Designsystem). */
private val NEUTRAL_LIGHT = Color(0xFFF7FBFF)
private val NEUTRAL_DARK = Color(0xFF101010)

/** Ab dieser Luminanz steht dunkle statt heller Schrift auf einer Flaeche. */
private const val ON_COLOR_THRESHOLD = 0.35f

/**
 * WCAG AA fuer grossen Text (>= 18.66 sp bold / 24 sp regular) verlangt 3:1.
 * Der Now-Playing-Titel ist 30 sp, faellt also in diese Klasse.
 */
private const val MIN_ACCENT_CONTRAST = 3.0f

private const val CONTRAST_STEP_FRACTION = 0.12f
private const val CONTRAST_STEPS = 12
