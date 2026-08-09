package com.dropsync.feature.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Shuffle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.dropsync.core.designsystem.icon.BrandIcons
import com.dropsync.domain.library.SongSort

/**
 * Kopfzeile eines Kategorie-Screens (Poweramp): grosser, getoenter Icon-Kreis,
 * Kategoriename und optionale Meta-Zeile ("n Titel | Gesamtdauer"). Der
 * Zurueck-Link steht darueber.
 */
@Composable
internal fun CategoryHeader(
    iconRes: Int,
    title: String,
    subtitle: String?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 4.dp, top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    painterResource(BrandIcons.Back),
                    contentDescription = stringResource(R.string.library_back),
                )
            }
            Text(
                text = stringResource(R.string.library_title),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier =
                    Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painterResource(iconRes),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(28.dp),
                )
            }
            Spacer(Modifier.width(16.dp))
            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/**
 * Aktionsleiste eines Kategorie-Screens (Poweramp): Zufallswiedergabe, Play,
 * Suche (blendet ein Suchfeld ein), "Auswaehlen" und rechts das Drei-Punkte-
 * Ueberlaufmenue (Poweramp "More Options"), das die Listen-Optionen oeffnet.
 * Suche und Auswahl sind optional (nicht jede Kategorie unterstuetzt sie).
 */
@Composable
internal fun LibraryToolbar(
    onShuffle: (() -> Unit)?,
    onPlayAll: (() -> Unit)?,
    onToggleSearch: (() -> Unit)?,
    onSelect: (() -> Unit)?,
    onOpenListOptions: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (onShuffle != null) {
            IconButton(onClick = onShuffle, enabled = enabled) {
                Icon(
                    Icons.Outlined.Shuffle,
                    contentDescription = stringResource(R.string.library_shuffle),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
        if (onPlayAll != null) {
            IconButton(onClick = onPlayAll, enabled = enabled) {
                Icon(
                    painterResource(BrandIcons.Play),
                    contentDescription = stringResource(R.string.library_play_all),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
        if (onToggleSearch != null) {
            IconButton(onClick = onToggleSearch) {
                Icon(
                    painterResource(BrandIcons.Search),
                    contentDescription = stringResource(R.string.library_search_hint),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
        Spacer(Modifier.weight(1f))
        if (onSelect != null) {
            TextButton(onClick = onSelect, enabled = enabled) {
                Text(stringResource(R.string.library_select))
            }
        }
        IconButton(onClick = onOpenListOptions) {
            Icon(
                painterResource(BrandIcons.More),
                contentDescription = stringResource(R.string.library_list_options),
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/** Inline-Suchfeld unter der Toolbar (Poweramp: Lupe blendet Suche ein). */
@Composable
internal fun InlineSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        singleLine = true,
        leadingIcon = { Icon(painterResource(BrandIcons.Search), contentDescription = null) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(
                        painterResource(BrandIcons.Close),
                        contentDescription = stringResource(R.string.library_search_clear),
                    )
                }
            }
        },
        placeholder = { Text(stringResource(R.string.library_search_hint)) },
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp),
    )
}

/**
 * Listen-Optionen (Poweramp "List Options"): Sortierung (Radio) mit
 * Auf-/Absteigend und Ansichtsmodus. [sortOptions] leer = nur Ansicht (fuer
 * Sammlungen ohne Titel-Sortierung).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ListOptionsSheet(
    categoryTitle: String,
    config: CategoryListConfig,
    sortOptions: List<SongSort>,
    viewModes: List<LibraryViewMode>,
    onSort: (SongSort) -> Unit,
    onDescending: (Boolean) -> Unit,
    onViewMode: (LibraryViewMode) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 24.dp),
        ) {
            Text(
                text = stringResource(R.string.library_list_options_title, categoryTitle),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(vertical = 8.dp),
            )
            if (sortOptions.isNotEmpty()) {
                Text(
                    text = stringResource(R.string.library_sort_section),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                )
                sortOptions.forEach { option ->
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .selectable(
                                    selected = option == config.sort,
                                    onClick = { onSort(option) },
                                ).heightIn(min = 48.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = option == config.sort, onClick = { onSort(option) })
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(option.optionLabelRes()))
                    }
                }
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .clickable { onDescending(!config.descending) }
                            .heightIn(min = 48.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = config.descending, onCheckedChange = { onDescending(it) })
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.library_sort_desc))
                }
            }
            Text(
                text = stringResource(R.string.library_view_section),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
            )
            viewModes.forEach { mode ->
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .selectable(selected = mode == config.viewMode, onClick = { onViewMode(mode) })
                            .heightIn(min = 48.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = mode == config.viewMode, onClick = { onViewMode(mode) })
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(mode.labelRes()))
                }
            }
            Spacer(Modifier.width(8.dp))
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.align(Alignment.End),
            ) {
                Text(stringResource(R.string.library_close))
            }
        }
    }
}

/**
 * Untere Aktionsleiste im Auswahlmodus (Poweramp Selection menu): Kopf mit
 * "Alle" und Zaehler, darunter Playlist / Warteschlange / Als Naechstes /
 * Info / Loeschen.
 */
@Composable
internal fun SelectionBar(
    selectedCount: Int,
    totalCount: Int,
    onSelectAll: () -> Unit,
    onClose: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onAddToQueue: () -> Unit,
    onPlayNext: () -> Unit,
    onInfo: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    bottomInset: Dp = 0.dp,
) {
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .padding(bottom = bottomInset),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onSelectAll) { Text(stringResource(R.string.library_selection_all)) }
            Spacer(Modifier.weight(1f))
            Text(
                text = stringResource(R.string.library_selection_count, selectedCount, totalCount),
                style = MaterialTheme.typography.labelLarge,
            )
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onClose) {
                Icon(painterResource(BrandIcons.Close), contentDescription = stringResource(R.string.library_close))
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            SelectionAction(BrandIcons.PlaylistAdd, R.string.library_action_playlist, onAddToPlaylist)
            SelectionAction(BrandIcons.Queue, R.string.library_action_queue, onAddToQueue)
            SelectionAction(BrandIcons.PlayNext, R.string.library_action_play_next, onPlayNext)
            SelectionAction(BrandIcons.Info, R.string.library_action_info, onInfo)
            SelectionAction(BrandIcons.Delete, R.string.library_action_delete, onDelete)
        }
    }
}

@Composable
private fun RowScope.SelectionAction(
    iconRes: Int,
    labelRes: Int,
    onClick: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier =
            Modifier
                .weight(1f)
                .clip(MaterialTheme.shapes.small)
                .clickable(onClick = onClick)
                .padding(horizontal = 2.dp, vertical = 4.dp),
    ) {
        Icon(
            painterResource(iconRes),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = stringResource(labelRes),
            style = MaterialTheme.typography.labelSmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}

/** Info-Dialog fuer einen einzelnen Titel (Poweramp "Info/Tags", reduziert). */
@Composable
internal fun TrackInfoDialog(
    title: String,
    path: String,
    duration: String,
    size: String,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.library_info_title)) },
        text = {
            Column {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.width(8.dp))
                InfoRow(stringResource(R.string.library_info_path), path)
                InfoRow(stringResource(R.string.library_info_duration), duration)
                InfoRow(stringResource(R.string.library_info_size), size)
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.library_close)) }
        },
    )
}

@Composable
private fun InfoRow(
    label: String,
    value: String,
) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(
            text = "$label: ",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(text = value, style = MaterialTheme.typography.bodyMedium)
    }
}

internal fun SongSort.optionLabelRes(): Int =
    when (this) {
        SongSort.TITLE -> R.string.library_opt_sort_title
        SongSort.FILENAME -> R.string.library_opt_sort_filename
        SongSort.PATH -> R.string.library_opt_sort_path
        SongSort.ARTIST -> R.string.library_opt_sort_artist
        SongSort.ALBUM -> R.string.library_opt_sort_album
        SongSort.DURATION -> R.string.library_opt_sort_duration
        SongSort.DATE_ADDED -> R.string.library_opt_sort_date_added
        SongSort.LAST_PLAYED -> R.string.library_opt_sort_last_played
        SongSort.PLAY_COUNT -> R.string.library_opt_sort_play_count
    }

internal fun LibraryViewMode.labelRes(): Int =
    when (this) {
        LibraryViewMode.LIST -> R.string.library_view_mode_list
        LibraryViewMode.LIST_COMPACT -> R.string.library_view_mode_list_compact
        LibraryViewMode.GRID_SMALL -> R.string.library_view_mode_grid_small
        LibraryViewMode.GRID -> R.string.library_view_mode_grid
    }
