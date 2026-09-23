package com.dropsync.core.designsystem.component

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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext

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
