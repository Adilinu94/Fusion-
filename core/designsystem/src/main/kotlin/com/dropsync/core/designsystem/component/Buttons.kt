package com.dropsync.core.designsystem.component

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

// Marken-Buttons gemaess Design.txt: Pill (radius 999), Hoehe 56, Bold-Label.
// Statt Web-Hover ein dezenter Press-Scale (Motion-Sprache "springy").
private val ButtonHeight = 56.dp
private val PillShape = RoundedCornerShape(percent = 50)

@Composable
private fun rememberPressScale(source: MutableInteractionSource): Float {
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(targetValue = if (pressed) 0.97f else 1f, label = "pressScale")
    return scale
}

/** Primaere Aktion: Lime-Flaeche, schwarzer Text. Lime ist hierfuer reserviert. */
@Composable
fun BrandButtonPrimary(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leadingIcon: ImageVector? = null,
) {
    val source = remember { MutableInteractionSource() }
    val scale = rememberPressScale(source)
    Button(
        onClick = onClick,
        modifier = modifier.heightIn(min = ButtonHeight).scale(scale),
        enabled = enabled,
        shape = PillShape,
        colors =
            ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ),
        contentPadding = PaddingValues(horizontal = 24.dp),
        interactionSource = source,
    ) {
        ButtonRow(text = text, leadingIcon = leadingIcon)
    }
}

/** Sekundaere Aktion: kontrastierende Vollflaeche (schwarz/weiss je nach Theme). */
@Composable
fun BrandButtonSecondary(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leadingIcon: ImageVector? = null,
) {
    val source = remember { MutableInteractionSource() }
    val scale = rememberPressScale(source)
    Button(
        onClick = onClick,
        modifier = modifier.heightIn(min = ButtonHeight).scale(scale),
        enabled = enabled,
        shape = PillShape,
        colors =
            ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.secondary,
                contentColor = MaterialTheme.colorScheme.onSecondary,
            ),
        contentPadding = PaddingValues(horizontal = 24.dp),
        interactionSource = source,
    ) {
        ButtonRow(text = text, leadingIcon = leadingIcon)
    }
}

/** Tertiaere Aktion: Ghost mit Hairline-Border, ohne Fuellung. */
@Composable
fun BrandButtonGhost(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leadingIcon: ImageVector? = null,
) {
    val source = remember { MutableInteractionSource() }
    val scale = rememberPressScale(source)
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = ButtonHeight).scale(scale),
        enabled = enabled,
        shape = PillShape,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurface),
        contentPadding = PaddingValues(horizontal = 24.dp),
        interactionSource = source,
    ) {
        ButtonRow(text = text, leadingIcon = leadingIcon)
    }
}

@Composable
private fun ButtonRow(
    text: String,
    leadingIcon: ImageVector?,
) {
    if (leadingIcon != null) {
        Icon(imageVector = leadingIcon, contentDescription = null, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
    }
    Text(text = text, style = MaterialTheme.typography.labelLarge)
}
