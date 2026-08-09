package com.dropsync.feature.library

import android.app.Activity
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dropsync.core.designsystem.icon.BrandIcons
import com.dropsync.core.model.Song

/** Ziel im internen Bibliotheks-Backstack (Poweramp-Umbau). */
private sealed interface LibraryRoute {
    data object Home : LibraryRoute

    data class Category(
        val category: LibraryCategory,
    ) : LibraryRoute

    data class Collection(
        val kind: CollectionKind,
        val key: String,
        val label: String,
        val artist: String?,
    ) : LibraryRoute

    data class FolderTree(
        val path: String,
    ) : LibraryRoute

    data class PlaylistDetailRoute(
        val id: Long,
    ) : LibraryRoute
}

/** Art der aufgeklappten Sammlung; bildet auf die vorhandene Detailabfrage ab. */
internal enum class CollectionKind { ALBUM, ARTIST, GENRE, FOLDER }

private fun CollectionKind.toView(): LibraryView =
    when (this) {
        CollectionKind.ALBUM -> LibraryView.ALBUMS
        CollectionKind.ARTIST -> LibraryView.ARTISTS
        CollectionKind.GENRE -> LibraryView.GENRES
        CollectionKind.FOLDER -> LibraryView.FOLDERS
    }

/**
 * Bibliotheksinhalt im Poweramp-Aufbau (Umbau): Startseite mit Kategorien,
 * Drill-down in Kategorie-Screens und Sammlungs-Details ueber einen internen
 * Backstack. Der globale Mini-Player der App-Shell bleibt darunter sichtbar.
 */
@Composable
internal fun LibraryContent(
    viewModel: LibraryViewModel,
    contentPadding: PaddingValues,
    scanFailed: Boolean,
    onOpenNowPlaying: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val stack = remember { mutableStateListOf<LibraryRoute>(LibraryRoute.Home) }
    val current = stack.last()
    val selectionActive by viewModel.selectionActive.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var songForPlaylist by remember { mutableStateOf<Song?>(null) }
    var pendingDelete by remember { mutableStateOf<List<Song>>(emptyList()) }
    val deleteLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                viewModel.clearSelection()
                viewModel.refresh(force = true)
            }
            pendingDelete = emptyList()
        }

    fun push(route: LibraryRoute) {
        viewModel.clearSelection()
        stack.add(route)
    }

    fun pop() {
        if (stack.size > 1) stack.removeAt(stack.lastIndex)
    }

    fun requestDelete(songs: List<Song>) {
        if (songs.isEmpty()) return
        val uris = songs.map { Uri.parse(it.contentUri) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            pendingDelete = songs
            val pending = MediaStore.createDeleteRequest(context.contentResolver, uris)
            deleteLauncher.launch(IntentSenderRequest.Builder(pending.intentSender).build())
        }
    }

    BackHandler(enabled = selectionActive || stack.size > 1) {
        when {
            selectionActive -> viewModel.clearSelection()
            else -> pop()
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        if (scanFailed) {
            Surface(color = MaterialTheme.colorScheme.errorContainer) {
                Text(
                    text = stringResource(R.string.library_scan_failed),
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        }
        when (val route = current) {
            LibraryRoute.Home -> {
                HomeRoute(viewModel, contentPadding, onOpen = { push(LibraryRoute.Category(it)) })
            }

            is LibraryRoute.Category -> {
                CategoryRoute(
                    viewModel = viewModel,
                    category = route.category,
                    contentPadding = contentPadding,
                    onBack = ::pop,
                    onOpenCollection = { push(it) },
                    onOpenFolderTree = { push(LibraryRoute.FolderTree(it)) },
                    onOpenPlaylist = { push(LibraryRoute.PlaylistDetailRoute(it)) },
                    onAddToPlaylist = { songForPlaylist = it },
                    onDelete = ::requestDelete,
                    onOpenNowPlaying = onOpenNowPlaying,
                )
            }

            is LibraryRoute.Collection -> {
                CollectionRoute(
                    viewModel = viewModel,
                    route = route,
                    contentPadding = contentPadding,
                    onBack = ::pop,
                    onAddToPlaylist = { songForPlaylist = it },
                    onDelete = ::requestDelete,
                    onOpenNowPlaying = onOpenNowPlaying,
                )
            }

            is LibraryRoute.FolderTree -> {
                FolderTreeRoute(
                    viewModel = viewModel,
                    path = route.path,
                    contentPadding = contentPadding,
                    onBack = ::pop,
                    onOpenFolder = { push(LibraryRoute.FolderTree(it.path)) },
                    onOpenLeaf = { node ->
                        push(
                            LibraryRoute.Collection(
                                kind = CollectionKind.FOLDER,
                                key = node.path,
                                label = node.name,
                                artist = null,
                            ),
                        )
                    },
                    onOpenNowPlaying = onOpenNowPlaying,
                )
            }

            is LibraryRoute.PlaylistDetailRoute -> {
                PlaylistDetailRoute(
                    viewModel = viewModel,
                    playlistId = route.id,
                    contentPadding = contentPadding,
                    onBack = ::pop,
                    onOpenNowPlaying = onOpenNowPlaying,
                )
            }
        }
    }

    val song = songForPlaylist
    if (song != null) {
        val playlists by viewModel.playlists.collectAsStateWithLifecycle()
        AddToPlaylistDialog(
            playlists = playlists,
            onDismiss = { songForPlaylist = null },
            onPick = { id ->
                viewModel.addSongToPlaylist(id, song)
                songForPlaylist = null
            },
            onCreateNew = { name ->
                viewModel.createPlaylistWithSong(name, song)
                songForPlaylist = null
            },
        )
    }
}

@Composable
private fun HomeRoute(
    viewModel: LibraryViewModel,
    contentPadding: PaddingValues,
    onOpen: (LibraryCategory) -> Unit,
) {
    val queue by viewModel.queue.collectAsStateWithLifecycle()
    val viewConfig by viewModel.viewConfig.collectAsStateWithLifecycle()
    val excluded by viewModel.excludedFolders.collectAsStateWithLifecycle()
    val allFolders by viewModel.allFolderPaths.collectAsStateWithLifecycle()

    var showFolders by remember { mutableStateOf(false) }
    var showCategories by remember { mutableStateOf(false) }

    val hidden = viewConfig?.hiddenKeys ?: emptySet()
    val visibleCategories = LibraryCategory.entries.filter { it.key !in hidden }

    LibraryHomeScreen(
        categories = visibleCategories,
        queueCount = queue.size,
        contentPadding = contentPadding,
        onOpen = onOpen,
        onRescan = { viewModel.refresh(force = true) },
        onSelectFolders = { showFolders = true },
        onEditCategories = { showCategories = true },
    )

    if (showFolders) {
        SelectFoldersDialog(
            allFolders = allFolders,
            excluded = excluded,
            onSave = { newExcluded ->
                viewModel.setExcludedFolders(newExcluded)
                showFolders = false
            },
            onDismiss = { showFolders = false },
        )
    }
    if (showCategories) {
        CategoryVisibilityDialog(
            hidden = hidden,
            onToggle = { category, visible -> viewModel.setCategoryVisible(category, visible) },
            onDismiss = { showCategories = false },
        )
    }
}

@Composable
private fun CategoryRoute(
    viewModel: LibraryViewModel,
    category: LibraryCategory,
    contentPadding: PaddingValues,
    onBack: () -> Unit,
    onOpenCollection: (LibraryRoute.Collection) -> Unit,
    onOpenFolderTree: (String) -> Unit,
    onOpenPlaylist: (Long) -> Unit,
    onAddToPlaylist: (Song) -> Unit,
    onDelete: (List<Song>) -> Unit,
    onOpenNowPlaying: () -> Unit,
) {
    when (category) {
        LibraryCategory.ALL_SONGS, LibraryCategory.FAVORITES, LibraryCategory.RECENTLY_ADDED,
        LibraryCategory.RECENTLY_PLAYED, LibraryCategory.MOST_PLAYED,
        -> {
            val raw by songSourceFor(viewModel, category).collectAsStateWithLifecycle()
            SongCategoryScreen(
                viewModel = viewModel,
                category = category,
                rawSongs = raw,
                contentPadding = contentPadding,
                onBack = onBack,
                onAddToPlaylist = onAddToPlaylist,
                onRequestDelete = onDelete,
                onOpenNowPlaying = onOpenNowPlaying,
            )
        }

        LibraryCategory.ALBUMS, LibraryCategory.ARTISTS, LibraryCategory.GENRES, LibraryCategory.FOLDERS -> {
            BucketRoute(viewModel, category, contentPadding, onBack, onOpenCollection)
        }

        LibraryCategory.FOLDERS_HIERARCHY -> {
            FolderTreeRoute(
                viewModel = viewModel,
                path = "",
                contentPadding = contentPadding,
                onBack = onBack,
                onOpenFolder = { onOpenFolderTree(it.path) },
                onOpenLeaf = { node ->
                    onOpenCollection(
                        LibraryRoute.Collection(
                            kind = CollectionKind.FOLDER,
                            key = node.path,
                            label = node.name,
                            artist = null,
                        ),
                    )
                },
                onOpenNowPlaying = onOpenNowPlaying,
            )
        }

        LibraryCategory.QUEUE -> {
            val queue by viewModel.queue.collectAsStateWithLifecycle()
            val index by viewModel.queueIndex.collectAsStateWithLifecycle()
            QueueCategoryScreen(
                queue = queue,
                currentIndex = index,
                contentPadding = contentPadding,
                onBack = onBack,
                onPlayIndex = { queueIndex ->
                    viewModel.playQueueIndex(queueIndex)
                    onOpenNowPlaying()
                },
            )
        }

        LibraryCategory.PLAYLISTS -> {
            val playlists by viewModel.playlists.collectAsStateWithLifecycle()
            Column(modifier = Modifier.fillMaxSize()) {
                CategoryHeader(
                    iconRes = categoryIcon(category),
                    title = stringResource(category.titleRes()),
                    subtitle = null,
                    onBack = onBack,
                )
                PlaylistList(
                    playlists = playlists,
                    contentPadding = contentPadding,
                    onOpen = onOpenPlaylist,
                    onCreate = viewModel::createPlaylist,
                    onRename = viewModel::renamePlaylist,
                    onDelete = viewModel::deletePlaylist,
                )
            }
        }
    }
}

/** Rohe Titelquelle je Song-Kategorie. */
@Composable
private fun songSourceFor(
    viewModel: LibraryViewModel,
    category: LibraryCategory,
) = when (category) {
    LibraryCategory.FAVORITES -> viewModel.favorites
    LibraryCategory.RECENTLY_ADDED -> viewModel.recentlyAdded
    LibraryCategory.RECENTLY_PLAYED -> viewModel.recentlyPlayed
    LibraryCategory.MOST_PLAYED -> viewModel.mostPlayed
    else -> viewModel.allSongs
}

@Composable
private fun BucketRoute(
    viewModel: LibraryViewModel,
    category: LibraryCategory,
    contentPadding: PaddingValues,
    onBack: () -> Unit,
    onOpenCollection: (LibraryRoute.Collection) -> Unit,
) {
    val allSongs by viewModel.allSongs.collectAsStateWithLifecycle()
    val items: List<BucketItem>
    val kind: CollectionKind
    val iconRes: Int
    when (category) {
        LibraryCategory.ALBUMS -> {
            val albums by viewModel.albums.collectAsStateWithLifecycle()
            val art = remember(allSongs) { allSongs.groupCoverBy { it.album } }
            items = albums.map { BucketItem(it.title, it.title, it.artist, it.trackCount, art[it.title]) }
            kind = CollectionKind.ALBUM
            iconRes = BrandIcons.Albums
        }

        LibraryCategory.ARTISTS -> {
            val artists by viewModel.artists.collectAsStateWithLifecycle()
            val art = remember(allSongs) { allSongs.groupCoverBy { it.artist } }
            items = artists.map { BucketItem(it.name, it.name, null, it.trackCount, art[it.name]) }
            kind = CollectionKind.ARTIST
            iconRes = BrandIcons.Artists
        }

        LibraryCategory.GENRES -> {
            val genres by viewModel.genres.collectAsStateWithLifecycle()
            val art = remember(allSongs) { allSongs.groupCoverBy { it.genre } }
            items = genres.map { BucketItem(it.name, it.name, null, it.trackCount, art[it.name]) }
            kind = CollectionKind.GENRE
            iconRes = BrandIcons.Genres
        }

        else -> {
            val folders by viewModel.folders.collectAsStateWithLifecycle()
            val art = remember(allSongs) { allSongs.groupCoverBy { it.relativePath } }
            items =
                folders.map {
                    val name =
                        it.relativePath
                            .trim('/')
                            .substringAfterLast('/')
                            .ifEmpty { it.relativePath }
                    val parent = it.relativePath.trim('/').substringBeforeLast('/', "")
                    BucketItem(it.relativePath, name, parent.ifEmpty { null }, it.trackCount, art[it.relativePath])
                }
            kind = CollectionKind.FOLDER
            iconRes = BrandIcons.Folder
        }
    }
    BucketCategoryScreen(
        viewModel = viewModel,
        category = category,
        items = items,
        contentPadding = contentPadding,
        iconRes = iconRes,
        onBack = onBack,
        onOpen = { item ->
            onOpenCollection(
                LibraryRoute.Collection(kind, item.key, item.title, item.subtitle),
            )
        },
    )
}

@Composable
private fun CollectionRoute(
    viewModel: LibraryViewModel,
    route: LibraryRoute.Collection,
    contentPadding: PaddingValues,
    onBack: () -> Unit,
    onAddToPlaylist: (Song) -> Unit,
    onDelete: (List<Song>) -> Unit,
    onOpenNowPlaying: () -> Unit,
) {
    LaunchedEffect(route.kind, route.key) {
        viewModel.openBucket(route.kind.toView(), route.key, route.label)
    }
    val songs by viewModel.detailSongs.collectAsStateWithLifecycle()
    val configCategory =
        when (route.kind) {
            CollectionKind.ALBUM -> LibraryCategory.ALBUMS
            CollectionKind.ARTIST -> LibraryCategory.ARTISTS
            CollectionKind.GENRE -> LibraryCategory.GENRES
            CollectionKind.FOLDER -> LibraryCategory.FOLDERS
        }
    CollectionSongScreen(
        viewModel = viewModel,
        configCategory = configCategory,
        headerIcon = collectionIcon(route.kind),
        title = route.label,
        subtitleArtist = route.artist,
        songs = songs,
        hero = route.kind == CollectionKind.ALBUM,
        contentPadding = contentPadding,
        onBack = onBack,
        onAddToPlaylist = onAddToPlaylist,
        onRequestDelete = onDelete,
        onOpenNowPlaying = onOpenNowPlaying,
    )
}

@Composable
private fun FolderTreeRoute(
    viewModel: LibraryViewModel,
    path: String,
    contentPadding: PaddingValues,
    onBack: () -> Unit,
    onOpenFolder: (FolderNode) -> Unit,
    onOpenLeaf: (FolderNode) -> Unit,
    onOpenNowPlaying: () -> Unit,
) {
    val folders by viewModel.folders.collectAsStateWithLifecycle()
    val allSongs by viewModel.allSongs.collectAsStateWithLifecycle()
    // Alle Titel im Teilbaum des aktuellen Ordners (Poweramp: Play/Shuffle in
    // der Ordner-Hierarchie spielt rekursiv den ganzen Ordner). Pfad-Praefix
    // wie in FolderHierarchy (getrimmte relative_path-Segmente).
    val subtree =
        remember(allSongs, path) {
            if (path.isEmpty()) {
                allSongs
            } else {
                allSongs
                    .filter {
                        val rp = it.relativePath.trim('/')
                        rp == path || rp.startsWith("$path/")
                    }.sortedWith(compareBy({ it.relativePath }, { songTitle(it) }))
            }
        }
    FolderTreeScreen(
        path = path,
        folders = folders,
        contentPadding = contentPadding,
        onBack = onBack,
        onOpenFolder = onOpenFolder,
        onOpenLeaf = onOpenLeaf,
        playEnabled = subtree.isNotEmpty(),
        onPlayAll = {
            if (subtree.isNotEmpty()) {
                viewModel.play(subtree, 0)
                onOpenNowPlaying()
            }
        },
        onShuffle = {
            if (subtree.isNotEmpty()) {
                viewModel.shufflePlay(subtree)
                onOpenNowPlaying()
            }
        },
    )
}

@Composable
private fun PlaylistDetailRoute(
    viewModel: LibraryViewModel,
    playlistId: Long,
    contentPadding: PaddingValues,
    onBack: () -> Unit,
    onOpenNowPlaying: () -> Unit,
) {
    LaunchedEffect(playlistId) { viewModel.openPlaylist(playlistId) }
    val playlist by viewModel.openPlaylist.collectAsStateWithLifecycle()
    val songs by viewModel.playlistSongs.collectAsStateWithLifecycle()
    val pl = playlist
    if (pl != null) {
        PlaylistDetail(
            playlist = pl,
            songs = songs,
            contentPadding = contentPadding,
            onBack = {
                viewModel.closePlaylist()
                onBack()
            },
            onPlay = { index ->
                viewModel.play(songs, index)
                onOpenNowPlaying()
            },
            onRemove = { position -> viewModel.removeFromPlaylist(pl.id, position) },
            onMove = { from, to -> viewModel.moveInPlaylist(pl.id, from, to) },
            onSetLabel = { label -> viewModel.setPlaylistLabel(pl.id, label) },
        )
    }
}

/** Erstes Cover je Gruppierungsschluessel (Album/Interpret/Genre/Ordner). */
private inline fun List<Song>.groupCoverBy(key: (Song) -> String?): Map<String, String> {
    val map = HashMap<String, String>()
    for (song in this) {
        val k = key(song) ?: continue
        if (k.isNotEmpty() && k !in map) map[k] = song.contentUri
    }
    return map
}

private fun collectionIcon(kind: CollectionKind): Int =
    when (kind) {
        CollectionKind.ALBUM -> BrandIcons.Albums
        CollectionKind.ARTIST -> BrandIcons.Artists
        CollectionKind.GENRE -> BrandIcons.Genres
        CollectionKind.FOLDER -> BrandIcons.Folder
    }
