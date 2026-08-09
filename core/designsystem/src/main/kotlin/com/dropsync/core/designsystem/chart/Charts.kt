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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

// Eigene, offline-konforme Compose-Canvas-Charts (Schritt 12): keine neue
// Abhaengigkeit, animiertes Einzeichnen, Lime-Akzent via MaterialTheme.
// Barrierefreiheit: Der Aufrufer liefert eine zusammenfassende
// contentDescription (z. B. "1RM-Trend: 8 Sessions, zuletzt 92,5 kg"),
// statt jeden Datenpunkt vorzulesen (analog 12.4).

private const val CHART_ANIMATION_MILLIS = 700

/**
 * Animierte Trendlinie (z. B. geschaetztes 1RM oder Bestlast je Session).
 * Werte werden auf min..max normalisiert; bei nur einem Wert wird eine
 * horizontale Linie in der Mitte gezeichnet. Groesse bestimmt der Aufrufer
 * ueber den [modifier].
 */
@Composable
fun LineChart(
    values: List<Float>,
    modifier: Modifier = Modifier,
    lineColor: Color = MaterialTheme.colorScheme.primary,
    fill: Boolean = true,
    contentDescription: String? = null,
) {
    val baselineColor = MaterialTheme.colorScheme.outlineVariant
    val progress = remember(values) { Animatable(0f) }
    LaunchedEffect(values) {
        progress.snapTo(0f)
        progress.animateTo(1f, animationSpec = tween(CHART_ANIMATION_MILLIS))
    }
    val strokeWidthPx = with(LocalDensity.current) { 2.5.dp.toPx() }
    val dotRadiusPx = with(LocalDensity.current) { 4.dp.toPx() }
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
        // Innenabstand, damit Strich und Endpunkt nicht abgeschnitten werden.
        val pad = dotRadiusPx + strokeWidthPx
        val min = values.min()
        val max = values.max()
        val span = (max - min).takeIf { it > 0f } ?: 1f
        val stepX = if (values.size > 1) (w - 2 * pad) / (values.size - 1) else 0f

        fun pointAt(index: Int): Offset {
            val x = pad + stepX * index
            val norm = (values[index] - min) / span
            val y = pad + (1f - norm) * (h - 2 * pad)
            return Offset(x, y)
        }
        // Baseline am unteren Rand als ruhige Referenz.
        drawLine(
            color = baselineColor,
            start = Offset(pad, h - pad),
            end = Offset(w - pad, h - pad),
            strokeWidth = strokeWidthPx / 2,
        )
        // Animiertes Einzeichnen: nur der Anteil bis progress ist sichtbar.
        val visibleCount = 1 + ((values.size - 1) * progress.value).toInt()
        val partial = ((values.size - 1) * progress.value) - (visibleCount - 1)
        val linePath = Path()
        linePath.moveTo(pointAt(0).x, pointAt(0).y)
        for (i in 1 until visibleCount) {
            val p = pointAt(i)
            linePath.lineTo(p.x, p.y)
        }
        var tip = pointAt(visibleCount - 1)
        if (visibleCount < values.size && partial > 0f) {
            val next = pointAt(visibleCount)
            tip =
                Offset(
                    tip.x + (next.x - tip.x) * partial,
                    tip.y + (next.y - tip.y) * partial,
                )
            linePath.lineTo(tip.x, tip.y)
        }
        if (fill && values.size > 1) {
            val areaPath = Path()
            areaPath.addPath(linePath)
            areaPath.lineTo(tip.x, h - pad)
            areaPath.lineTo(pad, h - pad)
            areaPath.close()
            drawPath(path = areaPath, color = lineColor.copy(alpha = 0.15f), style = Fill)
        }
        drawPath(
            path = linePath,
            color = lineColor,
            style =
                Stroke(
                    width = strokeWidthPx,
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round,
                ),
        )
        // Endpunkt betont den aktuellsten Wert.
        drawCircle(color = lineColor, radius = dotRadiusPx, center = tip)
    }
}

/**
 * Animiertes Balkendiagramm (z. B. Session-Volumen). Balkenhoehen sind auf
 * den Maximalwert normalisiert; die Hoehe waechst beim Einblenden von 0 auf
 * den Zielwert. Groesse bestimmt der Aufrufer ueber den [modifier].
 */
@Composable
fun BarChart(
    values: List<Float>,
    modifier: Modifier = Modifier,
    barColor: Color = MaterialTheme.colorScheme.primary,
    contentDescription: String? = null,
) {
    val baselineColor = MaterialTheme.colorScheme.outlineVariant
    val progress = remember(values) { Animatable(0f) }
    LaunchedEffect(values) {
        progress.snapTo(0f)
        progress.animateTo(1f, animationSpec = tween(CHART_ANIMATION_MILLIS))
    }
    val strokeWidthPx = with(LocalDensity.current) { 1.dp.toPx() }
    val cornerPx = with(LocalDensity.current) { 3.dp.toPx() }
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
            val norm = (value / max).coerceIn(0f, 1f)
            val barHeight = norm * h * progress.value
            if (barHeight <= 0f) return@forEachIndexed
            val left = index * (barWidth + gap)
            drawRoundRect(
                color = barColor,
                topLeft = Offset(left, h - barHeight),
                size = Size(barWidth, barHeight),
                cornerRadius = CornerRadius(cornerPx, cornerPx),
            )
        }
    }
}
