package com.dropsync.feature.library

import com.dropsync.core.model.Song
import com.dropsync.domain.library.SongPlayStat
import com.dropsync.domain.library.SongSort
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Sortierlogik der Poweramp-Titellisten (Plan-Testplan: alle Sortierarten,
 * auf-/absteigend). Reine JVM-Tests ueber [LibrarySortEngine].
 */
class LibrarySortEngineTest {
    // id=1 Beta, id=2 Alpha, id=3 Gamma; Felder so gewaehlt, dass jede
    // Sortierart eine eindeutige Reihenfolge ergibt.
    private val song1 =
        song(
            id = 1,
            title = "Beta",
            displayName = "file_b.mp3",
            path = "B",
            duration = 200,
            date = 200,
            artist = "Beta Artist",
            album = "Beta Album",
        )
    private val song2 =
        song(
            id = 2,
            title = "Alpha",
            displayName = "file_a.mp3",
            path = "A",
            duration = 300,
            date = 100,
            artist = "Alpha Artist",
            album = "Alpha Album",
        )
    private val song3 =
        song(
            id = 3,
            title = "Gamma",
            displayName = "file_c.mp3",
            path = "C",
            duration = 100,
            date = 300,
            artist = "Gamma Artist",
            album = "Gamma Album",
        )

    private val songs = listOf(song1, song2, song3)

    private val stats =
        mapOf(
            1L to SongPlayStat(songId = 1, playCount = 5, lastPlayedAtEpochMs = 1_000L),
            3L to SongPlayStat(songId = 3, playCount = 2, lastPlayedAtEpochMs = 5_000L),
        )

    private fun ids(
        sort: SongSort,
        descending: Boolean = false,
    ): List<Long> = LibrarySortEngine.sort(songs, sort, descending, stats).map { it.mediaStoreId }

    @Test
    fun songSortsListsAllNineOptions() {
        assertEquals(SongSort.entries.size, LibrarySortEngine.songSorts.size)
        assertEquals(SongSort.entries.toSet(), LibrarySortEngine.songSorts.toSet())
    }

    @Test
    fun titleSortsAscendingAndDescending() {
        assertEquals(listOf(2L, 1L, 3L), ids(SongSort.TITLE))
        assertEquals(listOf(3L, 1L, 2L), ids(SongSort.TITLE, descending = true))
    }

    @Test
    fun titleFallsBackToDisplayNameWhenNull() {
        val untitled =
            song(
                id = 9,
                title = null,
                displayName = "AAA.mp3",
                path = "Z",
                duration = 1,
                date = 1,
                artist = "x",
                album = "x",
            )
        val sorted = LibrarySortEngine.sort(listOf(song1, untitled), SongSort.TITLE, descending = false)
        // "AAA.mp3" < "Beta" case-insensitive -> untitled first.
        assertEquals(listOf(9L, 1L), sorted.map { it.mediaStoreId })
    }

    @Test
    fun filenameSortsByDisplayName() {
        assertEquals(listOf(2L, 1L, 3L), ids(SongSort.FILENAME))
    }

    @Test
    fun pathSortsByRelativePathThenFilename() {
        assertEquals(listOf(2L, 1L, 3L), ids(SongSort.PATH))
    }

    @Test
    fun artistAndAlbumSortAlphabetically() {
        assertEquals(listOf(2L, 1L, 3L), ids(SongSort.ARTIST))
        assertEquals(listOf(2L, 1L, 3L), ids(SongSort.ALBUM))
    }

    @Test
    fun durationSortsByLength() {
        assertEquals(listOf(3L, 1L, 2L), ids(SongSort.DURATION))
        assertEquals(listOf(2L, 1L, 3L), ids(SongSort.DURATION, descending = true))
    }

    @Test
    fun dateAddedSortsByModifiedSeconds() {
        assertEquals(listOf(2L, 1L, 3L), ids(SongSort.DATE_ADDED))
    }

    @Test
    fun lastPlayedTreatsMissingStatAsNeverPlayed() {
        // song2 hat keine Statistik -> gilt als nie gespielt (zuerst aufsteigend).
        assertEquals(listOf(2L, 1L, 3L), ids(SongSort.LAST_PLAYED))
        assertEquals(listOf(3L, 1L, 2L), ids(SongSort.LAST_PLAYED, descending = true))
    }

    @Test
    fun playCountTreatsMissingStatAsZero() {
        assertEquals(listOf(2L, 3L, 1L), ids(SongSort.PLAY_COUNT))
        assertEquals(listOf(1L, 3L, 2L), ids(SongSort.PLAY_COUNT, descending = true))
    }

    private fun song(
        id: Long,
        title: String?,
        displayName: String,
        path: String,
        duration: Long,
        date: Long,
        artist: String?,
        album: String?,
    ): Song =
        Song(
            mediaStoreId = id,
            contentUri = "content://media/$id",
            displayName = displayName,
            relativePath = path,
            durationMs = duration,
            sizeBytes = 0,
            dateModifiedSeconds = date,
            title = title,
            artist = artist,
            album = album,
            isAvailable = true,
        )
}
