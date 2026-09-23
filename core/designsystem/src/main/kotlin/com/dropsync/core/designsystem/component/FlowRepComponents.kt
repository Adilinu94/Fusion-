package com.dropsync.core.designsystem.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dropsync.core.designsystem.icon.BrandIcons
import com.dropsync.core.designsystem.theme.Spacing

/** Shared surface contract for FlowRep screens; feature modules do not set colors directly. */
@Composable
fun FlowRepSurface(
    modifier: Modifier = Modifier,
    contentPadding: androidx.compose.foundation.layout.PaddingValues =
        androidx.compose.foundation.layout
            .PaddingValues(Spacing.space24),
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(Spacing.radiusMedium),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.58f)),
        tonalElevation = 0.dp,
    ) {
        Column(modifier = Modifier.padding(contentPadding), content = content)
    }
}

/** Accessible 48dp icon control for secondary, compact actions. */
@Composable
fun FlowRepIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    IconButton(
        onClick = onClick,
        modifier =
            modifier
                .size(48.dp)
                .semantics {
                    this.contentDescription = contentDescription
                    role = Role.Button
                },
    ) {
        Icon(imageVector = icon, contentDescription = null)
    }
}

/** Full-width primary action placed at the end of a workflow. */
@Composable
fun FlowRepPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    BrandButtonPrimary(
        text = text,
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth().heightIn(min = 56.dp),
    )
}

@Composable
fun FlowRepSectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = title, style = MaterialTheme.typography.titleLarge)
        action?.invoke()
    }
}

/**
 * Einheitliches App-Bar-Muster (UI-Befund 4.1.6): Zurueck-Taste + Titel in
 * einer Zeile. Ersetzt die vier Varianten (M3-TopAppBar, CategoryHeader,
 * FlowRepIconButton-Reihe, TextButton "Back") durch einen Look.
 */
@Composable
fun FlowRepTopBar(
    title: String,
    onBack: () -> Unit,
    backContentDescription: String,
    modifier: Modifier = Modifier,
    actions: (@Composable RowScope.() -> Unit)? = null,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(start = Spacing.space4, top = Spacing.space4, end = Spacing.space12),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(
            onClick = onBack,
            modifier =
                Modifier
                    .size(48.dp)
                    .semantics {
                        contentDescription = backContentDescription
                        role = Role.Button
                    },
        ) {
            Icon(
                painter = painterResource(BrandIcons.Back),
                contentDescription = null,
            )
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        actions?.invoke(this)
    }
}

/**
 * Einheitlicher Leerzustand (UI-Befund 4.2.7): ersetzt die bisherigen
 * Eigenbauten in den Features (Library-EmptyHint, fehlende Zustaende in
 * ExerciseLibrary/AllSets). Zentriert, ruhig, ohne Icon-Zwang.
 */
@Composable
fun FlowRepEmptyState(
    text: String,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.fillMaxSize().padding(Spacing.space24),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * Einheitlicher Fehlerzustand (UI-Befund 4.2.7): Fehlerfarbe, optional mit
 * Wiederholen-Aktion. Bewusst kein AlertDialog — Fehler gehoeren in den
 * Inhalt, nicht ueber ihn.
 */
@Composable
fun FlowRepErrorState(
    text: String,
    modifier: Modifier = Modifier,
    onRetry: (() -> Unit)? = null,
    retryLabel: String? = null,
) {
    Column(
        modifier = modifier.fillMaxSize().padding(Spacing.space24),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.error,
            textAlign = TextAlign.Center,
        )
        if (onRetry != null && retryLabel != null) {
            FlowRepPrimaryButton(
                text = retryLabel,
                onClick = onRetry,
                modifier = Modifier.padding(top = Spacing.space16),
            )
        }
    }
}
