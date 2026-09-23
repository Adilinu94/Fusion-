package com.dropsync.app

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.snap
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.dropsync.core.designsystem.component.FlowRepPrimaryButton
import com.dropsync.core.designsystem.icon.BrandIcons
import com.dropsync.core.designsystem.theme.LocalReducedMotion
import com.dropsync.core.designsystem.theme.rememberAccentTextColor

/**
 * First-Run-Onboarding (Ausbauplan B3): drei Seiten ohne Wischzwang —
 * Musik, Marker/Drop-Rest, Training. Ersetzt die kommentarlos angefragten
 * Berechtigungen durch Kontext: Wer versteht, wofuer Timer-Cues und
 * Herzfrequenz da sind, entscheidet informiert, wenn das System fragt.
 */
@Composable
fun OnboardingScreen(
    onFinish: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var page by remember { mutableIntStateOf(0) }
    val last = page >= ONBOARDING_PAGES.size - 1

    Column(
        modifier =
            modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        // Reduced Motion: Seitenwechsel ohne Transition (sofortiger Inhalt).
        // transitionSpec ist kein @Composable-Kontext: Wert vorher einfangen.
        val reducedMotion = LocalReducedMotion.current
        AnimatedContent(
            targetState = page,
            transitionSpec = {
                if (reducedMotion) {
                    (fadeIn(snap()) togetherWith fadeOut(snap()))
                } else {
                    fadeIn() togetherWith fadeOut()
                }
            },
            label = "onboarding-page",
        ) { current ->
            val (icon, titleRes, descRes) = ONBOARDING_PAGES[current]
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    painterResource(icon),
                    contentDescription = null,
                    tint = rememberAccentTextColor(),
                    modifier = Modifier.size(72.dp),
                )
                Spacer(Modifier.height(24.dp))
                Text(
                    text = stringResource(titleRes),
                    style = MaterialTheme.typography.headlineMedium,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    text = stringResource(descRes),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
        Spacer(Modifier.height(32.dp))
        // C5 (U-8): Die Punkte sind Zierde — TalkBack bekommt stattdessen
        // einen Satz ("Seite 2 von 3").
        val pageIndicator =
            stringResource(R.string.onboarding_page_indicator, page + 1, ONBOARDING_PAGES.size)
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier =
                Modifier.semantics(mergeDescendants = true) {
                    contentDescription = pageIndicator
                },
        ) {
            ONBOARDING_PAGES.indices.forEach { index ->
                Surface(
                    color =
                        if (index == page) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        },
                    modifier = Modifier.size(8.dp).clip(CircleShape),
                    content = {},
                )
            }
        }
        Spacer(Modifier.height(32.dp))
        FlowRepPrimaryButton(
            text = stringResource(if (last) R.string.onboarding_start else R.string.onboarding_next),
            onClick = { if (last) onFinish() else page++ },
            modifier = Modifier.fillMaxWidth(),
        )
        // Befund 7.1.1: Schritt zurueck (Fehlklick, TalkBack) und
        // Ueberspringen nebeneinander; auf der letzten Seite entfaellt Skip.
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (page > 0) {
                TextButton(onClick = { page-- }) {
                    Text(stringResource(R.string.onboarding_back))
                }
            }
            if (!last) {
                TextButton(onClick = onFinish) {
                    Text(stringResource(R.string.onboarding_skip))
                }
            }
        }
    }
}

private data class OnboardingPage(
    val iconRes: Int,
    val titleRes: Int,
    val descRes: Int,
)

private val ONBOARDING_PAGES =
    listOf(
        OnboardingPage(
            BrandIcons.NavMusic,
            R.string.onboarding_music_title,
            R.string.onboarding_music_desc,
        ),
        OnboardingPage(
            BrandIcons.NavTrain,
            R.string.onboarding_drop_title,
            R.string.onboarding_drop_desc,
        ),
        OnboardingPage(
            BrandIcons.NavHistory,
            R.string.onboarding_training_title,
            R.string.onboarding_training_desc,
        ),
    )
