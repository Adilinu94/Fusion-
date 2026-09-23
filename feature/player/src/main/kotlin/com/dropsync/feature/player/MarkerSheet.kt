package com.dropsync.feature.player

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dropsync.core.model.MarkerSource
import com.dropsync.core.model.SongMarker
import java.util.Locale

/**
 * P2-21 (UI-Handbuch 14.4/14.5): Marker-Tap-Sheet im Now-Playing — der Ort,
 * an dem der Nutzer den Drop hoert und festlegt.
 *
 * - Anhoeren springt zur Markerposition (Seek), die Feinjustierung
 *   verschiebt in 10/100-ms-Schritten und behaelt die Originalposition
 *   ([MarkerEditState]); "Zurueck auf Original" erscheint nur bei
 *   Abweichung.
 * - "Als DropSync-Ziel waehlen" setzt das bevorzugte Ziel des Songs; ein
 *   unbestaetigter Vorschlag wird dabei bestaetigt.
 * - Loeschen laeuft ueber den Aufrufer (Undo-Snackbar der Shell).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MarkerSheet(
    marker: SongMarker,
    isSuggestion: Boolean,
    isTarget: Boolean,
    editState: MarkerEditState,
    onDismiss: () -> Unit,
    onListen: () -> Unit,
    onAdjust: (Long) -> Unit,
    onRevert: () -> Unit,
    onChooseTarget: () -> Unit,
    onClearTarget: () -> Unit,
    onConfirm: () -> Unit,
    onRename: (String) -> Unit,
    onDelete: () -> Unit,
    allMarkers: List<SongMarker> = emptyList(),
    onListenMarker: (SongMarker) -> Unit = {},
) {
    var renaming by remember(marker.id) { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        MarkerSheetContent(
            marker = marker,
            isSuggestion = isSuggestion,
            isTarget = isTarget,
            editState = editState,
            onListen = onListen,
            onAdjust = onAdjust,
            onRevert = onRevert,
            onChooseTarget = onChooseTarget,
            onClearTarget = onClearTarget,
            onConfirm = onConfirm,
            onRenameRequest = { renaming = true },
            onDelete = onDelete,
            allMarkers = allMarkers,
            onListenMarker = onListenMarker,
        )
    }

    if (renaming) {
        RenameMarkerDialog(
            initialLabel = marker.label,
            onConfirm = { label ->
                renaming = false
                onRename(label)
            },
            onDismiss = { renaming = false },
        )
    }
}

/**
 * D4 (Welle 2): Der Inhalt des Marker-Sheets als eigene Composable — ohne
 * ModalBottomSheet-Fenster testbar. Die Fenster-Chrome ist nicht Teil der
 * Regeln; Feinjustierung, Zielwahl, Umbenennen und Loeschen schon.
 */
@Composable
internal fun MarkerSheetContent(
    marker: SongMarker,
    isSuggestion: Boolean,
    isTarget: Boolean,
    editState: MarkerEditState,
    onListen: () -> Unit,
    onAdjust: (Long) -> Unit,
    onRevert: () -> Unit,
    onChooseTarget: () -> Unit,
    onClearTarget: () -> Unit,
    onConfirm: () -> Unit,
    onRenameRequest: () -> Unit,
    onDelete: () -> Unit,
    allMarkers: List<SongMarker>,
    onListenMarker: (SongMarker) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp),
    ) {
        Text(
            text = marker.label,
            style = MaterialTheme.typography.headlineSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = formatMarkerTimeMs(editState.editedPositionMs),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MarkerStatusChip(text = statusLabel(marker, isSuggestion))
            if (isTarget) {
                MarkerStatusChip(
                    text = stringResource(R.string.marker_sheet_target_chip),
                    highlighted = true,
                )
            }
        }

        Spacer(Modifier.height(16.dp))
        TextButton(
            onClick = onListen,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.marker_sheet_listen))
        }

        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.marker_sheet_fine_tune),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AdjustButton(label = "-100 ms", modifier = Modifier.weight(1f)) { onAdjust(-100L) }
            AdjustButton(label = "-10 ms", modifier = Modifier.weight(1f)) { onAdjust(-10L) }
            AdjustButton(label = "+10 ms", modifier = Modifier.weight(1f)) { onAdjust(10L) }
            AdjustButton(label = "+100 ms", modifier = Modifier.weight(1f)) { onAdjust(100L) }
        }
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = formatMarkerTimeMs(editState.editedPositionMs),
                style = MaterialTheme.typography.titleMedium,
            )
            if (editState.isDirty) {
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onRevert) {
                    Text(stringResource(R.string.marker_sheet_revert))
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        Button(
            onClick = if (isTarget) onClearTarget else onChooseTarget,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                stringResource(
                    if (isTarget) R.string.marker_sheet_clear_target else R.string.marker_sheet_choose_target,
                ),
            )
        }
        if (isSuggestion) {
            Spacer(Modifier.height(8.dp))
            TextButton(
                onClick = onConfirm,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.marker_sheet_confirm))
            }
        }
        Spacer(Modifier.height(8.dp))
        TextButton(
            onClick = onRenameRequest,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.marker_sheet_rename))
        }
        TextButton(
            onClick = onDelete,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = stringResource(R.string.marker_sheet_delete),
                color = MaterialTheme.colorScheme.error,
            )
        }

        // C9 (P-8, UI-Handbuch 19.4): Marker-Liste als alternative
        // Textansicht — der Detailmodus des Sheets listet ALLE Marker
        // des Songs; jede Zeile springt mit Vorlauf dorthin.
        if (allMarkers.size > 1) {
            Spacer(Modifier.height(16.dp))
            MarkerTextList(markers = allMarkers, onListenMarker = onListenMarker)
        }
    }
}

/**
 * C9 (P-8): Textliste aller Marker als alternative Ansicht (TalkBack und
 * Detailmodus). Jede Zeile springt mit Vorlauf zum Marker.
 */
@Composable
internal fun MarkerTextList(
    markers: List<SongMarker>,
    onListenMarker: (SongMarker) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.marker_sheet_all_markers, markers.size),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = MARKER_LIST_MAX_HEIGHT)) {
            items(markers, key = { it.id }) { entry ->
                ListItem(
                    headlineContent = {
                        Text(entry.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    },
                    supportingContent = { Text(formatMarkerTimeMs(entry.positionMs)) },
                    modifier =
                        Modifier
                            .clickable { onListenMarker(entry) }
                            .heightIn(min = 48.dp),
                )
            }
        }
    }
}

@Composable
private fun MarkerStatusChip(
    text: String,
    highlighted: Boolean = false,
) {
    Surface(
        shape = RoundedCornerShape(50),
        color =
            if (highlighted) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.secondaryContainer
            },
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color =
                if (highlighted) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSecondaryContainer
                },
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun AdjustButton(
    label: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 4.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1)
    }
}

/** Statuszeile des Sheets: Herkunft und Bestaetigungszustand des Markers. */
@Composable
private fun statusLabel(
    marker: SongMarker,
    isSuggestion: Boolean,
): String =
    stringResource(
        when {
            isSuggestion -> R.string.marker_sheet_status_suggestion
            marker.source == MarkerSource.AUTO_DETECTED -> R.string.marker_sheet_status_confirmed_auto
            marker.source == MarkerSource.MANUAL -> R.string.marker_sheet_status_manual
            else -> R.string.marker_sheet_status_imported
        },
    )

@Composable
private fun RenameMarkerDialog(
    initialLabel: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var label by remember { mutableStateOf(initialLabel) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.marker_sheet_rename_title)) },
        text = {
            OutlinedTextField(
                value = label,
                onValueChange = { label = it },
                label = { Text(stringResource(R.string.marker_sheet_rename_label)) },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(label) },
                enabled = label.isNotBlank(),
            ) {
                Text(stringResource(R.string.marker_sheet_rename_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.marker_sheet_rename_cancel)) }
        },
    )
}

/** C9: maximale Hoehe der Marker-Textliste im Sheet. */
private val MARKER_LIST_MAX_HEIGHT = 180.dp

/** Markerzeit mit Millisekunden ("01:18.420", UI-Handbuch 14.4). */
internal fun formatMarkerTimeMs(ms: Long): String {
    val total = ms.coerceAtLeast(0L)
    val hours = total / 3_600_000
    val minutes = (total % 3_600_000) / 60_000
    val seconds = (total % 60_000) / 1000
    val millis = total % 1000
    return if (hours > 0) {
        String.format(Locale.ROOT, "%d:%02d:%02d.%03d", hours, minutes, seconds, millis)
    } else {
        String.format(Locale.ROOT, "%02d:%02d.%03d", minutes, seconds, millis)
    }
}
