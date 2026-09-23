package com.dropsync.feature.library

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.activity.compose.PredictiveBackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.DeferredAnimatedContent
import androidx.compose.animation.MutableContentTransform
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.DeferredTransitionState
import androidx.compose.animation.core.ExperimentalDeferredTransitionApi
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dropsync.core.designsystem.component.FlowRepTopBar
import com.dropsync.core.designsystem.icon.BrandIcons
import com.dropsync.core.designsystem.theme.LocalReducedMotion
import com.dropsync.core.model.Song
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

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
 * Fortschritt der Zurueck-Geste (Ausbauplan A4; seit Compose 1.12 auf
 * Predictive-Back-Bausteinen): liefert den Finger-Fortschritt 0..1 waehrend
 * der Geste, sonst null. Erst beim Loslassen faellt die Entscheidung
 * (Auswahl loeschen oder poppen) — ein Abbruch animiert die Ueberlagerung
 * per Handoff zurueck. Die echte Uebergangsanimation uebernimmt
 * [DeferredAnimatedContent] im Rumpf von `LibraryContent`.
 */
@OptIn(ExperimentalDeferredTransitionApi::class)
@Composable
private fun libraryBackProgress(
    stack: SnapshotStateList<LibraryRoute>,
    selectionActive: Boolean,
    transitionState: DeferredTransitionState<LibraryRoute>,
    onClearSelection: () -> Unit,
    onPop: () -> Unit,
): Float? {
    var backProgress by remember { mutableStateOf<Float?>(null) }
    PredictiveBackHandler(enabled = selectionActive || stack.size > 1) { progress ->
        val routeProgress = !selectionActive && stack.size > 1
        if (routeProgress) {
            // Deferred-Phase: die Elternroute wird vorbereitet, aber noch
            // nicht angesteuert; die Transformation folgt dem Finger.
            transitionState.defer(stack[stack.lastIndex - 1])
        }
        try {
            progress.collect { event ->
                backProgress = if (routeProgress) event.progress else null
            }
        } catch (e: CancellationException) {
            backProgress = null
            if (routeProgress) {
                // Abbruch: Handoff animiert die Transformation zurueck.
                transitionState.animateTo(stack.last())
            }
            throw e
        }
        backProgress = null
        when {
            selectionActive -> onClearSelection()
            else -> onPop()
        }
    }
    return backProgress
}

/**
 * Bibliotheksinhalt im Poweramp-Aufbau (Umbau): Startseite mit Kategorien,
 * Drill-down in Kategorie-Screens und Sammlungs-Details ueber einen internen
 * Backstack. Der globale Mini-Player der App-Shell bleibt darunter sichtbar.
 */
@OptIn(ExperimentalDeferredTransitionApi::class)
@Composable
internal fun LibraryContent(
    viewModel: LibraryViewModel,
    contentPadding: PaddingValues,
    scanFailed: Boolean,
    onOpenNowPlaying: () -> Unit,
    modifier: Modifier = Modifier,
    // B4: App-weiter Snackbar-Host (Undo) aus der Shell.
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
) {
    val stack = remember { mutableStateListOf<LibraryRoute>(LibraryRoute.Home) }
    // Compose 1.12 Predictive Back: die Route-Uebergaenge laufen ueber eine
    // Deferred-Transition. Waehrend der Geste bleibt der Zielzustand offen
    // (defer), die Transformation folgt dem Finger; beim Loslassen uebernimmt
    // entweder der Handoff in die Zielroute oder die Rueckkehr zur Ausgangsroute.
    val transitionState = remember { DeferredTransitionState(stack.last()) }
    val routeTransition = rememberTransition(transitionState, label = "library-route")
    val selectionActive by viewModel.selectionActive.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var songForPlaylist by remember { mutableStateOf<Song?>(null) }
    var pendingDelete by remember { mutableStateOf<List<Song>>(emptyList()) }

    // UI-Befund 4.2.2/4.2.4 + Befund 6.2: alle ViewModel-Rueckmeldungen
    // laufen als Snackbar (Extraktion, Detekt CyclomaticComplexMethod).
    LibraryNoticeSnackbars(viewModel, snackbarHostState)
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
        transitionState.animateTo(route)
    }

    fun pop() {
        if (stack.size > 1) {
            stack.removeAt(stack.lastIndex)
            transitionState.animateTo(stack.last())
        }
    }

    // Der Screen folgt dem Finger (leichtes Mitschieben + Abdunkeln, manuelle
    // Transformation unten); null ohne Geste oder bei Auswahl-Modus.
    val backProgress =
        libraryBackProgress(
            stack = stack,
            selectionActive = selectionActive,
            transitionState = transitionState,
            onClearSelection = viewModel::clearSelection,
            onPop = ::pop,
        )

    fun requestDelete(songs: List<Song>) {
        if (songs.isEmpty()) return
        val uris = songs.map { Uri.parse(it.contentUri) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            pendingDelete = songs
            val pending = MediaStore.createDeleteRequest(context.contentResolver, uris)
            deleteLauncher.launch(IntentSenderRequest.Builder(pending.intentSender).build())
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
        // Befund 7.1.4: sichtbarer Scan-Fortschritt — `isRefreshing` lief
        // bisher ins Leere, waehrend der Nutzer vor unveraenderter Liste
        // stand (Berechtigung, Pull-to-Refresh, Ordnerwechsel).
        val isRefreshing by viewModel.isRefreshing.collectAsStateWithLifecycle()
        if (isRefreshing) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        // Poweramp-artiger Uebergang: der alte Screen blendet aus, der neue
        // sanft ein. Bewusst nur Fade, kein Slide, damit es ruhig und
        // akkuschonend bleibt. Bei Reduced Motion ohne Uebergang.
        // Waehrend der Zurueck-Geste folgt der aktuelle Screen dem Finger
        // (manuelle Transformation in der Deferred-Phase); beim Loslassen
        // uebernimmt der Handoff. transitionSpec ist kein @Composable-Kontext:
        // Wert vorher einfangen.
        val reducedMotion = LocalReducedMotion.current
        routeTransition.DeferredAnimatedContent(
            transitionSpec = {
                if (reducedMotion) {
                    (fadeIn(snap()) togetherWith fadeOut(snap()))
                        .using(SizeTransform(clip = false))
                } else {
                    (fadeIn(tween(220)) togetherWith fadeOut(tween(180)))
                        .using(SizeTransform(clip = false))
                }
            },
            modifier = Modifier.weight(1f),
            mutableTransformSpec = {
                MutableContentTransform {
                    initialContentTransform { fullSize ->
                        // Der verlassene Screen schiebt mit dem Finger nach
                        // rechts und dunkelt leicht ab. Der Zielwert wird erst
                        // hier (Layout-Phase) gelesen, nicht in der Composition.
                        val gesture = backProgress
                        if (gesture != null) {
                            offset = IntOffset((fullSize.width * gesture * 0.25f).roundToInt(), 0)
                            alpha = 1f - gesture * 0.25f
                        }
                    }
                }
            },
        ) { route ->
            when (route) {
                LibraryRoute.Home -> {
                    HomeRoute(
                        viewModel = viewModel,
                        contentPadding = contentPadding,
                        onOpen = { push(LibraryRoute.Category(it)) },
                        onOpenNowPlaying = onOpenNowPlaying,
                        onOpenPlaylist = { push(LibraryRoute.PlaylistDetailRoute(it)) },
                        snackbarHostState = snackbarHostState,
                    )
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
                        snackbarHostState = snackbarHostState,
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
                        snackbarHostState = snackbarHostState,
                    )
                }
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
private fun LibraryNoticeSnackbars(
    viewModel: LibraryViewModel,
    snackbarHostState: SnackbarHostState,
) {
    val context = LocalContext.current
    // UI-Befund 4.2.2: uebersprungene Duplikate sichtbar machen statt still.
    LaunchedEffect(viewModel) {
        viewModel.duplicateSkips.collect { skipped ->
            snackbarHostState.showSnackbar(
                context.getString(R.string.library_playlist_duplicates_skipped, skipped),
            )
        }
    }

    // UI-Befund 4.2.4: Ergebnis des SAF-Ordnerscans (null = Fehler).
    val scanFailedText = stringResource(R.string.library_scan_saf_failed)
    LaunchedEffect(viewModel) {
        viewModel.folderScanResult.collect { result ->
            val message =
                if (result == null) {
                    scanFailedText
                } else {
                    context.getString(
                        R.string.library_scan_saf_done_detail,
                        result.audioFiles,
                        result.cueSheets,
                        result.importedCueTracks,
                    )
                }
            snackbarHostState.showSnackbar(message)
        }
    }

    // UI-Befund 4.2.4: Ergebnis des M3U-Imports (null = Fehler).
    val m3uFailedText = stringResource(R.string.library_m3u_import_failed)
    LaunchedEffect(viewModel) {
        viewModel.m3uImportResult.collect { result ->
            val message =
                if (result == null) {
                    m3uFailedText
                } else {
                    context.getString(
                        R.string.library_m3u_import_done_detail,
                        result.importedCount,
                        result.unresolved,
                        result.skippedRemote,
                    )
                }
            snackbarHostState.showSnackbar(message)
        }
    }

    // Befund 6.2: Playlist-/Favoriten-/Such-/Abspielfehler (vorher stumm).
    val playlistCreateFailedText = stringResource(R.string.library_playlist_create_failed)
    val playlistRenameFailedText = stringResource(R.string.library_playlist_rename_failed)
    val playlistChangeFailedText = stringResource(R.string.library_playlist_change_failed)
    val playlistRestoreFailedText = stringResource(R.string.library_playlist_restore_failed)
    val favoriteFailedText = stringResource(R.string.library_favorite_failed)
    val searchFailedText = stringResource(R.string.library_search_failed)
    val playFailedText = stringResource(R.string.library_play_failed)
    LaunchedEffect(viewModel) {
        viewModel.playlistNotice.collect { notice ->
            val message =
                when (notice) {
                    PlaylistNotice.CREATE_FAILED -> playlistCreateFailedText
                    PlaylistNotice.RENAME_FAILED -> playlistRenameFailedText
                    PlaylistNotice.CHANGE_FAILED -> playlistChangeFailedText
                    PlaylistNotice.RESTORE_FAILED -> playlistRestoreFailedText
                    PlaylistNotice.FAVORITE_FAILED -> favoriteFailedText
                    PlaylistNotice.SEARCH_FAILED -> searchFailedText
                    PlaylistNotice.PLAY_FAILED -> playFailedText
                }
            snackbarHostState.showSnackbar(message)
        }
    }
}

@Composable
private fun HomeRoute(
    viewModel: LibraryViewModel,
    contentPadding: PaddingValues,
    onOpen: (LibraryCategory) -> Unit,
    onOpenNowPlaying: () -> Unit,
    onOpenPlaylist: (Long) -> Unit,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
) {
    val queue by viewModel.queue.collectAsStateWithLifecycle()
    val playbackState by viewModel.playbackState.collectAsStateWithLifecycle()
    val pendingMarkers by viewModel.pendingMarkerReviews.collectAsStateWithLifecycle()
    val dropSyncCards by viewModel.dropSyncCards.collectAsStateWithLifecycle()
    val songs by viewModel.allSongs.collectAsStateWithLifecycle()
    val viewConfig by viewModel.viewConfig.collectAsStateWithLifecycle()
    val excluded by viewModel.excludedFolders.collectAsStateWithLifecycle()
    val allFolders by viewModel.allFolderPaths.collectAsStateWithLifecycle()

    var showFolders by remember { mutableStateOf(false) }
    var showCategories by remember { mutableStateOf(false) }

    // UI-Befund 4.2.4: SAF-Ordnerscan + M3U-Import (Datenschicht existierte
    // bereits, es fehlte nur der Einstieg). Ergebnisse zeigt der zentrale
    // Snackbar-Host von [LibraryContent] ueber die ViewModel-Flows.
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val safFolderPicker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) {
                // Persistente Leserechte fuer spaetere Re-Scans.
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
                viewModel.scanSafFolder(uri.toString())
            }
        }
    val m3uFilePicker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                scope.launch {
                    val text =
                        runCatching {
                            context.contentResolver
                                .openInputStream(uri)
                                ?.bufferedReader()
                                ?.use { it.readText() }
                        }.getOrNull()
                    val name = uri.lastPathSegment?.substringAfterLast('/')?.removeSuffix(".m3u")
                    if (text.isNullOrBlank() || name.isNullOrBlank()) {
                        viewModel.onM3uReadFailed()
                    } else {
                        viewModel.importM3u(name, text)
                    }
                }
            }
        }

    val hidden = viewConfig?.hiddenKeys ?: emptySet()
    val visibleCategories = LibraryCategory.entries.filter { it.key !in hidden }

    // C4 (U-3): Bestaetigen/Verwerfen wird quittiert und ist umkehrbar.
    val confirmedText = stringResource(R.string.library_marker_confirmed)
    val discardedText = stringResource(R.string.library_marker_discarded)
    val undoText = stringResource(R.string.library_undo)
    LaunchedEffect(viewModel) {
        viewModel.markerReviewFeedback.collect { action ->
            val result =
                snackbarHostState.showSnackbar(
                    message = if (action == MarkerReviewAction.CONFIRMED) confirmedText else discardedText,
                    actionLabel = undoText,
                    withDismissAction = true,
                )
            if (result == SnackbarResult.ActionPerformed) viewModel.undoMarkerReview()
        }
    }

    LibraryHomeScreen(
        categories = visibleCategories,
        queueCount = queue.size,
        playbackState = playbackState,
        pendingMarkers = pendingMarkers,
        dropSyncCards = dropSyncCards,
        songs = songs,
        contentPadding = contentPadding,
        onOpen = onOpen,
        onOpenNowPlaying = onOpenNowPlaying,
        onOpenPlaylist = onOpenPlaylist,
        onUseDropSync = viewModel::useWithDropSync,
        onDetectDrops = viewModel::detectDropsForPlaylist,
        onPreviewMarker = viewModel::previewMarker,
        onConfirmMarker = viewModel::confirmMarker,
        onDiscardMarker = viewModel::discardMarker,
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
            // UI-Befund 4.2.4: SAF/M3U-Einstieg aus dem Ordnerdialog.
            onScanSafFolder = {
                showFolders = false
                safFolderPicker.launch(null)
            },
            onImportM3u = {
                showFolders = false
                m3uFilePicker.launch(
                    arrayOf("audio/x-mpegurl", "audio/x-m3u", "text/plain", "application/octet-stream"),
                )
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
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
) {
    // B4: Undo-Texte und Scope im Composable-Kontext.
    val scope = rememberCoroutineScope()
    val playlistDeletedText = stringResource(R.string.library_playlist_deleted)
    val undoText = stringResource(R.string.library_undo)
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
                // C12 (U-10): eine gemeinsame Kopfzeile statt Eigenbau.
                FlowRepTopBar(
                    title = stringResource(category.titleRes()),
                    onBack = onBack,
                    backContentDescription = stringResource(R.string.library_back),
                )
                CategoryHeader(
                    iconRes = categoryIcon(category),
                    title = stringResource(category.titleRes()),
                    subtitle = null,
                )
                PlaylistList(
                    playlists = playlists,
                    contentPadding = contentPadding,
                    onOpen = onOpenPlaylist,
                    onCreate = viewModel::createPlaylist,
                    onRename = viewModel::renamePlaylist,
                    // B4: Loeschen mit Undo statt unwiderruflich (suspend:
                    // erst danach ist hasPlaylistUndo() aussagekraeftig).
                    onDelete = { playlistId ->
                        scope.launch {
                            viewModel.deletePlaylist(playlistId)
                            scope.showLibraryUndoSnackbar(
                                host = snackbarHostState,
                                message = playlistDeletedText,
                                actionLabel = undoText,
                                hasUndo = viewModel.hasPlaylistUndo(),
                                onUndo = viewModel::undoDeletePlaylist,
                            )
                        }
                    },
                )
            }
        }
    }
}

/**
 * B4: Undo-Snackbar — Aufrufer loesen Texte und Scope im Composable-Kontext
 * auf und reichen den App-Host durch.
 */
private fun CoroutineScope.showLibraryUndoSnackbar(
    host: SnackbarHostState,
    message: String,
    actionLabel: String,
    hasUndo: Boolean,
    onUndo: () -> Unit,
) {
    if (!hasUndo) return
    launch {
        val result =
            host.showSnackbar(
                message = message,
                actionLabel = actionLabel,
                withDismissAction = true,
            )
        if (result == SnackbarResult.ActionPerformed) onUndo()
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
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
) {
    // Paket 4.16: openPlaylist setzt nur Auswahlzustand — SideEffect mit Key
    // reicht und spart die Coroutine.
    SideEffect(playlistId) { viewModel.openPlaylist(playlistId) }
    val playlist by viewModel.openPlaylist.collectAsStateWithLifecycle()
    val songs by viewModel.playlistSongs.collectAsStateWithLifecycle()
    // C4 (U-2): Abdeckung ("9/12 Drops") auch in der Detailansicht.
    val coverage by
        remember(playlistId) { viewModel.dropCoverage(playlistId) }
            .collectAsStateWithLifecycle(initialValue = null)
    // B4: Undo-Texte und Scope im Composable-Kontext.
    val scope = rememberCoroutineScope()
    val entryRemovedText = stringResource(R.string.library_playlist_entry_removed)
    val undoText = stringResource(R.string.library_undo)
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
            onRemove = { position ->
                // B4: Entfernen mit Undo (Song-ID fuer das Wiederanhaengen).
                songs.getOrNull(position)?.let { song ->
                    viewModel.removeFromPlaylist(pl.id, position, song.mediaStoreId)
                    scope.showLibraryUndoSnackbar(
                        host = snackbarHostState,
                        message = entryRemovedText,
                        actionLabel = undoText,
                        hasUndo = viewModel.hasPlaylistEntryUndo(),
                        onUndo = viewModel::undoRemoveFromPlaylist,
                    )
                }
            },
            onMove = { from, to -> viewModel.moveInPlaylist(pl.id, from, to) },
            onSetLabel = { label -> viewModel.setPlaylistLabel(pl.id, label) },
            dropCoverage = coverage,
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
