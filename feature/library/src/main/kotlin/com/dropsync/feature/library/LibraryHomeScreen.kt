package com.dropsync.feature.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import com.dropsync.core.designsystem.component.FlowRepSectionHeader
import com.dropsync.core.designsystem.component.FlowRepSurface
import com.dropsync.core.designsystem.icon.BrandIcons
import com.dropsync.core.designsystem.theme.CategoryTints
import com.dropsync.core.designsystem.theme.Spacing
import com.dropsync.core.designsystem.theme.rememberAccentTextColor
import com.dropsync.core.model.Song
import com.dropsync.core.model.SongMarker
import com.dropsync.domain.playback.PlaybackState
import java.util.Locale

/**
 * Music ist ein lokaler Einstieg in die aktuelle Hoersituation. Wiederkehrende
 * Aufgaben stehen vor der vollstaendigen Bibliothek; die darunterliegende
 * Kategorienliste bleibt der Drill-down fuer die Offline-Mediathek.
 */
@Composable
internal fun LibraryHomeScreen(
    categories: List<LibraryCategory>,
    queueCount: Int,
    playbackState: PlaybackState,
    pendingMarkers: List<SongMarker>,
    dropSyncCards: List<DropSyncCard>,
    songs: List<Song>,
    contentPadding: PaddingValues,
    onOpen: (LibraryCategory) -> Unit,
    onOpenNowPlaying: () -> Unit,
    onOpenPlaylist: (Long) -> Unit,
    onUseDropSync: (Long) -> Unit,
    onDetectDrops: (Long) -> Unit,
    onPreviewMarker: (SongMarker) -> Unit,
    onConfirmMarker: (Long) -> Unit,
    onDiscardMarker: (Long) -> Unit,
    onRescan: () -> Unit,
    onSelectFolders: () -> Unit,
    onEditCategories: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val featured =
        listOf(
            LibraryCategory.PLAYLISTS,
            LibraryCategory.QUEUE,
        ).filter { it in categories }
    val quickAccess =
        listOf(
            LibraryCategory.FAVORITES,
            LibraryCategory.RECENTLY_PLAYED,
        ).filter { it in categories }
    val libraryCategories = categories.filterNot { it in quickAccess || it in featured }
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding =
            PaddingValues(
                top = contentPadding.calculateTopPadding(),
                bottom = contentPadding.calculateBottomPadding() + 32.dp,
            ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.library_title),
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.weight(1f),
                )
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(
                            painterResource(BrandIcons.More),
                            contentDescription = stringResource(R.string.library_more_actions),
                        )
                    }
                    DropdownMenu(
                        expanded = menuOpen,
                        onDismissRequest = { menuOpen = false },
                        // Rechtsbuendig unter die drei Punkte: Offset aus der
                        // Breite des laengsten Menue-Labels (7.2) statt eines
                        // hartcodierten Werts, der bei anderen Sprachen bricht.
                        offset =
                            DpOffset(
                                x = -with(LocalDensity.current) {
                                    stringResource(R.string.library_select_folders)
                                        .textWidthDp()
                                        .coerceAtLeast(136.dp)
                                },
                                y = 0.dp,
                            ),
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.library_select_folders)) },
                            onClick = {
                                onSelectFolders()
                                menuOpen = false
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.library_rescan)) },
                            onClick = {
                                onRescan()
                                menuOpen = false
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.library_categories)) },
                            onClick = {
                                onEditCategories()
                                menuOpen = false
                            },
                        )
                    }
                }
            }
        }
        if (featured.isNotEmpty()) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    featured.forEach { category ->
                        val supporting =
                            if (category == LibraryCategory.QUEUE) {
                                if (queueCount == 0) {
                                    stringResource(R.string.library_queue_empty)
                                } else {
                                    stringResource(R.string.library_queue_ready, queueCount)
                                }
                            } else {
                                stringResource(R.string.library_featured_training)
                            }
                        FeaturedMusicCard(
                            category = category,
                            supporting = supporting,
                            onClick = { onOpen(category) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
        if (playbackState.currentIndex in playbackState.queue.indices) {
            item {
                val current = playbackState.queue[playbackState.currentIndex]
                NowPlayingCard(
                    title = current.title,
                    artist = current.artist,
                    isPlaying = playbackState.isPlaying,
                    onClick = onOpenNowPlaying,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
        }
        if (dropSyncCards.isNotEmpty()) {
            item {
                DropSyncSection(
                    cards = dropSyncCards,
                    onOpenPlaylist = onOpenPlaylist,
                    onUseDropSync = onUseDropSync,
                    onDetectDrops = onDetectDrops,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
        }
        if (pendingMarkers.isNotEmpty()) {
            item {
                MarkerReviewSection(
                    markers = pendingMarkers,
                    songs = songs,
                    onConfirm = onConfirmMarker,
                    onDiscard = onDiscardMarker,
                    onPreview = onPreviewMarker,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
        }
        if (quickAccess.isNotEmpty()) {
            item {
                Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                    FlowRepSectionHeader(
                        title = stringResource(R.string.library_section_for_now),
                        modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
                    )
                    quickAccess.forEach { category ->
                        CategoryRow(
                            category = category,
                            hint = null,
                            onClick = { onOpen(category) },
                        )
                    }
                }
            }
        }
        if (libraryCategories.isNotEmpty()) {
            item {
                Column(modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
                    FlowRepSectionHeader(
                        title = stringResource(R.string.library_section_library),
                        modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
                    )
                    libraryCategories.forEach { category ->
                        CategoryRow(
                            category = category,
                            hint = null,
                            onClick = { onOpen(category) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun NowPlayingCard(
    title: String,
    artist: String?,
    isPlaying: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        // C1: System-Radius statt Literal.
        shape = RoundedCornerShape(Spacing.radiusCard),
        color = MaterialTheme.colorScheme.primaryContainer,
    ) {
        Row(
            modifier = Modifier.padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier =
                    Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(if (isPlaying) BrandIcons.Waveform else BrandIcons.Play),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(28.dp),
                )
            }
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.library_now_playing),
                    style = MaterialTheme.typography.labelMedium,
                    color = rememberAccentTextColor(),
                )
                Text(text = title, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                Text(
                    text = artist ?: stringResource(R.string.library_unknown_artist),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            Icon(
                painter = painterResource(BrandIcons.Play),
                contentDescription = stringResource(R.string.library_open_now_playing),
                tint = rememberAccentTextColor(),
            )
        }
    }
}

@Composable
internal fun MarkerReviewSection(
    markers: List<SongMarker>,
    songs: List<Song>,
    onConfirm: (Long) -> Unit,
    onDiscard: (Long) -> Unit,
    onPreview: (SongMarker) -> Unit,
    modifier: Modifier = Modifier,
) {
    FlowRepSurface(modifier = modifier) {
        FlowRepSectionHeader(title = stringResource(R.string.library_marker_review_title))
        Text(
            text = stringResource(R.string.library_marker_review_count, markers.size),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
        )
        markers.forEach { marker ->
            val song = songs.firstOrNull { it.mediaStoreId == marker.linkedSongId }
            Column(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = song?.title ?: song?.displayName ?: stringResource(R.string.library_unknown_track),
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                )
                Text(
                    text = stringResource(R.string.library_marker_review_position, formatMarkerTime(marker.positionMs)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    // C4 (U-3): erst hoeren, dann entscheiden.
                    TextButton(onClick = { onPreview(marker) }) {
                        Text(stringResource(R.string.library_marker_listen))
                    }
                    TextButton(onClick = { onDiscard(marker.id) }) {
                        Text(stringResource(R.string.library_marker_discard))
                    }
                    TextButton(onClick = { onConfirm(marker.id) }) {
                        Text(stringResource(R.string.library_marker_confirm))
                    }
                }
            }
        }
    }
}

/**
 * C4 (U-2): Work-/Rest-Einstiege direkt auf Music Home. Jede Karte nennt
 * die Drop-Abdeckung, offene Kandidaten und bietet "DropSync verwenden";
 * ohne Marker fuehrt ein CTA direkt in die Erkennung.
 */
@Composable
internal fun DropSyncSection(
    cards: List<DropSyncCard>,
    onOpenPlaylist: (Long) -> Unit,
    onUseDropSync: (Long) -> Unit,
    onDetectDrops: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FlowRepSectionHeader(
            title = stringResource(R.string.library_dropsync_section),
            modifier = Modifier.padding(start = 4.dp),
        )
        cards.forEach { card ->
            DropSyncCardItem(
                card = card,
                onOpen = { onOpenPlaylist(card.playlistId) },
                onUse = { onUseDropSync(card.playlistId) },
                onDetect = { onDetectDrops(card.playlistId) },
            )
        }
    }
}

@Composable
private fun DropSyncCardItem(
    card: DropSyncCard,
    onOpen: () -> Unit,
    onUse: () -> Unit,
    onDetect: () -> Unit,
) {
    Surface(
        onClick = onOpen,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(Spacing.radiusCard),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(card.label.labelRes()),
                    style = MaterialTheme.typography.labelMedium,
                    color = rememberAccentTextColor(),
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = card.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                )
            }
            if (card.coverage.totalSongs == 0) {
                Text(
                    text = stringResource(R.string.library_dropsync_no_songs),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else if (card.coverage.songsWithDrop == 0) {
                Text(
                    text = stringResource(R.string.library_dropsync_no_markers),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = onDetect) {
                    Text(stringResource(R.string.library_dropsync_detect))
                }
            } else {
                Text(
                    text =
                        stringResource(
                            R.string.library_dropsync_coverage,
                            card.coverage.songsWithDrop,
                            card.coverage.totalSongs,
                        ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (card.coverage.pendingReviews > 0) {
                    Text(
                        text =
                            stringResource(
                                R.string.library_dropsync_pending,
                                card.coverage.pendingReviews,
                            ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = onUse, enabled = card.coverage.totalSongs > 0) {
                    Text(stringResource(R.string.library_dropsync_use))
                }
            }
        }
    }
}

private fun formatMarkerTime(positionMs: Long): String {
    val seconds = positionMs.coerceAtLeast(0L) / 1000
    return String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60)
}

@Composable
private fun FeaturedMusicCard(
    category: LibraryCategory,
    supporting: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tint = categoryTint(category)
    Surface(
        onClick = onClick,
        modifier = modifier.aspectRatio(1.08f),
        // C1: System-Radius statt Literal.
        shape = RoundedCornerShape(Spacing.radiusMedium),
        color =
            if (category ==
                LibraryCategory.PLAYLISTS
            ) {
                tint.copy(alpha = 0.18f)
            } else {
                MaterialTheme.colorScheme.surfaceContainer
            },
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Icon(
                painter = painterResource(categoryIcon(category)),
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(30.dp),
            )
            Column {
                Text(
                    text = stringResource(category.titleRes()),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = supporting,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun CategoryRow(
    category: LibraryCategory,
    hint: String?,
    onClick: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 4.dp, vertical = 8.dp)
                .heightIn(min = 64.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val tint = categoryTint(category)
        Box(
            modifier =
                Modifier
                    .size(48.dp)
                    // C1: System-Radius statt Literal.
                    .clip(RoundedCornerShape(Spacing.radiusSmall))
                    .background(tint.copy(alpha = 0.18f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painterResource(categoryIcon(category)),
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(24.dp),
            )
        }
        Spacer(Modifier.width(16.dp))
        Text(
            text = stringResource(category.titleRes()),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f),
        )
        if (hint != null) {
            Text(
                text = hint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Marken-Icon je Kategorie. */
internal fun categoryIcon(category: LibraryCategory): Int =
    when (category) {
        LibraryCategory.ALL_SONGS -> BrandIcons.NavMusic
        LibraryCategory.FOLDERS -> BrandIcons.Folder
        LibraryCategory.FOLDERS_HIERARCHY -> BrandIcons.Folder
        LibraryCategory.ALBUMS -> BrandIcons.Albums
        LibraryCategory.ARTISTS -> BrandIcons.Artists
        LibraryCategory.GENRES -> BrandIcons.Genres
        LibraryCategory.PLAYLISTS -> BrandIcons.Playlists
        LibraryCategory.QUEUE -> BrandIcons.Queue
        LibraryCategory.FAVORITES -> BrandIcons.FavoriteFilled
        LibraryCategory.RECENTLY_ADDED -> BrandIcons.Add
        LibraryCategory.RECENTLY_PLAYED -> BrandIcons.Replay15
        LibraryCategory.MOST_PLAYED -> BrandIcons.Progress
    }

/** Semantischer Farbton des Kategorie-Icons; jede Kategorie hat eine eigene Farbe. */
@Composable
private fun categoryTint(category: LibraryCategory): Color {
    val base =
        when (category) {
            LibraryCategory.ALL_SONGS -> CategoryTints.lime
            LibraryCategory.FOLDERS -> CategoryTints.orange
            LibraryCategory.FOLDERS_HIERARCHY -> CategoryTints.amber
            LibraryCategory.ALBUMS -> CategoryTints.blue
            LibraryCategory.ARTISTS -> CategoryTints.purple
            LibraryCategory.GENRES -> CategoryTints.pink
            LibraryCategory.PLAYLISTS -> CategoryTints.green
            LibraryCategory.QUEUE -> CategoryTints.cyan
            LibraryCategory.FAVORITES -> CategoryTints.red
            LibraryCategory.RECENTLY_ADDED -> CategoryTints.teal
            LibraryCategory.RECENTLY_PLAYED -> CategoryTints.deepOrange
            LibraryCategory.MOST_PLAYED -> CategoryTints.indigo
        }
    // C1: Die Pastell-Palette ist Dark-first — im hellen Modus zum Schwarz
    // hin skalieren, damit der Kontrast auf hellen Tiles erhalten bleibt.
    // Der Farbton bleibt erkennbar, nur die Helligkeit sinkt.
    return if (isSystemInDarkTheme()) {
        base
    } else {
        base.copy(
            red = base.red * LIGHT_TINT_SCALE,
            green = base.green * LIGHT_TINT_SCALE,
            blue = base.blue * LIGHT_TINT_SCALE,
        )
    }
}

/** Abdunklungsfaktor der Kategorie-Farben im hellen Modus (C1). */
private const val LIGHT_TINT_SCALE: Float = 0.72f

/**
 * Breite eines Strings in dp (7.2): Grundlage fuer den rechtsbuendigen
 * Dropdown-Offset. `ceil` rundet auf, damit das Menue nie in den Button
 * ragt.
 */
@Composable
internal fun String.textWidthDp(): Dp {
    val measurer = rememberTextMeasurer()
    val widthPx =
        measurer.measure(
            text = this,
            style = MaterialTheme.typography.bodyMedium,
        ).size.width
    return with(LocalDensity.current) { kotlin.math.ceil(widthPx.toDp().value).dp }
}

/** Angezeigter Kategorie-Name. */
internal fun LibraryCategory.titleRes(): Int =
    when (this) {
        LibraryCategory.ALL_SONGS -> R.string.library_cat_all_songs
        LibraryCategory.FOLDERS -> R.string.library_cat_folders
        LibraryCategory.FOLDERS_HIERARCHY -> R.string.library_cat_folders_hierarchy
        LibraryCategory.ALBUMS -> R.string.library_cat_albums
        LibraryCategory.ARTISTS -> R.string.library_cat_artists
        LibraryCategory.GENRES -> R.string.library_cat_genres
        LibraryCategory.PLAYLISTS -> R.string.library_cat_playlists
        LibraryCategory.QUEUE -> R.string.library_cat_queue
        LibraryCategory.FAVORITES -> R.string.library_cat_favorites
        LibraryCategory.RECENTLY_ADDED -> R.string.library_cat_recently_added
        LibraryCategory.RECENTLY_PLAYED -> R.string.library_cat_recently_played
        LibraryCategory.MOST_PLAYED -> R.string.library_cat_most_played
    }
