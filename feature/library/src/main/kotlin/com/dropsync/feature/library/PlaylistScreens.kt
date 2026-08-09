package com.dropsync.feature.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dropsync.core.designsystem.icon.BrandIcons
import com.dropsync.core.model.PlaylistLabel
import com.dropsync.core.model.Song
import com.dropsync.domain.library.Playlist

/**
 * Playlist-Liste (Musik-Workout-Kopplung Phase 1): sichtbares Verwalten der
 * bereits vorhandenen Datenschicht. Anlegen ueber die Kopfzeile, je Zeile ein
 * Ueberlaufmenue zum Umbenennen und Loeschen; Tippen oeffnet die Detailansicht.
 */
@Composable
internal fun PlaylistList(
    playlists: List<Playlist>,
    contentPadding: PaddingValues,
    onOpen: (Long) -> Unit,
    onCreate: (String) -> Unit,
    onRename: (Long, String) -> Unit,
    onDelete: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showCreate by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<Playlist?>(null) }
    var deleteTarget by remember { mutableStateOf<Playlist?>(null) }

    Column(modifier = modifier.fillMaxSize()) {
        ListItem(
            headlineContent = { Text(stringResource(R.string.library_playlist_create)) },
            leadingContent = {
                Icon(painterResource(BrandIcons.PlaylistAdd), contentDescription = null)
            },
            modifier = Modifier.clickable { showCreate = true },
        )
        HorizontalDivider()
        if (playlists.isEmpty()) {
            Text(
                text = stringResource(R.string.library_playlist_empty),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.fillMaxWidth().padding(24.dp),
            )
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = contentPadding) {
                items(playlists, key = { it.id }) { playlist ->
                    PlaylistRow(
                        playlist = playlist,
                        onOpen = { onOpen(playlist.id) },
                        onRename = { renameTarget = playlist },
                        onDelete = { deleteTarget = playlist },
                    )
                }
            }
        }
    }

    if (showCreate) {
        PlaylistNameDialog(
            title = stringResource(R.string.library_playlist_new),
            initial = "",
            onConfirm = {
                onCreate(it)
                showCreate = false
            },
            onDismiss = { showCreate = false },
        )
    }
    renameTarget?.let { target ->
        PlaylistNameDialog(
            title = stringResource(R.string.library_playlist_rename),
            initial = target.name,
            onConfirm = {
                onRename(target.id, it)
                renameTarget = null
            },
            onDismiss = { renameTarget = null },
        )
    }
    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(stringResource(R.string.library_playlist_delete)) },
            text = { Text(stringResource(R.string.library_playlist_delete_confirm, target.name)) },
            confirmButton = {
                TextButton(onClick = {
                    onDelete(target.id)
                    deleteTarget = null
                }) { Text(stringResource(R.string.library_playlist_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) {
                    Text(stringResource(R.string.library_cancel))
                }
            },
        )
    }
}

@Composable
private fun PlaylistRow(
    playlist: Playlist,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    ListItem(
        headlineContent = { Text(playlist.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingContent = {
            Icon(painterResource(BrandIcons.Playlists), contentDescription = null)
        },
        supportingContent = {
            val labelText = playlist.label?.let { stringResource(it.labelRes()) }
            val count =
                pluralStringResource(
                    R.plurals.library_track_count,
                    playlist.trackCount,
                    playlist.trackCount,
                )
            Text(if (labelText == null) count else "$labelText \u00b7 $count")
        },
        trailingContent = {
            IconButton(onClick = { menuOpen = true }) {
                Icon(
                    painterResource(BrandIcons.More),
                    contentDescription = stringResource(R.string.library_more_actions),
                )
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.library_playlist_rename)) },
                    onClick = {
                        onRename()
                        menuOpen = false
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.library_playlist_delete)) },
                    onClick = {
                        onDelete()
                        menuOpen = false
                    },
                )
            }
        },
        modifier = Modifier.clickable { onOpen() },
    )
}

/**
 * Playlist-Detailansicht: Titelliste in gespeicherter Reihenfolge mit
 * Entfernen und Verschieben (hoch/runter). Bewusst Auf-/Ab-Tasten statt
 * Drag-Geste — robust, ohne neue Abhaengigkeit, ueber [onMove] an die
 * bestehende Repository-Reihenfolge gebunden.
 */
@Composable
internal fun PlaylistDetail(
    playlist: Playlist,
    songs: List<Song>,
    contentPadding: PaddingValues,
    onBack: () -> Unit,
    onPlay: (Int) -> Unit,
    onRemove: (Int) -> Unit,
    onMove: (Int, Int) -> Unit,
    onSetLabel: (PlaylistLabel?) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    painterResource(BrandIcons.Back),
                    contentDescription = stringResource(R.string.library_back),
                )
            }
            Text(
                text = playlist.name,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (songs.isNotEmpty()) {
                IconButton(onClick = { onPlay(0) }) {
                    Icon(
                        painterResource(BrandIcons.Play),
                        contentDescription = stringResource(R.string.library_playlist_play_all),
                    )
                }
            }
        }
        LabelChips(selected = playlist.label, onSelect = onSetLabel)
        if (songs.isEmpty()) {
            Text(
                text = stringResource(R.string.library_playlist_detail_empty),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.fillMaxWidth().padding(24.dp),
            )
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = contentPadding) {
                itemsIndexed(songs, key = { _, song -> song.mediaStoreId }) { index, song ->
                    ListItem(
                        headlineContent = {
                            Text(songTitle(song), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        },
                        supportingContent = {
                            Text(song.artist ?: stringResource(R.string.library_unknown_artist))
                        },
                        trailingContent = {
                            Row {
                                IconButton(
                                    onClick = { onMove(index, index - 1) },
                                    enabled = index > 0,
                                ) {
                                    Icon(
                                        Icons.Outlined.KeyboardArrowUp,
                                        contentDescription =
                                            stringResource(R.string.library_playlist_move_up),
                                    )
                                }
                                IconButton(
                                    onClick = { onMove(index, index + 1) },
                                    enabled = index < songs.lastIndex,
                                ) {
                                    Icon(
                                        Icons.Outlined.KeyboardArrowDown,
                                        contentDescription =
                                            stringResource(R.string.library_playlist_move_down),
                                    )
                                }
                                IconButton(onClick = { onRemove(index) }) {
                                    Icon(
                                        painterResource(BrandIcons.Delete),
                                        contentDescription =
                                            stringResource(R.string.library_playlist_remove_song),
                                    )
                                }
                            }
                        },
                        modifier = Modifier.clickable { onPlay(index) },
                    )
                }
            }
        }
    }
}

/**
 * Auswahl-Dialog "Zu Playlist hinzufuegen": vorhandene Playlisten oder eine
 * neue anlegen (und den Titel direkt aufnehmen).
 */
@Composable
internal fun AddToPlaylistDialog(
    playlists: List<Playlist>,
    onDismiss: () -> Unit,
    onPick: (Long) -> Unit,
    onCreateNew: (String) -> Unit,
) {
    var creating by remember { mutableStateOf(false) }
    if (creating) {
        PlaylistNameDialog(
            title = stringResource(R.string.library_playlist_new),
            initial = "",
            onConfirm = {
                onCreateNew(it)
                creating = false
            },
            onDismiss = { creating = false },
        )
        return
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.library_add_to_playlist)) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
            ) {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.library_playlist_new)) },
                    leadingContent = { Icon(painterResource(BrandIcons.PlaylistAdd), contentDescription = null) },
                    modifier = Modifier.clickable { creating = true },
                )
                playlists.forEach { playlist ->
                    ListItem(
                        headlineContent = {
                            Text(playlist.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        },
                        supportingContent = {
                            Text(
                                pluralStringResource(
                                    R.plurals.library_track_count,
                                    playlist.trackCount,
                                    playlist.trackCount,
                                ),
                            )
                        },
                        modifier = Modifier.clickable { onPick(playlist.id) },
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.library_cancel)) }
        },
    )
}

/** Auswahl des Workout-Labels (kein Label / Rest / Work) einer Playlist. */
@Composable
private fun LabelChips(
    selected: PlaylistLabel?,
    onSelect: (PlaylistLabel?) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilterChip(
            selected = selected == null,
            onClick = { onSelect(null) },
            label = { Text(stringResource(R.string.library_playlist_label_none)) },
        )
        FilterChip(
            selected = selected == PlaylistLabel.REST,
            onClick = { onSelect(PlaylistLabel.REST) },
            label = { Text(stringResource(R.string.library_playlist_label_rest)) },
        )
        FilterChip(
            selected = selected == PlaylistLabel.WORK,
            onClick = { onSelect(PlaylistLabel.WORK) },
            label = { Text(stringResource(R.string.library_playlist_label_work)) },
        )
    }
}

private fun PlaylistLabel.labelRes(): Int =
    when (this) {
        PlaylistLabel.REST -> R.string.library_playlist_label_rest
        PlaylistLabel.WORK -> R.string.library_playlist_label_work
    }

/** Gemeinsamer Namensdialog fuer Anlegen und Umbenennen. */
@Composable
private fun PlaylistNameDialog(
    title: String,
    initial: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                label = { Text(stringResource(R.string.library_playlist_name)) },
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name) },
                enabled = name.isNotBlank(),
            ) { Text(stringResource(R.string.library_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.library_cancel)) }
        },
    )
}
