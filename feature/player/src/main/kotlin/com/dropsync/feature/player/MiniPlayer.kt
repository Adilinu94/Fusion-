package com.dropsync.feature.player

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dropsync.core.designsystem.component.CoverImage
import com.dropsync.core.designsystem.icon.BrandIcons
import com.dropsync.core.designsystem.theme.Spacing
import com.dropsync.core.designsystem.theme.rememberReducedMotion
import kotlinx.coroutines.launch

/**
 * Aktiver Mini-Player: bleibt als klar beschriebene, bedienbare
 * Komponente in der Shell sichtbar (Schritt 12.2). Jeder Icon-Button hat
 * eine lokalisierte Inhaltsbeschreibung (12.4). Tap auf die Titelzeile
 * oeffnet den Now-Playing-Screen (Marker/Waveform-Plan Phase 1).
 */
@Composable
fun MiniPlayer(
    modifier: Modifier = Modifier,
    onOpenNowPlaying: () -> Unit = {},
    viewModel: PlayerViewModel = hiltViewModel(),
    // B4: App-weiter Snackbar-Host (Undo) — die Shell blendet ihn ein.
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
) {
    val state by viewModel.miniPlayer.collectAsStateWithLifecycle()
    // C2: Countdown des Plans fuer das Badge (derselbe getickte Zustand wie
    // die Statuszeile — keine zweite Zeitrechnung).
    val dropStatus by viewModel.dropStatus.collectAsStateWithLifecycle()
    if (!state.isVisible) return

    var showQueue by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val queueRemovedText = stringResource(R.string.player_queue_removed)
    val undoText = stringResource(R.string.player_undo)

    Surface(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp),
        // C1: System-Radius statt Literal.
        shape = RoundedCornerShape(Spacing.radiusCard),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 0.dp,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            MiniPlayerProgressBar(positionMs = state.positionMs, durationMs = state.durationMs)
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CoverImage(
                    contentUri = state.contentUri,
                    contentDescription = null,
                    modifier =
                        Modifier
                            .size(44.dp)
                            .clip(RoundedCornerShape(Spacing.radiusSmall))
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                ) {
                    Icon(
                        painterResource(BrandIcons.NavMusic),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(12.dp))
                // A5: Titel als Button mit 48-dp-Ziel — TalkBack meldet Titel,
                // Kuenstler und Aktion in einer Ansage statt getrennt.
                Column(
                    modifier =
                        Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp)
                            .clickable(
                                role = Role.Button,
                                onClickLabel = stringResource(R.string.miniplayer_open_label),
                                onClick = onOpenNowPlaying,
                            ),
                    verticalArrangement = Arrangement.Center,
                ) {
                    // P1-10: DropSync-Badge vor dem Titel; Text statt Technik,
                    // Farbe nie allein (A.4). C2: mit Countdown und als
                    // Details-Einstieg zum Plan (Tap oeffnet den Player).
                    state.dropSyncBadge?.let { badge ->
                        DropSyncBadgeChip(
                            badge = badge,
                            countdownMs = (dropStatus as? DropStatusLine.Ready)?.remainingMs,
                            // C16 (5.22): Die Kette steht auch im Mini-Player.
                            chain = (dropStatus as? DropStatusLine.Ready)?.chain.orEmpty(),
                            onClick = onOpenNowPlaying,
                        )
                    }
                    Text(
                        text = state.title,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    state.artist?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                IconButton(
                    onClick = viewModel::togglePlayPause,
                    // Grosse Touch-Ziele (12.5).
                    modifier = Modifier.size(48.dp),
                ) {
                    MiniPlayerPlayPauseIcon(isPlaying = state.isPlaying)
                }
                IconButton(
                    onClick = { showQueue = true },
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(
                        painterResource(BrandIcons.Queue),
                        contentDescription = stringResource(R.string.player_queue_open),
                    )
                }
            }
        }
    }

    if (showQueue) {
        val queueState by viewModel.queue.collectAsStateWithLifecycle()
        QueueSheet(
            state = queueState,
            onDismiss = { showQueue = false },
            onPlay = viewModel::playQueueItem,
            onMove = viewModel::moveQueueItem,
            // B4: Entfernen mit Undo (gleiche Logik wie im Now-Playing-Sheet).
            onRemove = { index ->
                viewModel.removeQueueItem(index)
                if (viewModel.hasQueueUndo()) {
                    scope.launch {
                        val result =
                            snackbarHostState.showSnackbar(
                                message = queueRemovedText,
                                actionLabel = undoText,
                                withDismissAction = true,
                            )
                        if (result == SnackbarResult.ActionPerformed) {
                            viewModel.undoRemoveQueueItem()
                        }
                    }
                }
            },
        )
    }
}

/**
 * P1-10: kleines DropSync-Badge im Mini-Player. Zeigt Zustand als Text
 * ("DROP BEREIT" / "DROP BEST EFFORT" / "DROP AUS"), nie Technik; die
 * Farbe unterstreicht nur (Design 4.5, A.4). C2: Bei scharfem Plan mit
 * Countdown ("DROP BEREIT · 1:27") und als Details-Einstieg antippbar.
 */
@Composable
internal fun DropSyncBadgeChip(
    badge: DropSyncBadge,
    countdownMs: Long?,
    chain: List<String>,
    onClick: () -> Unit,
) {
    val (labelRes, container) =
        when (badge) {
            DropSyncBadge.READY -> {
                R.string.miniplayer_dropsync_ready to MaterialTheme.colorScheme.primaryContainer
            }

            DropSyncBadge.BEST_EFFORT -> {
                R.string.miniplayer_dropsync_best_effort to MaterialTheme.colorScheme.tertiaryContainer
            }

            DropSyncBadge.OVERRIDDEN -> {
                R.string.miniplayer_dropsync_overridden to MaterialTheme.colorScheme.surfaceVariant
            }

            DropSyncBadge.FAILED -> {
                R.string.miniplayer_dropsync_failed to MaterialTheme.colorScheme.errorContainer
            }
        }
    // C16: Bei einer Kette nennt das Badge die Uebergaenge ("A -> B -> Drop
    // in 1:27"); sonst den Zustand, bei scharfem Plan mit Countdown.
    val label =
        when {
            badge == DropSyncBadge.READY && chain.size > 1 && countdownMs != null -> {
                stringResource(
                    R.string.miniplayer_dropsync_chain,
                    chain.joinToString(CHAIN_ARROW),
                    formatClockMs(countdownMs),
                )
            }

            badge == DropSyncBadge.READY && countdownMs != null -> {
                stringResource(R.string.miniplayer_dropsync_ready_countdown, formatClockMs(countdownMs))
            }

            else -> {
                stringResource(labelRes)
            }
        }
    Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurface,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier =
            Modifier
                .padding(bottom = 2.dp)
                .clip(RoundedCornerShape(Spacing.radiusSmall))
                .clickable(onClick = onClick)
                .background(container)
                .padding(horizontal = 6.dp, vertical = 1.dp),
    )
}

/**
 * Duenne Fortschrittsleiste am oberen Rand des Mini-Players. Ausgelagert,
 * damit [MiniPlayer] unter der Detekt-Grenze fuer LongMethod bleibt.
 */
@Composable
private fun MiniPlayerProgressBar(
    positionMs: Long,
    durationMs: Long,
) {
    val progress =
        if (durationMs > 0) {
            (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
        } else {
            0f
        }
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(2.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        if (progress > 0f) {
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth(progress)
                        .fillMaxHeight()
                        .background(MaterialTheme.colorScheme.primary),
            )
        }
    }
}

/**
 * Play/Pause-Icon des Mini-Players. Expressive-Ziel im Kleinen: Der
 * Icon-Zustandswechsel pulst ueber eine Feder-Skalierung statt hart zu
 * springen.
 */
@Composable
private fun MiniPlayerPlayPauseIcon(isPlaying: Boolean) {
    val reducedMotion = rememberReducedMotion()
    val iconScale by animateFloatAsState(
        targetValue = if (isPlaying) 1.15f else 1f,
        animationSpec =
            if (reducedMotion) {
                snap()
            } else {
                spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessMedium,
                )
            },
        label = "mini_player_play_scale",
    )
    val scaleModifier =
        Modifier.graphicsLayer {
            scaleX = iconScale
            scaleY = iconScale
        }
    if (isPlaying) {
        Icon(
            painterResource(BrandIcons.Pause),
            contentDescription = stringResource(R.string.player_pause),
            modifier = scaleModifier,
        )
    } else {
        Icon(
            painterResource(BrandIcons.Play),
            contentDescription = stringResource(R.string.player_play),
            modifier = scaleModifier,
        )
    }
}
