package com.dropsync.core.designsystem.chart

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Eigene, offline-konforme Compose-Canvas-Charts (Schritt 12): keine neue
// Abhaengigkeit, animiertes Einzeichnen, Lime-Akzent via MaterialTheme.
// Barrierefreiheit: Der Aufrufer liefert eine zusammenfassende
// contentDescription (z. B. "1RM-Trend: 8 Sessions, zuletzt 92,5 kg"),
// statt jeden Datenpunkt vorzulesen (analog 12.4).

private const val CHART_ANIMATION_MILLIS = 700

/**
 * Animiertes Balkendiagramm (z. B. Session-Volumen). Balkenhoehen sind auf
 * den Maximalwert normalisiert; die Hoehe waechst beim Einblenden von 0 auf
 * den Zielwert. Groesse bestimmt der Aufrufer ueber den [modifier].
 *
 * Dashboard-Erweiterung (UI-Vertrag R4), alles per Default abgeschaltet:
 * [highlightIndex] faerbt einen Balken (die aktuelle Woche), [fulfilled]
 * unterlegt erreichte Wochen mit einer 2-dp-Grundlinien-Markierung, und
 * [pillText] zeichnet eine Wert-Pille ueber dem hervorgehobenen Balken —
 * Pillen-Flaeche [pillColor] mit dunkler Schrift [pillTextColor] (Grafik auf
 * Grafik, Ratio 3,42). Wochen ohne Wert zeigen eine 2-dp-Markierung auf der
 * Grundlinie statt eines Nullbalkens.
 */
@Composable
fun BarChart(
    values: List<Float>,
    modifier: Modifier = Modifier,
    barColor: Color = MaterialTheme.colorScheme.primary,
    contentDescription: String? = null,
    highlightIndex: Int? = null,
    highlightColor: Color = MaterialTheme.colorScheme.secondary,
    fulfilled: List<Boolean> = emptyList(),
    fulfilledColor: Color = MaterialTheme.colorScheme.secondary,
    pillText: String? = null,
    pillColor: Color = MaterialTheme.colorScheme.primary,
    pillTextColor: Color = MaterialTheme.colorScheme.onPrimary,
) {
    val baselineColor = MaterialTheme.colorScheme.outlineVariant
    val progress = remember(values) { Animatable(0f) }
    LaunchedEffect(values) {
        progress.snapTo(0f)
        progress.animateTo(1f, animationSpec = tween(CHART_ANIMATION_MILLIS))
    }
    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer()
    val pillLayout =
        remember(pillText) {
            pillText?.let {
                textMeasurer.measure(
                    it,
                    TextStyle(fontSize = 12.sp, fontWeight = FontWeight.SemiBold),
                )
            }
        }
    val strokeWidthPx = with(density) { 1.dp.toPx() }
    val cornerPx = with(density) { 3.dp.toPx() }
    val pillPadHPx = with(density) { 8.dp.toPx() }
    val pillPadVPx = with(density) { 3.dp.toPx() }
    val pillGapPx = with(density) { 6.dp.toPx() }
    val markPx = with(density) { 2.dp.toPx() }
    val pillHeightPx = pillLayout?.let { it.size.height + 2 * pillPadVPx } ?: 0f
    val pillWidthPx = pillLayout?.let { it.size.width + 2 * pillPadHPx } ?: 0f
    // Reserven fuer die Pille: Der Chart muendet in die Grundlinie, oben bleibt
    // Platz fuer die Wert-Pille ueber dem hervorgehobenen Balken.
    val topReserve = if (pillLayout != null && highlightIndex != null) pillHeightPx + pillGapPx else 0f
    val desc = contentDescription
    val semanticsModifier =
        if (desc != null) {
            modifier.semantics { this.contentDescription = desc }
        } else {
            modifier
        }
    Canvas(modifier = semanticsModifier) {
        if (values.isEmpty()) return@Canvas
        val w = size.width
        val h = size.height
        val chartHeight = h - topReserve
        val max = values.max().takeIf { it > 0f } ?: 1f
        val gap = w * 0.02f
        val barWidth = ((w - gap * (values.size - 1)) / values.size).coerceAtLeast(1f)
        drawLine(
            color = baselineColor,
            start = Offset(0f, h),
            end = Offset(w, h),
            strokeWidth = strokeWidthPx,
        )
        values.forEachIndexed { index, value ->
            val left = index * (barWidth + gap)
            val norm = (value / max).coerceIn(0f, 1f)
            val barHeight = norm * chartHeight * progress.value
            drawWeekBar(
                left = left,
                barWidth = barWidth,
                barHeight = barHeight,
                highlighted = index == highlightIndex,
                fulfilled = fulfilled.getOrNull(index) == true,
                barColor = barColor,
                highlightColor = highlightColor,
                fulfilledColor = fulfilledColor,
                markPx = markPx,
                cornerPx = cornerPx,
            )
            if (pillLayout != null && index == highlightIndex) {
                drawValuePill(
                    pillLayout = pillLayout,
                    centerX = left + barWidth / 2f,
                    aboveBarTop = h - barHeight,
                    canvasWidth = w,
                    pillWidthPx = pillWidthPx,
                    pillHeightPx = pillHeightPx,
                    pillGapPx = pillGapPx,
                    padHPx = pillPadHPx,
                    padVPx = pillPadVPx,
                    pillColor = pillColor,
                    pillTextColor = pillTextColor,
                )
            }
        }
    }
}

/** Ein Balken inklusive Null- und Erfuellt-Markierung auf der Grundlinie (R4). */
private fun DrawScope.drawWeekBar(
    left: Float,
    barWidth: Float,
    barHeight: Float,
    highlighted: Boolean,
    fulfilled: Boolean,
    barColor: Color,
    highlightColor: Color,
    fulfilledColor: Color,
    markPx: Float,
    cornerPx: Float,
) {
    if (barHeight <= 0f) {
        // Ein Nullbalken sieht wie ein Fehler aus; die Markierung
        // liest sich als "hier war nichts" (R4).
        drawRect(
            color = barColor,
            topLeft = Offset(left, size.height - markPx),
            size = Size(barWidth, markPx),
        )
    } else {
        drawRoundRect(
            color = if (highlighted) highlightColor else barColor,
            topLeft = Offset(left, size.height - barHeight),
            size = Size(barWidth, barHeight),
            cornerRadius = CornerRadius(cornerPx, cornerPx),
        )
    }
    if (fulfilled) {
        drawRect(
            color = fulfilledColor,
            topLeft = Offset(left, size.height - markPx),
            size = Size(barWidth, markPx),
        )
    }
}

/** Wert-Pille ueber dem hervorgehobenen Balken: Flaeche plus dunkler Text. */
private fun DrawScope.drawValuePill(
    pillLayout: TextLayoutResult,
    centerX: Float,
    aboveBarTop: Float,
    canvasWidth: Float,
    pillWidthPx: Float,
    pillHeightPx: Float,
    pillGapPx: Float,
    padHPx: Float,
    padVPx: Float,
    pillColor: Color,
    pillTextColor: Color,
) {
    val pillLeft =
        (centerX - pillWidthPx / 2f)
            .coerceIn(0f, (canvasWidth - pillWidthPx).coerceAtLeast(0f))
    val pillTop = (aboveBarTop - pillGapPx - pillHeightPx).coerceAtLeast(0f)
    drawRoundRect(
        color = pillColor,
        topLeft = Offset(pillLeft, pillTop),
        size = Size(pillWidthPx, pillHeightPx),
        cornerRadius = CornerRadius(pillHeightPx / 2f, pillHeightPx / 2f),
    )
    drawText(
        textLayoutResult = pillLayout,
        color = pillTextColor,
        topLeft = Offset(pillLeft + padHPx, pillTop + padVPx),
    )
}
