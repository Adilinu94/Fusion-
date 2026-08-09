package com.dropsync.feature.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.dropsync.core.designsystem.icon.BrandIcons

/**
 * Poweramp-artige Bibliotheks-Startseite (Umbau): Titelzeile mit Ueberlaufmenue
 * (Neu scannen) und eine vertikale Liste aller Kategorien mit rundem, getoentem
 * Marken-Icon. Die Warteschlange zeigt "leer", solange sie leer ist.
 */
@Composable
internal fun LibraryHomeScreen(
    categories: List<LibraryCategory>,
    queueCount: Int,
    contentPadding: PaddingValues,
    onOpen: (LibraryCategory) -> Unit,
    onRescan: () -> Unit,
    onSelectFolders: () -> Unit,
    onEditCategories: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var menuOpen by remember { mutableStateOf(false) }
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = contentPadding,
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
                IconButton(onClick = { menuOpen = true }) {
                    Icon(
                        painterResource(BrandIcons.More),
                        contentDescription = stringResource(R.string.library_more_actions),
                    )
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
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
        items(categories, key = { it.key }) { category ->
            CategoryRow(
                category = category,
                hint =
                    if (category == LibraryCategory.QUEUE && queueCount == 0) {
                        stringResource(R.string.library_queue_empty)
                    } else {
                        null
                    },
                onClick = { onOpen(category) },
            )
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
                .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val tint = categoryTint(category)
        Box(
            modifier =
                Modifier
                    .size(48.dp)
                    .clip(CircleShape)
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
        Spacer(Modifier.width(20.dp))
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

/** Farbton des Kategorie-Icons (Lime dominiert, dezente Akzenttoene). */
private fun categoryTint(category: LibraryCategory): Color =
    when (category) {
        LibraryCategory.ALL_SONGS -> Color(0xFFEA6A4F)
        LibraryCategory.FOLDERS -> Color(0xFFEA8A3F)
        LibraryCategory.FOLDERS_HIERARCHY -> Color(0xFFEAB03F)
        LibraryCategory.ALBUMS -> Color(0xFFEACB3F)
        LibraryCategory.ARTISTS -> Color(0xFF9BAA5A)
        LibraryCategory.GENRES -> Color(0xFF3FBF8A)
        LibraryCategory.PLAYLISTS -> Color(0xFF4F7BEA)
        LibraryCategory.QUEUE -> Color(0xFF7B6BEA)
        LibraryCategory.FAVORITES -> Color(0xFFEA5A9B)
        LibraryCategory.RECENTLY_ADDED -> Color(0xFFEA8A3F)
        LibraryCategory.RECENTLY_PLAYED -> Color(0xFFEA6A4F)
        LibraryCategory.MOST_PLAYED -> Color(0xFFEA5A6B)
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
