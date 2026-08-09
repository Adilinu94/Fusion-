package com.dropsync.feature.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dropsync.core.model.Song
import com.dropsync.domain.playback.QueueItem

/**
 * Kategorie mit reiner Titelliste (Alle Titel, Favoriten, zuletzt/meist
 * gespielt). Toolbar (Shuffle/Play/Suche/Auswaehlen/Optionen), Sortierung und
 * Ansichtsmodus laut Listen-Optionen; Langdruck startet den Auswahlmodus.
 */
@Composable
internal fun SongCategoryScreen(
    viewModel: LibraryViewModel,
    category: LibraryCategory,
    rawSongs: List<Song>,
    contentPadding: PaddingValues,
    onBack: () -> Unit,
    onAddToPlaylist: (Song) -> Unit,
    onRequestDelete: (List<Song>) -> Unit,
    onOpenNowPlaying: () -> Unit,
) {
    val config by viewModel.listConfigs.getValue(category).collectAsStateWithLifecycle()
    val playStats by viewModel.playStats.collectAsStateWithLifecycle()
    val favoriteIds by viewModel.favoriteIds.collectAsStateWithLifecycle()
    val selectionActive by viewModel.selectionActive.collectAsStateWithLifecycle()
    val selectedIds by viewModel.selectedIds.collectAsStateWithLifecycle()

    var showOptions by remember { mutableStateOf(false) }
    var searchOpen by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var infoSong by remember { mutableStateOf<Song?>(null) }

    val sorted =
        remember(rawSongs, config, playStats, query) {
            val filtered =
                if (query.isBlank()) {
                    rawSongs
                } else {
                    val q = query.trim().lowercase()
                    rawSongs.filter {
                        songTitle(it).lowercase().contains(q) ||
                            (it.artist?.lowercase()?.contains(q) == true) ||
                            (it.album?.lowercase()?.contains(q) == true)
                    }
                }
            LibrarySortEngine.sort(filtered, config.sort, config.descending, playStats)
        }

    val headerSubtitle =
        stringResource(
            R.string.library_header_meta,
            pluralStringResource(R.plurals.library_track_count, rawSongs.size, rawSongs.size),
            formatTotalDuration(rawSongs.sumOf { it.durationMs }),
        )

    Column(modifier = Modifier.fillMaxSize()) {
        CategoryHeader(
            iconRes = categoryIcon(category),
            title = stringResource(category.titleRes()),
            subtitle = headerSubtitle,
            onBack = onBack,
        )
        LibraryToolbar(
            onShuffle = { viewModel.shufflePlay(sorted) },
            onPlayAll = { viewModel.play(sorted, 0) },
            onToggleSearch = { searchOpen = !searchOpen },
            onSelect = { if (sorted.isNotEmpty()) viewModel.startSelection(sorted.first().mediaStoreId) },
            onOpenListOptions = { showOptions = true },
            enabled = sorted.isNotEmpty(),
        )
        if (searchOpen) {
            InlineSearchField(query = query, onQueryChange = { query = it })
        }
        Box(modifier = Modifier.weight(1f)) {
            when (config.viewMode) {
                LibraryViewMode.GRID, LibraryViewMode.GRID_SMALL -> {
                    SongGrid(
                        songs = sorted,
                        contentPadding = contentPadding,
                        columns = if (config.viewMode == LibraryViewMode.GRID) 2 else 3,
                        onPlay = { index ->
                            viewModel.play(sorted, index)
                            onOpenNowPlaying()
                        },
                        selectionActive = selectionActive,
                        selectedIds = selectedIds,
                        onLongPress = { viewModel.startSelection(it.mediaStoreId) },
                        onToggleSelect = { viewModel.toggleSelection(it.mediaStoreId) },
                    )
                }

                else -> {
                    SongColumn(
                        songs = sorted,
                        favoriteIds = favoriteIds,
                        contentPadding = contentPadding,
                        onPlay = { index ->
                            viewModel.play(sorted, index)
                            onOpenNowPlaying()
                        },
                        onToggleFavorite = viewModel::toggleFavorite,
                        onPlayNext = viewModel::playNext,
                        onAddToQueue = viewModel::addToQueue,
                        onDetectDrops = viewModel::detectDrops,
                        onAddToPlaylist = onAddToPlaylist,
                        compact = config.viewMode == LibraryViewMode.LIST_COMPACT,
                        // Poweramp "Alle Titel": kein Herz/⋮ je Zeile, Titel volle
                        // Breite; Aktionen laufen ueber Langdruck-Auswahl.
                        showTrailingActions = false,
                        selectionActive = selectionActive,
                        selectedIds = selectedIds,
                        onLongPress = { viewModel.startSelection(it.mediaStoreId) },
                        onToggleSelect = { viewModel.toggleSelection(it.mediaStoreId) },
                    )
                }
            }
        }

        SelectionActionsBar(
            viewModel = viewModel,
            pool = sorted,
            contentPadding = contentPadding,
            onShowInfo = { infoSong = it },
            onRequestDelete = onRequestDelete,
        )
    }

    if (showOptions) {
        ListOptionsSheet(
            categoryTitle = stringResource(category.titleRes()),
            config = config,
            sortOptions = LibrarySortEngine.songSorts,
            viewModes = LibraryViewMode.entries,
            onSort = { viewModel.setSortFor(category, it) },
            onDescending = { viewModel.setDescendingFor(category, it) },
            onViewMode = { viewModel.setViewModeFor(category, it) },
            onDismiss = { showOptions = false },
        )
    }
    infoSong?.let { song ->
        TrackInfoDialog(
            title = songTitle(song),
            path = song.relativePath + "/" + song.displayName,
            duration = formatDuration(song.durationMs),
            size = formatSize(song.sizeBytes),
            onDismiss = { infoSong = null },
        )
    }
}

/**
 * Untere Auswahlleiste inklusive Aufloesung der Auswahl gegen [pool]. Sichtbar
 * nur im Auswahlmodus; kapselt die Aktionen fuer beide Screen-Typen.
 */
@Composable
internal fun SelectionActionsBar(
    viewModel: LibraryViewModel,
    pool: List<Song>,
    contentPadding: PaddingValues,
    onShowInfo: (Song) -> Unit,
    onRequestDelete: (List<Song>) -> Unit,
) {
    val selectionActive by viewModel.selectionActive.collectAsStateWithLifecycle()
    val selectedIds by viewModel.selectedIds.collectAsStateWithLifecycle()
    val playlists by viewModel.playlists.collectAsStateWithLifecycle()
    var pickPlaylist by remember { mutableStateOf(false) }
    if (!selectionActive) return
    val selectedSongs = pool.filter { it.mediaStoreId in selectedIds }
    SelectionBar(
        selectedCount = selectedIds.size,
        totalCount = pool.size,
        onSelectAll = { viewModel.selectAll(pool.map { it.mediaStoreId }) },
        onClose = viewModel::clearSelection,
        onAddToPlaylist = { if (selectedSongs.isNotEmpty()) pickPlaylist = true },
        onAddToQueue = { viewModel.addSelectionToQueue(pool) },
        onPlayNext = { viewModel.playSelectionNext(pool) },
        onInfo = { selectedSongs.firstOrNull()?.let(onShowInfo) },
        onDelete = { if (selectedSongs.isNotEmpty()) onRequestDelete(selectedSongs) },
        bottomInset = contentPadding.calculateBottomPadding(),
    )
    if (pickPlaylist) {
        AddToPlaylistDialog(
            playlists = playlists,
            onDismiss = { pickPlaylist = false },
            onPick = { id ->
                viewModel.addSelectionToPlaylist(id, pool)
                pickPlaylist = false
            },
            onCreateNew = { name ->
                viewModel.createPlaylist(name)
                pickPlaylist = false
            },
        )
    }
}

/**
 * Kategorie als Sammlung (Alben/Interpreten/Genres/Ordner): Liste oder Raster
 * mit Titelzahl; Tippen oeffnet die Titel der Sammlung. Cover werden aus einem
 * Beispieltitel der Sammlung abgeleitet.
 */
@Composable
internal fun BucketCategoryScreen(
    viewModel: LibraryViewModel,
    category: LibraryCategory,
    items: List<BucketItem>,
    contentPadding: PaddingValues,
    iconRes: Int,
    onBack: () -> Unit,
    onOpen: (BucketItem) -> Unit,
) {
    val config by viewModel.listConfigs.getValue(category).collectAsStateWithLifecycle()
    var showOptions by remember { mutableStateOf(false) }

    val ordered =
        remember(items, config) {
            val base = items.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title })
            if (config.descending) base.asReversed() else base
        }

    Column(modifier = Modifier.fillMaxSize()) {
        CategoryHeader(
            iconRes = iconRes,
            title = stringResource(category.titleRes()),
            subtitle =
                pluralStringResource(R.plurals.library_track_count, items.size, items.size),
            onBack = onBack,
        )
        LibraryToolbar(
            onShuffle = null,
            onPlayAll = null,
            onToggleSearch = null,
            onSelect = null,
            onOpenListOptions = { showOptions = true },
        )
        Box(modifier = Modifier.weight(1f)) {
            when (config.viewMode) {
                LibraryViewMode.GRID, LibraryViewMode.GRID_SMALL -> {
                    BucketGrid(
                        labels = ordered,
                        contentPadding = contentPadding,
                        columns = if (config.viewMode == LibraryViewMode.GRID) 2 else 3,
                        onOpen = onOpen,
                        iconRes = iconRes,
                    )
                }

                else -> {
                    BucketColumn(
                        labels = ordered,
                        contentPadding = contentPadding,
                        onOpen = onOpen,
                        iconRes = iconRes,
                    )
                }
            }
        }
    }

    if (showOptions) {
        ListOptionsSheet(
            categoryTitle = stringResource(category.titleRes()),
            config = config,
            sortOptions = emptyList(),
            viewModes = LibraryViewMode.entries,
            onSort = {},
            onDescending = { viewModel.setDescendingFor(category, it) },
            onViewMode = { viewModel.setViewModeFor(category, it) },
            onDismiss = { showOptions = false },
        )
    }
}

/**
 * Warteschlange als Kategorie (Poweramp "Queue"): aktuelle Reihenfolge, der
 * laufende Titel ist hervorgehoben; Tippen springt an die Stelle.
 */
@Composable
internal fun QueueCategoryScreen(
    queue: List<QueueItem>,
    currentIndex: Int,
    contentPadding: PaddingValues,
    onBack: () -> Unit,
    onPlayIndex: (Int) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        CategoryHeader(
            iconRes = categoryIcon(LibraryCategory.QUEUE),
            title = stringResource(LibraryCategory.QUEUE.titleRes()),
            subtitle = pluralStringResource(R.plurals.library_track_count, queue.size, queue.size),
            onBack = onBack,
        )
        if (queue.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(stringResource(R.string.library_empty), style = MaterialTheme.typography.bodyLarge)
            }
            return
        }
        LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = contentPadding) {
            itemsIndexed(queue, key = { _, item -> item.mediaId }) { index, item ->
                val highlight = index == currentIndex
                ListItem(
                    headlineContent = {
                        Text(
                            item.title,
                            style = MaterialTheme.typography.titleMedium,
                            color =
                                if (highlight) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    supportingContent =
                        item.artist?.let {
                            {
                                Text(
                                    it,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        },
                    modifier = Modifier.clickable { onPlayIndex(index) },
                )
            }
        }
    }
}

/** Menschlich lesbare Dateigroesse fuer den Info-Dialog. */
internal fun formatSize(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = listOf("B", "KB", "MB", "GB")
    var value = bytes.toDouble()
    var unit = 0
    while (value >= 1024 && unit < units.lastIndex) {
        value /= 1024
        unit++
    }
    return if (unit == 0) "$bytes B" else "%.1f %s".format(value, units[unit])
}
