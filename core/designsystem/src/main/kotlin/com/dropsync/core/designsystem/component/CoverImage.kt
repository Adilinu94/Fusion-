package com.dropsync.core.designsystem.component

import android.content.Context
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Gemeinsamer Cover-Lader fuer Bibliothek, Mini-Player und Now-Playing.
 * Liest das eingebettete Bild per MediaMetadataRetriever (minSdk 26,
 * keine neue Abhaengigkeit — Plan-Architekturentscheidung) und haelt
 * dekodierte Bitmaps in einem prozessweiten LRU-Cache, damit Listen
 * beim Scrollen nicht wiederholt dekodieren.
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
        val key = "$contentUri@$maxDimPx"
        when (val cached = cache.get(key)) {
            is ImageBitmap -> return cached
            noCover -> return null
        }
        val bitmap = withContext(Dispatchers.IO) { decode(context, contentUri, maxDimPx) }
        cache.put(key, bitmap ?: noCover)
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

/**
 * Zeigt das eingebettete Cover der Datei, sonst [fallback] (z. B. das
 * Marken-Notensymbol). Der Aufrufer gibt Form/Hintergrund per [modifier]
 * vor (clip + background), damit Kachelgroessen einheitlich bleiben.
 */
@Composable
fun CoverImage(
    contentUri: String?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    maxDimPx: Int = DEFAULT_COVER_DIM_PX,
    fallback: @Composable BoxScope.() -> Unit,
) {
    val context = LocalContext.current
    val cover by produceState<ImageBitmap?>(initialValue = null, contentUri, maxDimPx) {
        value = contentUri?.let { CoverArtLoader.load(context, it, maxDimPx) }
    }
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        val bitmap = cover
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = contentDescription,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            fallback()
        }
    }
}

/** Reicht fuer Listen-Kacheln; Now-Playing fordert explizit mehr an. */
const val DEFAULT_COVER_DIM_PX = 256
