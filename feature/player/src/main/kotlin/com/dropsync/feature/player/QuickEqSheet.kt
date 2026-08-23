package com.dropsync.feature.player

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.dropsync.domain.audio.DspConfig
import com.dropsync.domain.audio.EqBand
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * EQ-Schnellzugriff aus dem Player (Recherche 2026: Aktions-Carousel +
 * vertikale Slider statt Umweg ueber die Einstellungen). Vollwertige
 * Regler ohne Experimental-Api: eigene Canvas-Slider mit 48-dp-Bedien-
 * flaeche, 0-dB-Rasterung und Haptik beim Nulldurchgang; abgeschlossen
 * (onValueChangeFinished) wird der Gain persistent in die DSP-Kette
 * geschrieben, waehrend des Drags nur lokal angezeigt.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun QuickEqSheet(
    config: DspConfig,
    onDismiss: () -> Unit,
    onSetEnabled: (Boolean) -> Unit,
    onBandGainFinished: (Int, Double) -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.quick_eq_title),
                    style = MaterialTheme.typography.titleLarge,
                )
                Text(
                    text = stringResource(R.string.quick_eq_bands, config.eq.bands.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = config.eq.enabled,
                onCheckedChange = onSetEnabled,
            )
        }
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            config.eq.bands.forEachIndexed { index, band ->
                VerticalBandSlider(
                    label = formatHz(band.frequencyHz),
                    value = band.gainDb.toFloat(),
                    enabled = config.eq.enabled,
                    onValueFinished = { onBandGainFinished(index, it.toDouble()) },
                )
            }
        }
        Spacer(modifier = Modifier.height(16.dp))
    }
}

/**
 * Vertikaler Band-Regler: Spur mit Nulllinie und Daumen; Drag UND Tap
 * setzen den Wert, Rasterung auf 0,5 dB. Die Bedienflaeche ist 48 dp
 * breit (A11y-Mindestgroesse), die Optik bleibt schlank.
 */
@Composable
private fun VerticalBandSlider(
    label: String,
    value: Float,
    enabled: Boolean,
    onValueFinished: (Float) -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    var draggingValue by remember(label) { mutableFloatStateOf(value) }
    var lastAboveZero by remember(label) { mutableStateOf(value > 0f) }
    val activeColor =
        if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    val trackColor = MaterialTheme.colorScheme.surfaceVariant

    fun update(raw: Float) {
        val snapped = (raw * 2).roundToInt() / 2f
        draggingValue = snapped
        val aboveZero = snapped > 0f
        if (aboveZero != lastAboveZero) {
            lastAboveZero = aboveZero
            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }
    }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Canvas(
            modifier =
                Modifier
                    .width(48.dp)
                    .height(SLIDER_HEIGHT)
                    .semantics { contentDescription = "$label ${draggingValue}dB" }
                    .pointerInput(enabled) {
                        detectTapGestures(
                            onTap = { offset ->
                                if (enabled) {
                                    update(1f - offset.y / size.height)
                                    onValueFinished(draggingValue)
                                }
                            },
                        )
                    }.pointerInput(enabled) {
                        detectVerticalDragGestures(
                            onVerticalDrag = { change, _ ->
                                if (enabled) {
                                    update(1f - change.position.y / size.height)
                                }
                            },
                            onDragEnd = { onValueFinished(draggingValue) },
                        )
                    },
        ) {
            val sliderWidth = 10.dp.toPx()
            val left = (size.width - sliderWidth) / 2f
            // Spur
            drawRoundRect(
                color = trackColor,
                topLeft = Offset(left, 0f),
                size = Size(sliderWidth, size.height),
                cornerRadius = CornerRadius(sliderWidth / 2f),
            )
            // Nulllinie
            drawLine(
                color = trackColor.copy(alpha = 1f),
                start = Offset(left - 6.dp.toPx(), size.height / 2f),
                end = Offset(left + sliderWidth + 6.dp.toPx(), size.height / 2f),
                strokeWidth = 1.dp.toPx(),
            )
            // Wert: Anteil vom unteren Ende (Range -GAIN..+GAIN)
            val fraction = ((draggingValue - MIN_GAIN) / (MAX_GAIN - MIN_GAIN)).coerceIn(0f, 1f)
            val fillHeight = abs(size.height / 2f - (1f - fraction) * size.height)
            val fillTop =
                if (draggingValue >= 0f) {
                    (1f - fraction) * size.height
                } else {
                    size.height / 2f
                }
            drawRoundRect(
                color = activeColor,
                topLeft = Offset(left, fillTop),
                size = Size(sliderWidth, fillHeight.coerceAtLeast(2f)),
                cornerRadius = CornerRadius(sliderWidth / 2f),
            )
            // Daumen
            val thumbY = (1f - fraction) * size.height
            drawCircle(
                color = activeColor,
                radius = 7.dp.toPx(),
                center = Offset(size.width / 2f, thumbY),
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = formatDb(draggingValue),
            style = MaterialTheme.typography.labelSmall,
            color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private val SLIDER_HEIGHT = 180.dp
private val MIN_GAIN = EqBand.MIN_GAIN_DB.toFloat()
private val MAX_GAIN = EqBand.MAX_GAIN_DB.toFloat()

private fun formatHz(hz: Double): String =
    if (hz >= 1000.0) {
        String.format(Locale.ROOT, "%.0fk", hz / 1000.0)
    } else {
        hz.roundToInt().toString()
    }

private fun formatDb(db: Float): String =
    if (db > 0f) {
        String.format(Locale.ROOT, "+%.1f", db)
    } else {
        String.format(Locale.ROOT, "%.1f", db)
    }
