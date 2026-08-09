package com.dropsync.feature.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dropsync.core.designsystem.component.CoverImage
import com.dropsync.core.designsystem.icon.BrandIcons

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
) {
    val state by viewModel.miniPlayer.collectAsStateWithLifecycle()
    if (!state.isVisible) return

    var showQueue by remember { mutableStateOf(false) }

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 3.dp,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            val progress =
                if (state.durationMs > 0) {
                    (state.positionMs.toFloat() / state.durationMs).coerceIn(0f, 1f)
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
                            .clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                ) {
                    Icon(
                        painterResource(BrandIcons.NavMusic),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(
                    modifier =
                        Modifier
                            .weight(1f)
                            .clickable(onClick = onOpenNowPlaying),
                ) {
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
                    if (state.isPlaying) {
                        Icon(
                            painterResource(BrandIcons.Pause),
                            contentDescription = stringResource(R.string.player_pause),
                        )
                    } else {
                        Icon(
                            painterResource(BrandIcons.Play),
                            contentDescription = stringResource(R.string.player_play),
                        )
                    }
                }
                IconButton(
                    onClick = viewModel::skipToNext,
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(
                        painterResource(BrandIcons.SkipNext),
                        contentDescription = stringResource(R.string.player_next),
                    )
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
            onRemove = viewModel::removeQueueItem,
        )
    }
}
