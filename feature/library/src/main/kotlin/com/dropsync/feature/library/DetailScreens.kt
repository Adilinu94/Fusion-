package com.dropsync.feature.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dropsync.core.designsystem.component.CoverImage
import com.dropsync.core.designsystem.icon.BrandIcons
import com.dropsync.core.model.Song

/**
 * Titel einer Sammlung (Album/Interpret/Genre/Ordner). Alben erhalten einen
 * Hero-Kopf mit Cover als Hintergrund (Poweramp Album-Detail); die uebrigen
 * bekommen die schlanke [CategoryHeader]. Sortierung/Ansicht folgen den
 * Listen-Optionen der zugehoerigen Kategorie.
 */
@Composable
internal fun CollectionSongScreen(
    viewModel: LibraryViewModel,
    configCategory: LibraryCategory,
    headerIcon: Int,
    title: String,
    subtitleArtist: String?,
    songs: List<Song>,
    hero: Boolean,
    contentPadding: PaddingValues,
    onBack: () -> Unit,
    onAddToPlaylist: (Song) -> Unit,
    onRequestDelete: (List<Song>) -> Unit,
    onOpenNowPlaying: () -> Unit,
) {
    val config by viewModel.listConfigs.getValue(configCategory).collectAsStateWithLifecycle()
    val playStats by viewModel.playStats.collectAsStateWithLifecycle()
    val favoriteIds by viewModel.favoriteIds.collectAsStateWithLifecycle()
    val selectionActive by viewModel.selectionActive.collectAsStateWithLifecycle()
    val selectedIds by viewModel.selectedIds.collectAsStateWithLifecycle()
    var showOptions by remember { mutableStateOf(false) }
    var infoSong by remember { mutableStateOf<Song?>(null) }

    val sorted =
        remember(songs, config, playStats) {
            LibrarySortEngine.sort(songs, config.sort, config.descending, playStats)
        }
    val meta =
        stringResource(
            R.string.library_header_meta,
            pluralStringResource(R.plurals.library_track_count, songs.size, songs.size),
            formatTotalDuration(songs.sumOf { it.durationMs }),
        )

    Column(modifier = Modifier.fillMaxSize()) {
        if (hero) {
            AlbumHero(
                coverUri = songs.firstOrNull()?.contentUri,
                title = title,
                artist = subtitleArtist,
                meta = meta,
                onBack = onBack,
            )
        } else {
            CategoryHeader(
                iconRes = headerIcon,
                title = title,
                subtitle = subtitleArtist?.let { "$it  |  $meta" } ?: meta,
                onBack = onBack,
            )
        }
        LibraryToolbar(
            onShuffle = { viewModel.shufflePlay(sorted) },
            onPlayAll = { viewModel.play(sorted, 0) },
            onToggleSearch = null,
            onSelect = { if (sorted.isNotEmpty()) viewModel.startSelection(sorted.first().mediaStoreId) },
            onOpenListOptions = { showOptions = true },
            enabled = sorted.isNotEmpty(),
        )
        Box(modifier = Modifier.weight(1f)) {
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
                showFastScroller = false,
                compact = config.viewMode == LibraryViewMode.LIST_COMPACT,
                selectionActive = selectionActive,
                selectedIds = selectedIds,
                onLongPress = { viewModel.startSelection(it.mediaStoreId) },
                onToggleSelect = { viewModel.toggleSelection(it.mediaStoreId) },
            )
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
            categoryTitle = title,
            config = config,
            sortOptions = LibrarySortEngine.songSorts,
            viewModes = LibraryViewMode.entries,
            onSort = { viewModel.setSortFor(configCategory, it) },
            onDescending = { viewModel.setDescendingFor(configCategory, it) },
            onViewMode = { viewModel.setViewModeFor(configCategory, it) },
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

/** Hero-Kopf des Album-Details: Cover als Hintergrund mit Scrim, Titel/Meta. */
@Composable
private fun AlbumHero(
    coverUri: String?,
    title: String,
    artist: String?,
    meta: String,
    onBack: () -> Unit,
) {
    Box(modifier = Modifier.fillMaxWidth().height(220.dp)) {
        CoverImage(
            contentUri = coverUri,
            contentDescription = null,
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Icon(
                painterResource(BrandIcons.Albums),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // Scrim, damit der Text auf jedem Cover lesbar bleibt.
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0f to Color.Transparent,
                            1f to Color.Black.copy(alpha = 0.7f),
                        ),
                    ),
        )
        androidx.compose.material3.IconButton(
            onClick = onBack,
            modifier = Modifier.padding(4.dp),
        ) {
            Icon(
                painterResource(BrandIcons.Back),
                contentDescription = stringResource(R.string.library_back),
                tint = Color.White,
            )
        }
        Column(
            modifier =
                Modifier
                    .align(Alignment.BottomStart)
                    .padding(20.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (artist != null) {
                Text(
                    text = artist,
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color.White.copy(alpha = 0.85f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = meta,
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.7f),
            )
        }
    }
}

/**
 * Ordner-Hierarchie (Poweramp "Folders Hierarchy"): zeigt die direkten
 * Unterordner von [path] mit Aktionsleiste (Shuffle/Play spielen den ganzen
 * Teilbaum, das Drei-Punkte-Menue sortiert die Ordner). Ein Ordner ohne
 * Unterordner ist ein Blatt und wird ueber [onOpenLeaf] als Titelliste
 * geoeffnet; sonst geht es per [onOpenFolder] eine Ebene tiefer. Navigierbare
 * Ordner tragen rechts ein Ordner-Symbol als Hinweis (Poweramp).
 */
@Composable
internal fun FolderTreeScreen(
    path: String,
    folders: List<com.dropsync.domain.library.LibraryFolder>,
    contentPadding: PaddingValues,
    onBack: () -> Unit,
    onOpenFolder: (FolderNode) -> Unit,
    onOpenLeaf: (FolderNode) -> Unit,
    playEnabled: Boolean,
    onPlayAll: () -> Unit,
    onShuffle: () -> Unit,
) {
    val children = remember(folders, path) { FolderHierarchy.childrenOf(folders, path) }
    var folderSort by remember { mutableStateOf(FolderSort.NAME_ASC) }
    var sortMenuOpen by remember { mutableStateOf(false) }
    val ordered =
        remember(children, folderSort) {
            when (folderSort) {
                FolderSort.NAME_ASC -> children
                FolderSort.NAME_DESC -> children.sortedByDescending { it.name.lowercase() }
                FolderSort.TRACKS_DESC -> children.sortedByDescending { it.trackCount }
            }
        }
    val title =
        if (path.isEmpty()) {
            stringResource(LibraryCategory.FOLDERS_HIERARCHY.titleRes())
        } else {
            path.substringAfterLast('/')
        }
    Column(modifier = Modifier.fillMaxSize()) {
        CategoryHeader(
            iconRes = BrandIcons.Folder,
            title = title,
            subtitle = if (path.isEmpty()) null else path,
            onBack = onBack,
        )
        // Poweramp-Aktionsleiste der Ordner-Hierarchie: Shuffle/Play spielen den
        // Teilbaum, das Drei-Punkte-Menue bietet die Ordnersortierung.
        Box(modifier = Modifier.fillMaxWidth()) {
            LibraryToolbar(
                onShuffle = onShuffle,
                onPlayAll = onPlayAll,
                onToggleSearch = null,
                onSelect = null,
                onOpenListOptions = { sortMenuOpen = true },
                enabled = playEnabled,
            )
            DropdownMenu(
                expanded = sortMenuOpen,
                onDismissRequest = { sortMenuOpen = false },
                modifier = Modifier.align(Alignment.TopEnd),
            ) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.library_folder_sort_name_asc)) },
                    onClick = {
                        folderSort = FolderSort.NAME_ASC
                        sortMenuOpen = false
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.library_folder_sort_name_desc)) },
                    onClick = {
                        folderSort = FolderSort.NAME_DESC
                        sortMenuOpen = false
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.library_folder_sort_tracks)) },
                    onClick = {
                        folderSort = FolderSort.TRACKS_DESC
                        sortMenuOpen = false
                    },
                )
            }
        }
        LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = contentPadding) {
            items(ordered, key = { it.path }) { node ->
                val deeper = FolderHierarchy.childrenOf(folders, node.path).isNotEmpty()
                ListItem(
                    leadingContent = {
                        Box(
                            modifier =
                                Modifier
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant)
                                    .padding(10.dp),
                        ) {
                            Icon(
                                painterResource(BrandIcons.Folder),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    headlineContent = {
                        Text(
                            node.name,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    supportingContent = {
                        Text(
                            stringResource(
                                R.string.library_header_meta,
                                pluralStringResource(
                                    R.plurals.library_track_count,
                                    node.trackCount,
                                    node.trackCount,
                                ),
                                formatTotalDuration(node.totalDurationMs),
                            ),
                        )
                    },
                    trailingContent =
                        if (deeper) {
                            {
                                Icon(
                                    painterResource(BrandIcons.Folder),
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        } else {
                            null
                        },
                    modifier =
                        Modifier.clickable {
                            if (deeper) onOpenFolder(node) else onOpenLeaf(node)
                        },
                )
            }
        }
    }
}

/** Sortierung der Ordner-Hierarchie (Poweramp: Ordner sortieren). */
private enum class FolderSort { NAME_ASC, NAME_DESC, TRACKS_DESC }
