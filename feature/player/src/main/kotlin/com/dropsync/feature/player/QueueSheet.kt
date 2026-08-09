package com.dropsync.feature.player

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dropsync.core.designsystem.icon.BrandIcons
import com.dropsync.domain.playback.QueueItem

/**
 * Queue-Editor als modales Bottom-Sheet (Plan Phase 6, Punkt 3): jeder
 * Eintrag laesst sich antippen (dort weiterspielen), nach oben/unten
 * verschieben und entfernen. Alle Aktionen laufen ueber
 * [PlaybackRepository] gegen denselben MediaController; die Persistenz
 * uebernimmt der bestehende PlayerStateStore.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun QueueSheet(
    state: QueueUiState,
    onDismiss: () -> Unit,
    onPlay: (Int) -> Unit,
    onMove: (Int, Int) -> Unit,
    onRemove: (Int) -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text(
            text = stringResource(R.string.player_queue_title),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
        )
        if (state.items.isEmpty()) {
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(stringResource(R.string.player_queue_empty))
            }
        } else {
            LazyColumn(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 480.dp),
            ) {
                itemsIndexed(state.items, key = { _, item -> item.mediaId }) { index, item ->
                    QueueRow(
                        item = item,
                        isCurrent = index == state.currentIndex,
                        canMoveUp = index > 0,
                        canMoveDown = index < state.items.lastIndex,
                        onPlay = { onPlay(index) },
                        onMoveUp = { onMove(index, index - 1) },
                        onMoveDown = { onMove(index, index + 1) },
                        onRemove = { onRemove(index) },
                    )
                }
            }
        }
    }
}

@Composable
private fun QueueRow(
    item: QueueItem,
    isCurrent: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onPlay: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onPlay)
                .padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier =
                Modifier
                    .weight(1f)
                    .padding(vertical = 8.dp),
        ) {
            Text(
                text = item.title,
                style = MaterialTheme.typography.bodyLarge,
                color =
                    if (isCurrent) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val subtitle =
                if (isCurrent) stringResource(R.string.player_queue_now_playing) else item.artist
            subtitle?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        // 48-dp-Touch-Ziele (Schritt 12.5); Pfeile sind an den Raendern deaktiviert.
        IconButton(onClick = onMoveUp, enabled = canMoveUp) {
            Icon(
                Icons.Outlined.KeyboardArrowUp,
                contentDescription = stringResource(R.string.player_queue_move_up),
            )
        }
        IconButton(onClick = onMoveDown, enabled = canMoveDown) {
            Icon(
                Icons.Outlined.KeyboardArrowDown,
                contentDescription = stringResource(R.string.player_queue_move_down),
            )
        }
        IconButton(onClick = onRemove) {
            Icon(
                painterResource(BrandIcons.Delete),
                contentDescription = stringResource(R.string.player_queue_remove),
            )
        }
    }
}
