package com.dropsync.feature.player

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
 */
data class PlayerArtworkColors(
    /** Getoenter Hintergrund-Scrim (dunkle Deckfarbe des Covers). */
    val scrim: Color,
    /** Primaere Text-/Iconfarbe (weiss auf dunklem, schwarz auf hellem Cover). */
    val content: Color,
    /** Sekundaere Textfarbe (Interpret, Zeiten). */
    val contentMuted: Color,
    /** Adaptiver Akzent (vibrierendste Deckfarbe); null = Marken-Akzent nutzen. */
    val accent: Color?,
    /** Textfarbe auf dem Akzent. */
    val onAccent: Color,
)

/** Neutraler Fallback, solange kein Cover geladen/kein Bild vorhanden. */
fun defaultArtworkColors(): PlayerArtworkColors =
    PlayerArtworkColors(
        scrim = Color.Black,
        content = Color.White,
        contentMuted = Color.White.copy(alpha = 0.72f),
        accent = null,
        onAccent = Color.Black,
    )

/**
 * Laedt das 512er-Cover (geteilt mit dem Blur-Hintergrund — gleiche
 * Cache-Klasse) und leitet die Player-Farben ab. Neutraler Fallback bis
 * die Bitmap da ist; kein Flackern, weil der Uebergang ueber das naechste
 * Composition-Ergebnis laeuft.
 */
@Composable
fun rememberArtworkColors(contentUri: String?): State<PlayerArtworkColors> {
    val context = LocalContext.current
    return produceState(initialValue = defaultArtworkColors(), key1 = contentUri) {
        value =
            contentUri?.let { uri ->
                CoverArtLoader.load(context, uri, ARTWORK_COLOR_DIM_PX)?.let(::colorsFromBitmap)
            } ?: defaultArtworkColors()
    }
}

private fun colorsFromBitmap(bitmap: ImageBitmap): PlayerArtworkColors =
    colorsFromPixels(readSampledPixels(bitmap.asAndroidBitmap()))

/** Liest maximal [MAX_SAMPLES] Pixel gleichmaessig verteilt aus der Bitmap. */
private fun readSampledPixels(bitmap: android.graphics.Bitmap): IntArray {
    val total = bitmap.width * bitmap.height
    val stride = (total / MAX_SAMPLES).coerceAtLeast(1)
    val pixels = IntArray(total / stride + 1)
    var out = 0
    var index = 0
    while (index < total) {
        pixels[out++] = bitmap.getPixel(index % bitmap.width, index / bitmap.width)
        index += stride
    }
    return pixels.copyOf(out)
}

/**
 * Reine Farb-Mathematik (JVM-testbar): 4-Bit-Quantisierung je Kanal in
 * 4096 Buckets; dominant = staerkstes Bucket (Mittel), Akzent = Bucket
 * mit dem besten Score aus Haeufigkeit x Saettigung x Mitten-Luminanz.
 */
internal fun colorsFromPixels(pixels: IntArray): PlayerArtworkColors {
    if (pixels.isEmpty()) return defaultArtworkColors()

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
        val luminance = (0.299f * r + 0.587f * g + 0.114f * b) / 255f
        val score = bucketCount * saturation * (1f - kotlin.math.abs(luminance - 0.5f))
        if (saturation >= MIN_ACCENT_SATURATION && score > bestScore) {
            bestScore = score
            accentBucket = bucket
        }
    }

    val (dr, dg, db) = averageOf(dominantBucket, sums, count)
    val dominantLuminance = (0.299f * dr + 0.587f * dg + 0.114f * db) / 255f
    val darkBackground = dominantLuminance < LIGHT_CONTENT_THRESHOLD
    val content = if (darkBackground) Color.White else Color(0xFF101010)
    val scrimBase = Color(dr, dg, db)
    val scrim =
        if (darkBackground) {
            lerp(scrimBase, Color.Black, SCRIM_DARKEN)
        } else {
            lerp(scrimBase, Color.White, SCRIM_LIGHTEN)
        }

    val accent =
        if (accentBucket >= 0 && accentBucket != dominantBucket) {
            val (ar, ag, ab) = averageOf(accentBucket, sums, count)
            val accentLuminance = (0.299f * ar + 0.587f * ag + 0.114f * ab) / 255f
            val accentColor = Color(ar, ag, ab)
            val onAccent = if (accentLuminance < LIGHT_CONTENT_THRESHOLD) Color.White else Color(0xFF101010)
            PlayerAccent(accentColor, onAccent)
        } else {
            null
        }

    return PlayerArtworkColors(
        scrim = scrim,
        content = content,
        contentMuted = content.copy(alpha = 0.72f),
        accent = accent?.color,
        onAccent = accent?.onColor ?: if (darkBackground) Color.Black else Color.White,
    )
}

private data class PlayerAccent(
    val color: Color,
    val onColor: Color,
)

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
private const val MIN_ACCENT_SATURATION = 0.25f
private const val LIGHT_CONTENT_THRESHOLD = 0.55f
private const val SCRIM_DARKEN = 0.6f
private const val SCRIM_LIGHTEN = 0.7f
