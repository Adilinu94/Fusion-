package com.dropsync.feature.workout

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.dropsync.core.designsystem.theme.rememberAccentTextColor

/**
 * Live sensor waveform (Fusion Phase 4 step 5): rolling line plot of the
 * acceleration magnitude stream plus a brief flash overlay when the caller
 * reports a rep peak. Purely visual — never counts live (shadow-pipeline
 * rule, design doc section 11b).
 *
 * P1-Fix: [samples] ist ein FloatArray-Snapshot (keine geboxte Liste), der
 * Path wird ueber `drawWithCache` nur bei neuen Samples oder neuer Groesse
 * aufgebaut statt bei jedem Frame, und die vertikale Skala ist FEST. Die
 * frueher automatische Skalierung liess Ruhephasen genauso gross aussehen
 * wie echte Wiederholungen — visuell irrefuehrend.
 *
 * RC-8: aus dem Train-Tab herausgezogen, damit der Kalibrier-Wizard dieselbe
 * Komponente nutzt (Design: "Live-Signal (vorhandene Waveform-Komponente)").
 */
@Composable
internal fun SensorWaveform(
    samples: FloatArray,
    modifier: Modifier = Modifier,
    lastPeakMs: Long = 0L,
) {
    val lineColor = rememberAccentTextColor()
    val flashColor = MaterialTheme.colorScheme.tertiary
    val waveformDescription = stringResource(R.string.a11y_sensor_waveform)

    // Peak flash: visible for ~400 ms after the last detected peak.
    var flashVisible by remember { mutableStateOf(false) }
    LaunchedEffect(lastPeakMs) {
        if (lastPeakMs > 0) {
            flashVisible = true
            kotlinx.coroutines.delay(400)
            flashVisible = false
        }
    }

    Card(
        modifier =
            modifier
                .fillMaxWidth()
                .semantics { contentDescription = waveformDescription },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(72.dp)
                    .padding(8.dp),
        ) {
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .drawWithCache {
                            val path = Path()
                            if (samples.size >= 2) {
                                val stepX = size.width / (samples.size - 1)
                                samples.forEachIndexed { i, v ->
                                    // Feste Skala: 0 g unten, WAVEFORM_MAX_G oben.
                                    val norm = (v / WAVEFORM_MAX_G).coerceIn(0f, 1f)
                                    val x = i * stepX
                                    val y = size.height - norm * size.height
                                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                                }
                            }
                            val stroke = Stroke(width = 2.dp.toPx())
                            onDrawBehind {
                                if (samples.size >= 2) drawPath(path, color = lineColor, style = stroke)
                                if (flashVisible) drawRect(color = flashColor.copy(alpha = 0.25f))
                            }
                        },
            )
        }
    }
}

/**
 * Obere Grenze der festen Waveform-Skala in g. 1 g ist Ruhe (Erdanziehung),
 * kraeftige Wiederholungen erreichen etwa 2 g; 3 g laesst Spitzen Luft, ohne
 * Ruhephasen optisch aufzublasen.
 */
private const val WAVEFORM_MAX_G = 3f
