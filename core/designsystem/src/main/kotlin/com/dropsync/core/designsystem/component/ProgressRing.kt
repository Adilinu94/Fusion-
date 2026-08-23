package com.dropsync.core.designsystem.component

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Kreisfoermiger Fortschrittsring (Design.txt "Progress Ring / Spring").
 * Lime-Fortschritt auf grauem Rest-Track, feder-animiert. Der zentrale Inhalt
 * (z. B. grosse Restzeit) wird via [content] in die Mitte gelegt.
 *
 * [excessProgress] zeichnet einen duennen Zweitbogen konzentrisch IN den Ring
 * (UI-Vertrag R2): Der Hauptbogen bleibt bei 100 Prozent stehen, der
 * Ueberschuss ueber das Ziel wird separat sichtbar. Default 0 — der Bogen
 * entfaellt, bestehende Aufrufer aendern sich nicht.
 */
@Composable
fun ProgressRing(
    progress: Float,
    modifier: Modifier = Modifier,
    ringSize: Dp = 220.dp,
    strokeWidth: Dp = 14.dp,
    trackColor: Color = MaterialTheme.colorScheme.surfaceVariant,
    progressColor: Color = MaterialTheme.colorScheme.primary,
    excessProgress: Float = 0f,
    excessStrokeWidth: Dp = 6.dp,
    excessColor: Color = MaterialTheme.colorScheme.secondary,
    content: @Composable BoxScope.() -> Unit = {},
) {
    val animated by animateFloatAsState(
        targetValue = progress.coerceIn(0f, 1f),
        animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessLow),
        label = "progressRing",
    )
    val animatedExcess by animateFloatAsState(
        targetValue = excessProgress.coerceIn(0f, 1f),
        animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessLow),
        label = "progressRingExcess",
    )
    Box(modifier = modifier.size(ringSize), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val stroke = strokeWidth.toPx()
            val diameter = size.minDimension - stroke
            val topLeft = Offset((size.width - diameter) / 2f, (size.height - diameter) / 2f)
            val arcSize = Size(diameter, diameter)
            drawArc(
                color = trackColor,
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
            drawArc(
                color = progressColor,
                startAngle = -90f,
                sweepAngle = 360f * animated,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
            if (animatedExcess > 0f) {
                val excessStroke = excessStrokeWidth.toPx()
                val excessDiameter = (diameter - stroke - excessStroke * 2f).coerceAtLeast(excessStroke)
                drawArc(
                    color = excessColor,
                    startAngle = -90f,
                    sweepAngle = 360f * animatedExcess,
                    useCenter = false,
                    topLeft =
                        Offset(
                            (size.width - excessDiameter) / 2f,
                            (size.height - excessDiameter) / 2f,
                        ),
                    size = Size(excessDiameter, excessDiameter),
                    style = Stroke(width = excessStroke, cap = StrokeCap.Round),
                )
            }
        }
        content()
    }
}
