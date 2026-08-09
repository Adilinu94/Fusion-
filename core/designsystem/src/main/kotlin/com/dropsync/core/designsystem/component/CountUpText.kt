package com.dropsync.core.designsystem.component

import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.tween
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle

/**
 * Zahl, die beim Erscheinen/Aendern hochzaehlt (Design.txt "Count-Up") - fuer
 * Statistiken und den Peak-End-Moment. Nutzt tabellarische Ziffern via
 * Display-Stile, damit die Zahl nicht springt.
 */
@Composable
fun CountUpText(
    targetValue: Int,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.displayMedium,
    color: Color = Color.Unspecified,
    durationMillis: Int = 600,
    formatter: (Int) -> String = { it.toString() },
) {
    val animated by animateIntAsState(
        targetValue = targetValue,
        animationSpec = tween(durationMillis = durationMillis),
        label = "countUp",
    )
    Text(text = formatter(animated), style = style, color = color, modifier = modifier)
}
