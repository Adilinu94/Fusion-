package com.dropsync.core.designsystem.component

import android.content.Context
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.util.LruCache
import android.util.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * Laedt Cover-Bilder fuer Content-URIs (Songs). Ergebnis-Cache und Begrenzung der parallelen
 * Dekodierungen, damit schnelles Scrollen in Listen die Datei-/Decoder-Zugriffe nicht flutet.
 *
 * Quellen, je nach gewuenschter Groesse ([THUMBNAIL_FIRST_MAX_DIM_PX]):
 * - kleine Bilder (Listen): zuerst `ContentResolver.loadThumbnail` (MediaProvider cached die
 *   Thumbnails und findet auch Ordner-Cover wie `cover.jpg`), dann das eingebettete Bild;
 * - grosse Bilder (Now Playing): zuerst das eingebettete Bild (volle Qualitaet), dann der
 *   Thumbnail-Fallback fuer Dateien ohne eingebettetes Cover.
 *
 * Ergebnis-Semantik ([CoverResult]): "kein Cover" ist endgueltig und wird gemerkt, ein Fehler
 * (Decoder ueberlastet, I/O-Hickser) nur kurz ([FAILURE_RETRY_MS]) - frueher wurde jeder Fehlschlag
 * bis zum Prozessende als "kein Cover" gemerkt.
 */
object CoverArtLoader {
    /** Anzahl der Eintraege; Bitmaps sind klein (<= [DEFAULT_COVER_DIM_PX]^2). */
    private const val CACHE_ENTRIES = 256

    /** Hoechstens so viele Dekodierungen gleichzeitig (MediaMetadataRetriever ist schwer und scheitert unter Last). */
    internal const val MAX_PARALLEL_DECODES = 3

    /** Bis zu dieser Zielgroesse ist der MediaProvider-Thumbnail die schnellere Quelle. */
    internal const val THUMBNAIL_FIRST_MAX_DIM_PX = 320

    /** So lange wird ein Fehlschlag nicht erneut versucht. */
    internal const val FAILURE_RETRY_MS = 15_000L

    private val noCover = Any()
    private val cache = LruCache<String, Any>(CACHE_ENTRIES)
    private val cachedDims = LruCache<String, Int>(CACHE_ENTRIES)
    private val failedAtMs = LruCache<String, Long>(CACHE_ENTRIES)
    private val decodeGate = Semaphore(MAX_PARALLEL_DECODES)

    /** Austauschbar fuer Tests. */
    internal var source: CoverSource = AndroidCoverSource

    /** Austauschbar fuer Tests. */
    internal var clockMs: () -> Long = { SystemClock.elapsedRealtime() }

    /**
     * @return das Cover oder `null` (kein Cover vorhanden ODER Laden gescheitert - die Anzeige zeigt
     *   dann den Platzhalter; ein Fehlschlag wird nach [FAILURE_RETRY_MS] erneut versucht).
     */
    suspend fun load(
        context: Context,
        contentUri: String,
        maxDimPx: Int = DEFAULT_COVER_DIM_PX,
    ): ImageBitmap? {
        val cached = cache.get(contentUri)
        val cachedDim = cachedDims.get(contentUri)
        val servesRequest = cached != null && cachedDim != null && cachedDim >= maxDimPx
        val recentlyFailed = failedAtMs.get(contentUri)?.let { clockMs() - it < FAILURE_RETRY_MS } == true
        return when {
            servesRequest && cached is ImageBitmap -> cached
            servesRequest && cached === noCover -> null
            recentlyFailed -> null
            else -> loadUncached(context, contentUri, maxDimPx)
        }
    }

    private suspend fun loadUncached(
        context: Context,
        contentUri: String,
        maxDimPx: Int,
    ): ImageBitmap? {
        val result =
            withContext(Dispatchers.IO) { decodeGate.withPermit { source.read(context, contentUri, maxDimPx) } }
        return when (result) {
            is CoverResult.Decoded -> {
                cache.put(contentUri, result.bitmap)
                cachedDims.put(contentUri, maxDimPx)
                failedAtMs.remove(contentUri)
                result.bitmap
            }

            CoverResult.Missing -> {
                cache.put(contentUri, noCover)
                cachedDims.put(contentUri, maxDimPx)
                failedAtMs.remove(contentUri)
                null
            }

            CoverResult.Failed -> {
                failedAtMs.put(contentUri, clockMs())
                null
            }
        }
    }

    /** Setzt Caches und Quelle zurueck (nur Tests). */
    internal fun resetForTest() {
        cache.evictAll()
        cachedDims.evictAll()
        failedAtMs.evictAll()
        source = AndroidCoverSource
        clockMs = { SystemClock.elapsedRealtime() }
    }
}

/** Ergebnis eines Ladeversuchs. */
internal sealed interface CoverResult {
    class Decoded(
        val bitmap: ImageBitmap,
    ) : CoverResult

    /** Die Datei hat definitiv kein Cover (endgueltig, wird gemerkt). */
    data object Missing : CoverResult

    /** Laden gescheitert (Decoder ueberlastet, I/O-Fehler): kein Urteil, spaeter erneut versuchen. */
    data object Failed : CoverResult
}

/** Quelle fuer Cover-Bilder; austauschbar, damit Cache-Logik ohne Android-Medienstack testbar ist. */
internal interface CoverSource {
    fun read(
        context: Context,
        contentUri: String,
        maxDimPx: Int,
    ): CoverResult
}

/**
 * Probiert die Schritte der Reihe nach: das erste [CoverResult.Decoded] gewinnt (spaetere Schritte
 * laufen dann nicht mehr); sonst [CoverResult.Failed], wenn ein Schritt scheiterte, sonst [CoverResult.Missing].
 */
internal fun firstDecodedOrWorst(steps: List<() -> CoverResult>): CoverResult {
    var worst: CoverResult = CoverResult.Missing
    for (step in steps) {
        when (val result = step()) {
            is CoverResult.Decoded -> return result
            CoverResult.Failed -> worst = CoverResult.Failed
            CoverResult.Missing -> Unit
        }
    }
    return worst
}

/** Android-Implementierung: MediaProvider-Thumbnail und eingebettetes Bild. */
internal object AndroidCoverSource : CoverSource {
    override fun read(
        context: Context,
        contentUri: String,
        maxDimPx: Int,
    ): CoverResult {
        val uri = Uri.parse(contentUri)
        val thumbnail = { thumbnail(context, uri, maxDimPx) }
        val embedded = { embedded(context, uri, maxDimPx) }
        val steps =
            if (maxDimPx <=
                CoverArtLoader.THUMBNAIL_FIRST_MAX_DIM_PX
            ) {
                listOf(thumbnail, embedded)
            } else {
                listOf(embedded, thumbnail)
            }
        return firstDecodedOrWorst(steps)
    }

    /**
     * `ContentResolver.loadThumbnail` (API 29+): MediaProvider cached die Thumbnails und findet auch
     * Ordner-Cover. Eine IOException heisst "kein Thumbnail vorhanden" (endgueltig), Sicherheits- oder
     * Laufzeitfehler sind ein Fehlschlag.
     */
    private fun thumbnail(
        context: Context,
        uri: Uri,
        maxDimPx: Int,
    ): CoverResult {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return CoverResult.Missing
        return try {
            CoverResult.Decoded(
                context.contentResolver.loadThumbnail(uri, Size(maxDimPx, maxDimPx), null).asImageBitmap(),
            )
        } catch (_: IOException) {
            CoverResult.Missing
        } catch (_: SecurityException) {
            CoverResult.Failed
        } catch (_: RuntimeException) {
            CoverResult.Failed
        }
    }

    private fun embedded(
        context: Context,
        uri: Uri,
        maxDimPx: Int,
    ): CoverResult {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            val bytes = retriever.embeddedPicture
            val bitmap = bytes?.let { decodeScaled(it, maxDimPx) }
            if (bitmap == null) CoverResult.Missing else CoverResult.Decoded(bitmap)
        } catch (_: IOException) {
            CoverResult.Failed
        } catch (_: RuntimeException) {
            CoverResult.Failed
        } finally {
            runCatching { retriever.release() }
        }
    }

    /** Dekodiert [bytes] auf hoechstens [maxDimPx] (Potenz-von-2-Unterabtastung), null bei defektem Bild. */
    private fun decodeScaled(
        bytes: ByteArray,
        maxDimPx: Int,
    ): ImageBitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        // Wie bisher: beide Seiten bleiben >= maxDimPx (kein zu kleines Cover fuer die grosse Ansicht).
        while (bounds.outWidth / (sample * 2) >= maxDimPx && bounds.outHeight / (sample * 2) >= maxDimPx) {
            sample *= 2
        }
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)?.asImageBitmap()
    }
}
