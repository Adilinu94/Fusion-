package com.dropsync.feature.library

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dropsync.core.designsystem.component.CoverImage
import com.dropsync.core.designsystem.icon.BrandIcons
import com.dropsync.core.model.Song
import kotlinx.coroutines.launch

/** Anzeigetitel eines Songs (Titel-Tag, sonst Dateiname). */
internal fun songTitle(song: Song): String = song.title ?: song.displayName

/**
 * Titelliste im Poweramp-artigen Stil (Umbau): Cover, Titel, Interpret/Meta,
 * Favoriten-Toggle, Ueberlaufmenue. Unterstuetzt einen Auswahlmodus
 * (Langdruck -> Checkbox-Overlay) und einen kompakten Zeilenhoehen-Modus.
 * Der optionale A-Z-Schnellscroller springt zum ersten passenden Titel.
 */
@Composable
internal fun SongColumn(
    songs: List<Song>,
    favoriteIds: Set<Long>,
    contentPadding: PaddingValues,
    onPlay: (Int) -> Unit,
    onToggleFavorite: (Long) -> Unit,
    onPlayNext: (Song) -> Unit,
    onAddToQueue: (Song) -> Unit,
    onDetectDrops: (Song) -> Unit,
    onAddToPlaylist: (Song) -> Unit = {},
    modifier: Modifier = Modifier,
    showFastScroller: Boolean = true,
    compact: Boolean = false,
    showTrailingActions: Boolean = true,
    selectionActive: Boolean = false,
    selectedIds: Set<Long> = emptySet(),
    onLongPress: (Song) -> Unit = {},
    onToggleSelect: (Song) -> Unit = {},
) {
    if (songs.isEmpty()) {
        EmptyHint(contentPadding = contentPadding, modifier = modifier)
        return
    }
    val listState = rememberLazyListState()
    Row(modifier = modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f),
            contentPadding = contentPadding,
        ) {
            itemsIndexed(songs, key = { _, song -> song.mediaStoreId }) { index, song ->
                SongRow(
                    song = song,
                    isFavorite = song.mediaStoreId in favoriteIds,
                    compact = compact,
                    showTrailingActions = showTrailingActions,
                    selectionActive = selectionActive,
                    isSelected = song.mediaStoreId in selectedIds,
                    onPlay = { onPlay(index) },
                    onToggleFavorite = { onToggleFavorite(song.mediaStoreId) },
                    onPlayNext = { onPlayNext(song) },
                    onAddToQueue = { onAddToQueue(song) },
                    onDetectDrops = { onDetectDrops(song) },
                    onAddToPlaylist = { onAddToPlaylist(song) },
                    onLongPress = { onLongPress(song) },
                    onToggleSelect = { onToggleSelect(song) },
                )
            }
        }
        if (showFastScroller && !selectionActive && songs.size >= FAST_SCROLLER_MIN_ITEMS) {
            AlphabetScroller(songs = songs, listState = listState)
        }
    }
}

/** Titelraster (Poweramp "Grid"): Cover-Kachel mit Titel/Interpret darunter. */
@Composable
internal fun SongGrid(
    songs: List<Song>,
    contentPadding: PaddingValues,
    columns: Int,
    onPlay: (Int) -> Unit,
    modifier: Modifier = Modifier,
    selectionActive: Boolean = false,
    selectedIds: Set<Long> = emptySet(),
    onLongPress: (Song) -> Unit = {},
    onToggleSelect: (Song) -> Unit = {},
) {
    if (songs.isEmpty()) {
        EmptyHint(contentPadding = contentPadding, modifier = modifier)
        return
    }
    LazyVerticalGrid(
        columns = GridCells.Fixed(columns),
        modifier = modifier.fillMaxSize(),
        contentPadding = contentPadding,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        itemsIndexed(songs, key = { _, song -> song.mediaStoreId }) { index, song ->
            CoverTile(
                title = songTitle(song),
                subtitle = song.artist,
                contentUri = song.contentUri,
                selectionActive = selectionActive,
                isSelected = song.mediaStoreId in selectedIds,
                onClick = { if (selectionActive) onToggleSelect(song) else onPlay(index) },
                onLongClick = { onLongPress(song) },
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SongRow(
    song: Song,
    isFavorite: Boolean,
    compact: Boolean,
    showTrailingActions: Boolean,
    selectionActive: Boolean,
    isSelected: Boolean,
    onPlay: () -> Unit,
    onToggleFavorite: () -> Unit,
    onPlayNext: () -> Unit,
    onAddToQueue: () -> Unit,
    onDetectDrops: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onLongPress: () -> Unit,
    onToggleSelect: () -> Unit,
) {
    val playLabel = stringResource(R.string.library_play_song, songTitle(song))
    val favLabel =
        stringResource(if (isFavorite) R.string.library_unfavorite else R.string.library_favorite)
    var menuOpen by remember { mutableStateOf(false) }
    val meta =
        buildString {
            append(song.artist ?: stringResource(R.string.library_unknown_artist))
            append("  |  ")
            append(formatDuration(song.durationMs))
            songFormat(song)?.let {
                append("  |  ")
                append(it)
            }
        }
    // Poweramp "Alle Titel": groesseres Cover, Titel bis fast an den Rand.
    val coverSize = if (compact) 44.dp else 60.dp
    val rowPadding = if (compact) 4.dp else 8.dp
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClickLabel = playLabel,
                    onClick = { if (selectionActive) onToggleSelect() else onPlay() },
                    onLongClick = { if (selectionActive) onToggleSelect() else onLongPress() },
                ).padding(horizontal = 16.dp, vertical = rowPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(contentAlignment = Alignment.Center) {
            CoverImage(
                contentUri = song.contentUri,
                contentDescription = null,
                modifier =
                    Modifier
                        .size(coverSize)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
            ) {
                Icon(
                    painterResource(BrandIcons.NavMusic),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (selectionActive) {
                SelectionBadge(isSelected = isSelected)
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = songTitle(song),
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = meta,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (!selectionActive && showTrailingActions) {
            IconButton(onClick = onToggleFavorite) {
                val favTint =
                    if (isFavorite) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                Icon(
                    painter =
                        painterResource(
                            if (isFavorite) BrandIcons.FavoriteFilled else BrandIcons.FavoriteOutline,
                        ),
                    contentDescription = favLabel,
                    tint = favTint,
                )
            }
            IconButton(onClick = { menuOpen = true }) {
                Icon(
                    painterResource(BrandIcons.More),
                    contentDescription = stringResource(R.string.library_more_actions),
                )
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.library_play_next)) },
                    onClick = {
                        onPlayNext()
                        menuOpen = false
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.library_add_to_queue)) },
                    onClick = {
                        onAddToQueue()
                        menuOpen = false
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.library_detect_drops)) },
                    onClick = {
                        onDetectDrops()
                        menuOpen = false
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.library_add_to_playlist)) },
                    onClick = {
                        onAddToPlaylist()
                        menuOpen = false
                    },
                )
            }
        }
    }
}

/** Lime-Haekchen bzw. leerer Ring auf dem Cover im Auswahlmodus. */
@Composable
private fun SelectionBadge(isSelected: Boolean) {
    Box(
        modifier =
            Modifier
                .size(28.dp)
                .clip(RoundedCornerShape(50))
                .background(
                    if (isSelected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.surface.copy(alpha = 0.7f)
                    },
                ),
        contentAlignment = Alignment.Center,
    ) {
        if (isSelected) {
            Icon(
                painterResource(BrandIcons.SetComplete),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/** Vertikaler A–Z-Index; tippen springt zum ersten passenden Titel. */
@Composable
private fun AlphabetScroller(
    songs: List<Song>,
    listState: LazyListState,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val letterIndex =
        remember(songs) {
            val map = linkedMapOf<Char, Int>()
            songs.forEachIndexed { index, song ->
                val first = songTitle(song).trim().firstOrNull()?.uppercaseChar() ?: '#'
                val bucket = if (first.isLetter()) first else '#'
                map.putIfAbsent(bucket, index)
            }
            map
        }
    Column(
        modifier =
            modifier
                .fillMaxHeight()
                .width(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        for ((letter, index) in letterIndex) {
            Text(
                text = letter.toString(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier =
                    Modifier
                        .clickable { scope.launch { listState.scrollToItem(index) } }
                        .padding(vertical = 1.dp),
            )
        }
    }
}

/** Zeile einer Sammlung (Album/Kuenstler/Genre/Ordner) mit Titelzahl. */
@Composable
internal fun BucketColumn(
    labels: List<BucketItem>,
    contentPadding: PaddingValues,
    onOpen: (BucketItem) -> Unit,
    modifier: Modifier = Modifier,
    iconRes: Int = BrandIcons.Albums,
) {
    if (labels.isEmpty()) {
        EmptyHint(contentPadding = contentPadding, modifier = modifier)
        return
    }
    LazyColumn(modifier = modifier.fillMaxSize(), contentPadding = contentPadding) {
        items(labels, key = { it.key }) { item ->
            ListItem(
                leadingContent = {
                    if (item.artUri != null) {
                        CoverImage(
                            contentUri = item.artUri,
                            contentDescription = null,
                            modifier =
                                Modifier
                                    .size(44.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant),
                        ) {
                            Icon(
                                painterResource(iconRes),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else {
                        Box(
                            modifier =
                                Modifier
                                    .size(44.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                painterResource(iconRes),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                headlineContent = {
                    Text(
                        item.title,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                supportingContent = item.subtitle?.let { { Text(it) } },
                trailingContent = {
                    Text(
                        pluralStringResource(
                            R.plurals.library_track_count,
                            item.trackCount,
                            item.trackCount,
                        ),
                        style = MaterialTheme.typography.labelMedium,
                    )
                },
                modifier = Modifier.clickable { onOpen(item) },
            )
        }
    }
}

/** Raster einer Sammlung (Poweramp Alben-Raster): Cover-Kachel + Titel/Untertitel. */
@Composable
internal fun BucketGrid(
    labels: List<BucketItem>,
    contentPadding: PaddingValues,
    columns: Int,
    onOpen: (BucketItem) -> Unit,
    modifier: Modifier = Modifier,
    iconRes: Int = BrandIcons.Albums,
) {
    if (labels.isEmpty()) {
        EmptyHint(contentPadding = contentPadding, modifier = modifier)
        return
    }
    LazyVerticalGrid(
        columns = GridCells.Fixed(columns),
        modifier = modifier.fillMaxSize(),
        contentPadding = contentPadding,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(labels, key = { it.key }) { item ->
            CoverTile(
                title = item.title,
                subtitle = item.subtitle,
                contentUri = item.artUri,
                fallbackIconRes = iconRes,
                onClick = { onOpen(item) },
            )
        }
    }
}

/** Cover-Kachel fuer die Rasteransichten; optionales eingebettetes Cover. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CoverTile(
    title: String,
    subtitle: String?,
    contentUri: String?,
    modifier: Modifier = Modifier,
    fallbackIconRes: Int = BrandIcons.NavMusic,
    selectionActive: Boolean = false,
    isSelected: Boolean = false,
    onClick: () -> Unit = {},
    onLongClick: () -> Unit = {},
) {
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .combinedClickable(onClick = onClick, onLongClick = onLongClick)
                .padding(4.dp),
    ) {
        Box(contentAlignment = Alignment.TopEnd) {
            CoverImage(
                contentUri = contentUri,
                contentDescription = null,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
            ) {
                Icon(
                    painterResource(fallbackIconRes),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(40.dp),
                )
            }
            if (selectionActive) {
                Box(modifier = Modifier.padding(8.dp)) { SelectionBadge(isSelected = isSelected) }
            }
        }
        Spacer(Modifier.width(4.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp),
        )
        if (subtitle != null) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Anzeigeeintrag einer Sammlung; [key] ist der Filterschluessel. */
data class BucketItem(
    val key: String,
    val title: String,
    val subtitle: String?,
    val trackCount: Int,
    /** Cover eines Beispieltitels fuer die Rasteransicht; null = Icon-Fallback. */
    val artUri: String? = null,
)

@Composable
private fun EmptyHint(
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier =
            modifier
                .fillMaxSize()
                .padding(contentPadding)
                .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = stringResource(R.string.library_empty),
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}

private const val FAST_SCROLLER_MIN_ITEMS = 20

internal fun formatDuration(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}

/** Gesamtdauer als "h:mm:ss" bzw. "m:ss" fuer Kategorie-Kopfzeilen. */
internal fun formatTotalDuration(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}

/** Grossgeschriebene Dateiendung als Format-Kuerzel (z. B. FLAC), sonst null. */
private fun songFormat(song: Song): String? =
    song.displayName
        .substringAfterLast('.', "")
        .uppercase()
        .takeIf { it.isNotEmpty() && it.length <= 5 }
