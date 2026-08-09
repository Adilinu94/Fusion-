package com.dropsync.feature.library

import com.dropsync.domain.library.LibraryListConfig
import com.dropsync.domain.library.SongSort
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Listen-Optionen je Kategorie (Plan-Testplan: Prefs-Roundtrip). Prueft die
 * Standardwerte je Kategorie und die verlustfreie Umwandlung zwischen dem
 * persistierten [LibraryListConfig] und dem UI-Modell [CategoryListConfig].
 */
class CategoryListConfigTest {
    @Test
    fun defaultsMatchPowerampCategoryExpectations() {
        assertEquals(
            CategoryListConfig(SongSort.TITLE, descending = false, viewMode = LibraryViewMode.LIST),
            defaultListConfig(LibraryCategory.ALL_SONGS),
        )
        assertEquals(
            CategoryListConfig(SongSort.TITLE, descending = false, viewMode = LibraryViewMode.GRID),
            defaultListConfig(LibraryCategory.ALBUMS),
        )
        assertEquals(
            CategoryListConfig(SongSort.DATE_ADDED, descending = true, viewMode = LibraryViewMode.LIST),
            defaultListConfig(LibraryCategory.RECENTLY_ADDED),
        )
        assertEquals(
            CategoryListConfig(SongSort.LAST_PLAYED, descending = true, viewMode = LibraryViewMode.LIST),
            defaultListConfig(LibraryCategory.RECENTLY_PLAYED),
        )
        assertEquals(
            CategoryListConfig(SongSort.PLAY_COUNT, descending = true, viewMode = LibraryViewMode.LIST),
            defaultListConfig(LibraryCategory.MOST_PLAYED),
        )
    }

    @Test
    fun storedConfigRoundTripsThroughUiModel() {
        val ui = CategoryListConfig(SongSort.PLAY_COUNT, descending = true, viewMode = LibraryViewMode.GRID_SMALL)
        val stored =
            LibraryListConfig(
                sortKey = ui.sort.name,
                descending = ui.descending,
                viewModeKey = ui.viewMode.name,
            )
        assertEquals(ui, stored.toUi())
    }

    @Test
    fun unknownKeysMapToNullSoDefaultsApply() {
        assertNull(LibraryListConfig(sortKey = "NOPE", descending = false, viewModeKey = "LIST").toUi())
        assertNull(LibraryListConfig(sortKey = "TITLE", descending = false, viewModeKey = "NOPE").toUi())
    }

    @Test
    fun categoryKeysAreStableAndUnique() {
        val keys = LibraryCategory.entries.map { it.key }
        assertEquals(keys.size, keys.toSet().size)
        assertEquals(LibraryCategory.ALL_SONGS, LibraryCategory.fromKey("all_songs"))
        assertNull(LibraryCategory.fromKey("does_not_exist"))
    }
}
