package com.dropsync.feature.library

import com.dropsync.core.model.Song
import com.dropsync.domain.library.LibraryListConfig
import com.dropsync.domain.library.SongPlayStat
import com.dropsync.domain.library.SongSort

/**
 * Bibliothekskategorien der Poweramp-artigen Startseite. Reihenfolge = Anzeige
 * auf der Startseite (Poweramp-Umbau). Jede Kategorie kennt ihren stabilen
 * Schluessel (fuer die Navigation und die persistierten Listen-Optionen).
 */
enum class LibraryCategory(
    val key: String,
) {
    ALL_SONGS("all_songs"),
    FOLDERS("folders"),
    FOLDERS_HIERARCHY("folders_hierarchy"),
    ALBUMS("albums"),
    ARTISTS("artists"),
    GENRES("genres"),
    PLAYLISTS("playlists"),
    QUEUE("queue"),
    FAVORITES("favorites"),
    RECENTLY_ADDED("recently_added"),
    RECENTLY_PLAYED("recently_played"),
    MOST_PLAYED("most_played"),
    ;

    companion object {
        fun fromKey(key: String?): LibraryCategory? = entries.firstOrNull { it.key == key }
    }
}

/**
 * Ansichtsmodus einer Liste (Poweramp "View As", reduziert auf die im
 * FlowRep-Design sinnvollen Varianten).
 */
enum class LibraryViewMode {
    LIST,
    LIST_COMPACT,
    GRID_SMALL,
    GRID,
}

/** Aktuelle Listen-Optionen einer Kategorie im UI (Poweramp "List Options"). */
data class CategoryListConfig(
    val sort: SongSort,
    val descending: Boolean,
    val viewMode: LibraryViewMode,
)

/** Standardoptionen je Kategorie (nach Titel aufsteigend, Liste; Sammlungen als Raster). */
fun defaultListConfig(category: LibraryCategory): CategoryListConfig =
    when (category) {
        LibraryCategory.ALBUMS, LibraryCategory.ARTISTS, LibraryCategory.GENRES -> {
            CategoryListConfig(SongSort.TITLE, descending = false, viewMode = LibraryViewMode.GRID)
        }

        LibraryCategory.RECENTLY_ADDED -> {
            CategoryListConfig(SongSort.DATE_ADDED, descending = true, viewMode = LibraryViewMode.LIST)
        }

        LibraryCategory.RECENTLY_PLAYED -> {
            CategoryListConfig(SongSort.LAST_PLAYED, descending = true, viewMode = LibraryViewMode.LIST)
        }

        LibraryCategory.MOST_PLAYED -> {
            CategoryListConfig(SongSort.PLAY_COUNT, descending = true, viewMode = LibraryViewMode.LIST)
        }

        else -> {
            CategoryListConfig(SongSort.TITLE, descending = false, viewMode = LibraryViewMode.LIST)
        }
    }

/** Liest die persistierte Konfiguration in das UI-Modell; unbekannte Schluessel fallen weg. */
fun LibraryListConfig.toUi(): CategoryListConfig? {
    val sort = runCatching { SongSort.valueOf(sortKey) }.getOrNull() ?: return null
    val mode = runCatching { LibraryViewMode.valueOf(viewModeKey) }.getOrNull() ?: return null
    return CategoryListConfig(sort = sort, descending = descending, viewMode = mode)
}

/**
 * Baustein fuer die Titel-Sortierung (Poweramp-Umbau). Kapselt die
 * Comparator-Erzeugung, damit sie unabhaengig von Compose testbar bleibt.
 * [stats] liefert die Wiedergabestatistik je Titel-ID; fehlt sie, gilt der
 * Titel als nie gespielt (Zaehler 0, kein Zeitpunkt).
 */
object LibrarySortEngine {
    /** Sortierarten, die fuer reine Titellisten (Alle Titel) angeboten werden. */
    val songSorts: List<SongSort> =
        listOf(
            SongSort.TITLE,
            SongSort.FILENAME,
            SongSort.PATH,
            SongSort.ARTIST,
            SongSort.ALBUM,
            SongSort.DURATION,
            SongSort.DATE_ADDED,
            SongSort.LAST_PLAYED,
            SongSort.PLAY_COUNT,
        )

    fun sort(
        songs: List<Song>,
        sort: SongSort,
        descending: Boolean,
        stats: Map<Long, SongPlayStat> = emptyMap(),
    ): List<Song> {
        val base = songs.sortedWith(comparatorFor(sort, stats))
        return if (descending) base.asReversed() else base
    }

    private fun comparatorFor(
        sort: SongSort,
        stats: Map<Long, SongPlayStat>,
    ): Comparator<Song> =
        when (sort) {
            SongSort.TITLE -> {
                compareBy(String.CASE_INSENSITIVE_ORDER) { it.title ?: it.displayName }
            }

            SongSort.FILENAME -> {
                compareBy(String.CASE_INSENSITIVE_ORDER) { it.displayName }
            }

            SongSort.PATH -> {
                compareBy(String.CASE_INSENSITIVE_ORDER) { it.relativePath + "/" + it.displayName }
            }

            SongSort.ARTIST -> {
                compareBy(String.CASE_INSENSITIVE_ORDER) { it.artist ?: "" }
            }

            SongSort.ALBUM -> {
                compareBy(String.CASE_INSENSITIVE_ORDER) { it.album ?: "" }
            }

            SongSort.DURATION -> {
                compareBy { it.durationMs }
            }

            SongSort.DATE_ADDED -> {
                compareBy { it.dateModifiedSeconds }
            }

            SongSort.LAST_PLAYED -> {
                compareBy { stats[it.mediaStoreId]?.lastPlayedAtEpochMs ?: Long.MIN_VALUE }
            }

            SongSort.PLAY_COUNT -> {
                compareBy { stats[it.mediaStoreId]?.playCount ?: 0 }
            }
        }
}
