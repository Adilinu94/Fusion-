package com.dropsync.core.designsystem.component

import android.content.Context
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Gemeinsamer Cover-Lader fuer Bibliothek, Mini-Player und Now-Playing.
 * Liest das eingebettete Bild per MediaMetadataRetriever (minSdk 26,
 * keine neue Abhaengigkeit — Plan-Architekturentscheidung) und haelt
 * dekodierte Bitmaps in einem prozessweiten LRU-Cache, damit Listen
 * beim Scrollen nicht wiederholt dekodieren.
 *
 * P1-Fix (ein Dekodierpfad): der Cache-Schluessel ist die DATEI, nicht
 * "Datei@Zielgroesse". Vorher lag dasselbe Cover dreifach im Cache und
 * wurde dreimal dekodiert — 1024 px fuer Now-Playing, 512 px fuer die
 * Farbextraktion, 256 px fuer Listen. Jetzt wird EINMAL in der groessten
 * angeforderten Groesse dekodiert; kleinere Anforderungen bekommen dieselbe
 * Bitmap (Compose skaliert beim Zeichnen ohnehin, und die Farbextraktion
 * ist von der Auflösung unabhaengig).
 *
 * D2 (MatchingDeclarationName): eigene Datei statt Beifang in `CoverImage.kt`
 * — Lader und Composable sind zwei getrennte Zustaendigkeiten.
 */
object CoverArtLoader {
    /** Platzhalter fuer "Datei hat kein Cover" — verhindert erneute Laeufe. */
    private val noCover = Any()

    private val cache =
        object : LruCache<String, Any>(cacheSizeKb()) {
            override fun sizeOf(
                key: String,
                value: Any,
            ): Int =
                when (value) {
                    is ImageBitmap -> (value.width * value.height * BYTES_PER_PIXEL) / KILO
                    else -> 1
                }
        }

    /** Groesse, in der eine Datei bereits im Cache liegt. */
    private val cachedDims = mutableMapOf<String, Int>()

    /** Achtel des Heaps, gedeckelt auf 32 MB (in KB). */
    private fun cacheSizeKb(): Int {
        val maxKb = (Runtime.getRuntime().maxMemory() / KILO).toInt()
        return (maxKb / 8).coerceAtMost(MAX_CACHE_KB)
    }

    suspend fun load(
        context: Context,
        contentUri: String,
        maxDimPx: Int,
    ): ImageBitmap? {
        // Ein Treffer zaehlt, wenn die gecachte Bitmap mindestens so gross
        // ist wie angefordert. Nur bei einer GROESSEREN Anforderung wird neu
        // dekodiert (und der kleinere Eintrag ersetzt).
        val cachedDim = synchronized(cachedDims) { cachedDims[contentUri] }
        if (cachedDim != null && cachedDim >= maxDimPx) {
            when (val cached = cache.get(contentUri)) {
                is ImageBitmap -> return cached
                noCover -> return null
            }
        }
        val bitmap = withContext(Dispatchers.IO) { decode(context, contentUri, maxDimPx) }
        cache.put(contentUri, bitmap ?: noCover)
        synchronized(cachedDims) { cachedDims[contentUri] = maxDimPx }
        return bitmap
    }

    private fun decode(
        context: Context,
        contentUri: String,
        maxDimPx: Int,
    ): ImageBitmap? =
        runCatching {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, Uri.parse(contentUri))
                retriever.embeddedPicture?.let { bytes -> decodeScaled(bytes, maxDimPx) }
            } finally {
                retriever.release()
            }
        }.getOrNull()

    /** Zweistufiges Dekodieren mit inSampleSize gegen unnoetig grosse Bitmaps. */
    private fun decodeScaled(
        bytes: ByteArray,
        maxDimPx: Int,
    ): ImageBitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= maxDimPx && bounds.outHeight / (sample * 2) >= maxDimPx) {
            sample *= 2
        }
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)?.asImageBitmap()
    }

    private const val BYTES_PER_PIXEL = 4
    private const val KILO = 1024
    private const val MAX_CACHE_KB = 32 * 1024
}
