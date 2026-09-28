package com.dropsync.data.library

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dropsync.core.common.AppResult
import com.dropsync.core.database.DropSyncDatabase
import com.dropsync.core.database.RoomTransactionRunner
import com.dropsync.core.database.dao.PlayStatDao
import com.dropsync.core.database.dao.PlaylistDao
import com.dropsync.core.database.dao.PlaylistRow
import com.dropsync.core.database.entity.PlayStatEntity
import com.dropsync.core.database.entity.PlaylistEntity
import com.dropsync.core.database.entity.PlaylistItemEntity
import com.dropsync.core.database.entity.SongEntity
import com.dropsync.core.model.PlaylistLabel
import com.dropsync.core.testing.TestDispatcherProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Bibliotheksansichten, Statistik, Favoriten, Playlisten, Suche und
 * M3U-Import gegen eine echte In-Memory-Room-DB (Plan Phase 6).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class LibraryBrowseRepositoryTest {
    private lateinit var db: DropSyncDatabase
    private lateinit var repository: LibraryBrowseRepositoryImpl
    private var clock = 1_000L

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db =
            Room
                .inMemoryDatabaseBuilder(context, DropSyncDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        repository =
            LibraryBrowseRepositoryImpl(
                browseDao = db.libraryBrowseDao(),
                playStatDao = db.playStatDao(),
                favoriteDao = db.favoriteDao(),
                playlistDao = db.playlistDao(),
                songDao = db.songDao(),
                transactionRunner = RoomTransactionRunner(db),
                dispatchers = TestDispatcherProvider(),
                now = { clock },
            )
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun song(
        id: Long,
        title: String,
        artist: String,
        album: String,
        genre: String? = null,
        path: String = "Music",
        dateModified: Long = id,
    ) = SongEntity(
        mediaStoreId = id,
        contentUri = "content://media/$id",
        displayName = "$title.flac",
        relativePath = path,
        durationMs = 200_000,
        sizeBytes = 1_000,
        dateModifiedSeconds = dateModified,
        title = title,
        artist = artist,
        album = album,
        genre = genre,
        isAvailable = true,
        // 2026-09-27 (Befund 6.9): die gefalteten Spalten. Der Test
        // baut die Entity **direkt**, nicht ueber `Mappers.toEntity` —
        // ohne diese drei Zeilen waeren sie `NULL` und `searchFolded`
        // wuerde nichts finden, was als Fehler der Migration aussieht
        // und keiner ist.
        titleFolded = foldForSearch(title),
        artistFolded = foldForSearch(artist),
        albumFolded = foldForSearch(album),
    )

    @Test
    fun `alben kuenstler genres und ordner werden aggregiert`() =
        runTest {
            db.songDao().upsertAll(
                listOf(
                    song(1, "A", "Artist X", "Album 1", genre = "Rock", path = "Music/Rock"),
                    song(2, "B", "Artist X", "Album 1", genre = "Rock", path = "Music/Rock"),
                    song(3, "C", "Artist Y", "Album 2", genre = "Jazz", path = "Music/Jazz"),
                ),
            )

            val albums = repository.albums.first()
            assertEquals(2, albums.size)
            assertEquals(2, albums.first { it.title == "Album 1" }.trackCount)

            val artists = repository.artists.first()
            assertEquals(2, artists.size)
            assertEquals(2, artists.first { it.name == "Artist X" }.trackCount)
            assertEquals(1, artists.first { it.name == "Artist X" }.albumCount)

            assertEquals(2, repository.genres.first().size)
            assertEquals(2, repository.folders.first().size)
            assertEquals(2, repository.songsByAlbum("Album 1").first().size)
        }

    @Test
    fun `wiedergabestatistik speist zuletzt und meistgespielt`() =
        runTest {
            db.songDao().upsertAll(listOf(song(1, "A", "X", "Al"), song(2, "B", "X", "Al")))

            clock = 5_000L
            assertTrue(repository.recordPlayback(1) is AppResult.Success)
            assertTrue(repository.recordPlayback(1) is AppResult.Success)
            clock = 9_000L
            assertTrue(repository.recordPlayback(2) is AppResult.Success)

            val most = repository.mostPlayed().first()
            assertEquals(1L, most.first().mediaStoreId)
            // Zuletzt gespielt: Song 2 kam nach Song 1.
            val recent = repository.recentlyPlayed().first()
            assertEquals(2L, recent.first().mediaStoreId)
        }

    @Test
    fun `favoriten lassen sich setzen und wieder entfernen`() =
        runTest {
            db.songDao().upsertAll(listOf(song(1, "A", "X", "Al")))

            assertFalse(repository.isFavorite(1).first())
            repository.setFavorite(1, true)
            assertTrue(repository.isFavorite(1).first())
            assertEquals(1, repository.favorites.first().size)
            repository.setFavorite(1, false)
            assertFalse(repository.isFavorite(1).first())
            assertTrue(repository.favorites.first().isEmpty())
        }

    @Test
    fun `playlisten crud verschieben und entfernen haelt positionen lueckenlos`() =
        runTest {
            db.songDao().upsertAll(
                (1L..3L).map { song(it, "T$it", "X", "Al") },
            )
            val created = repository.createPlaylist("Meine Liste")
            val id = (created as AppResult.Success).value
            repository.addToPlaylist(id, listOf(1, 2, 3))

            assertEquals(listOf(1L, 2L, 3L), repository.songsOfPlaylist(id).first().map { it.mediaStoreId })
            assertEquals(
                3,
                repository.playlists
                    .first()
                    .first()
                    .trackCount,
            )

            // Erstes an letzte Stelle verschieben.
            repository.moveInPlaylist(id, fromPosition = 0, toPosition = 2)
            assertEquals(listOf(2L, 3L, 1L), repository.songsOfPlaylist(id).first().map { it.mediaStoreId })

            // Mittleres entfernen; Rest bleibt lueckenlos.
            repository.removeFromPlaylist(id, position = 1)
            assertEquals(listOf(2L, 1L), repository.songsOfPlaylist(id).first().map { it.mediaStoreId })

            // Doppelter Name wird abgelehnt.
            assertTrue(repository.createPlaylist("Meine Liste") is AppResult.Failure)
        }

    @Test
    fun `addToPlaylist ueberspringt bereits enthaltene titel und meldet die anzahl`() =
        runTest {
            db.songDao().upsertAll((1L..5L).map { song(it, "T$it", "X", "Al") })
            val id = (repository.createPlaylist("Duplikate") as AppResult.Success).value

            assertEquals(3, (repository.addToPlaylist(id, listOf(1, 2, 3)) as AppResult.Success).value)
            // Erneutes Hinzufuegen: 2 neue (4, 5), 1 Duplikat (3) wird uebersprungen.
            assertEquals(2, (repository.addToPlaylist(id, listOf(3, 4, 5)) as AppResult.Success).value)
            assertEquals(
                listOf(1L, 2L, 3L, 4L, 5L),
                repository.songsOfPlaylist(id).first().map { it.mediaStoreId },
            )
            // Nur Duplikate: 0 hinzugefuegt, Reihenfolge unveraendert.
            assertEquals(0, (repository.addToPlaylist(id, listOf(1, 2)) as AppResult.Success).value)
            assertEquals(
                listOf(1L, 2L, 3L, 4L, 5L),
                repository.songsOfPlaylist(id).first().map { it.mediaStoreId },
            )
        }

    @Test
    fun `playlist label laesst sich setzen entfernen und filtern`() =
        runTest {
            db.songDao().upsertAll((1L..2L).map { song(it, "T$it", "X", "Al") })
            val restId = (repository.createPlaylist("Pause") as AppResult.Success).value
            val workId = (repository.createPlaylist("Training") as AppResult.Success).value

            repository.setPlaylistLabel(restId, PlaylistLabel.REST)
            repository.setPlaylistLabel(workId, PlaylistLabel.WORK)

            assertEquals(
                listOf(restId),
                repository.playlistsByLabel(PlaylistLabel.REST).first().map { it.id },
            )
            assertEquals(
                PlaylistLabel.WORK,
                repository.playlists
                    .first()
                    .first { it.id == workId }
                    .label,
            )

            // Label wieder entfernen -> nicht mehr im Label-Filter.
            repository.setPlaylistLabel(restId, null)
            assertTrue(repository.playlistsByLabel(PlaylistLabel.REST).first().isEmpty())
        }

    @Test
    fun `volltextsuche findet ueber titel und kuenstler`() =
        runTest {
            db.songDao().upsertAll(
                listOf(
                    song(1, "Bohemian Rhapsody", "Queen", "A Night at the Opera"),
                    song(2, "Yesterday", "The Beatles", "Help"),
                ),
            )
            // Der FTS-Index wird beim Scan gepflegt, nicht beim Schreiben.
            db.libraryBrowseDao().rebuildSearchIndex()

            val byTitle = (repository.search("bohem") as AppResult.Success).value
            assertEquals(listOf(1L), byTitle.map { it.mediaStoreId })

            val byArtist = (repository.search("queen") as AppResult.Success).value
            assertEquals(listOf(1L), byArtist.map { it.mediaStoreId })

            assertTrue((repository.search("   ") as AppResult.Success).value.isEmpty())
        }

    /**
     * 2026-09-27, Befund 6.9: FTS4 verknuepft Token per UND. "queen
     * metallica" lieferte null Treffer, obwohl beide Begriffe in der
     * Bibliothek vorkommen. Der ODER-Fallback macht die Suche wieder
     * brauchbar, ohne die praezise UND-Semantik aufzugeben.
     */
    @Test
    fun `mehrwortsuche findet auch bei UND-leeremitte per ODER-treffer`() =
        runTest {
            db.songDao().upsertAll(
                listOf(
                    song(1, "Bohemian Rhapsody", "Queen", "A Night at the Opera"),
                    song(2, "Enter Sandman", "Metallica", "Black Album"),
                ),
            )
            db.libraryBrowseDao().rebuildSearchIndex()

            // Der Aufrufer probiert erst UND (praezise) und faellt dann auf
            // ODER zurueck. Beide Wege sehen hier dieselbe Eingabe, das
            // Ergebnis ist der Fallback.
            val hits = (repository.search("queen metallica") as AppResult.Success).value
            assertTrue(
                "ODER-Fallback lieferte nichts: $hits",
                hits.isNotEmpty(),
            )
            assertEquals(setOf(1L, 2L), hits.map { it.mediaStoreId }.toSet())
        }

    /**
     * Gegenprobe zum ODER-Fallback: bei einem Treffer im **praezisen**
     * Weg wird nicht verbreitert. Wer "queen bohemian" sucht, will den
     * einen Treffer, nicht alle Queen-Titel.
     */
    @Test
    fun `praezise treffer werden nicht verbreitert`() =
        runTest {
            db.songDao().upsertAll(
                listOf(
                    song(1, "Bohemian Rhapsody", "Queen", "A Night at the Opera"),
                    song(2, "We Will Rock You", "Queen", "News of the World"),
                ),
            )
            db.libraryBrowseDao().rebuildSearchIndex()

            val hits = (repository.search("queen bohemian") as AppResult.Success).value
            assertEquals(
                "Der praezise Weg lieferte mehr als den exakten Treffer",
                listOf(1L),
                hits.map { it.mediaStoreId },
            )
        }

    /**
     * 2026-09-27, Befund 6.9: FTS4 und `LIKE` vergleichen **Bytes**,
     * "Beyonce" findet "Beyoncé" nicht. Migration 17 fuehrt darum
     * `title_folded` — die normalisierte Form des Titels.
     *
     * Der Test **faellt ohne die Spalte um**: `searchFolded` kann dann
     * nichts finden, weil die Spalte `NULL` ist. Genau das war vorher
     * als Luecke dokumentiert (`diakritika im titel sind eine
     * dokumentierte luecke`).
     */
    @Test
    fun `diakritika werden ueber die gefaltete spalte gefunden`() =
        runTest {
            db.songDao().upsertAll(listOf(song(1, "Beyoncé", "Destiny's Child", "Listen")))
            db.libraryBrowseDao().rebuildSearchIndex()

            val hits = (repository.search("beyonce") as AppResult.Success).value
            assertTrue(
                "Der Diakritika-Fallback hat nicht gegriffen: $hits",
                hits.any { it.mediaStoreId == 1L },
            )
        }

    /**
     * Gegenprobe: die **umgekehrte** Richtung muss genauso funktionieren.
     * Eingabe "Beyoncé" (mit Akzent, etwa von einem iPhone-Tastatur-Layout),
     * gespeicherter Titel ohne. Das ist der haeufigere Fall, weil ein
     * deutscher Nutzer ohne Akzent tippt, ein importierter Tag aber welche
     * haben kann.
     */
    @Test
    fun `umgekehrte richtung findet ebenfalls`() =
        runTest {
            db.songDao().upsertAll(listOf(song(1, "Beyoncé", "Destiny's Child", "Listen")))
            db.libraryBrowseDao().rebuildSearchIndex()

            val hits = (repository.search("Beyoncé") as AppResult.Success).value
            assertTrue(
                "Auch die umgekehrte Richtung muss greifen: $hits",
                hits.any { it.mediaStoreId == 1L },
            )
        }

    /**
     * Die Kleinschreibung: "born to be wild" findet "Born To Be Wild".
     * Vor der gefalteten Spalte war das **nicht** der Fall — `LIKE` ohne
     * `COLLATE NOCASE` vergleicht case-sensitiv. Die gefaltete Spalte ist
     * kleingeschrieben, die Eingabe wird es auch.
     */
    @Test
    fun `gross-klein-schreibung wird ignoriert`() =
        runTest {
            db.songDao().upsertAll(listOf(song(1, "Born To Be Wild", "Steppenwolf", "Blitz")))
            db.libraryBrowseDao().rebuildSearchIndex()

            val hits = (repository.search("born to be") as AppResult.Success).value
            assertTrue(
                "Kleinschreibung muss denselben Titel finden: $hits",
                hits.any { it.mediaStoreId == 1L },
            )
        }

    /**
     * Gegenprobe: mit **richtigem** Diakritika greift bereits FTS, der
     * LIKE-Fallback wird gar nicht gebraucht. Das beweist, dass der
     * Fallback additiv wirkt und nicht den Normalweg ersetzt.
     */
    @Test
    fun `richtige diakritika finden ueber den schnellen fts-weg`() =
        runTest {
            db.songDao().upsertAll(listOf(song(1, "Beyoncé", "Destiny's Child", "Listen")))
            db.libraryBrowseDao().rebuildSearchIndex()

            val hits = (repository.search("Beyoncé") as AppResult.Success).value
            assertEquals(
                "Der FTS-Weg hat nicht getroffen, der Fallback wurde noetig",
                listOf(1L),
                hits.map { it.mediaStoreId },
            )
        }

    /**
     * 2026-09-27, Befund 6.9: ohne `ESCAPE` wuerde "%" als Muster
     * gelten und **jeden** Titel treffen. Der Test prueft, dass eine
     * Prozent-Eingabe als Text gesucht wird.
     */
    @Test
    fun `like-muster escaped sonderzeichen`() =
        runTest {
            db.songDao().upsertAll(
                listOf(
                    song(1, "100% Pure Love", "X", "Al"),
                    song(2, "Whatever", "X", "Al"),
                ),
            )
            db.libraryBrowseDao().rebuildSearchIndex()

            val hits = (repository.search("100%") as AppResult.Success).value
            assertEquals(
                "Die Prozent-Eingabe traf einen Titel ohne Prozentzeichen",
                listOf(1L),
                hits.map { it.mediaStoreId },
            )
        }

    @Test
    fun `m3u import loest lokale titel auf und ueberspringt streams`() =
        runTest {
            db.songDao().upsertAll(
                listOf(
                    song(1, "Song One", "X", "Al"),
                    song(2, "Song Two", "X", "Al"),
                ),
            )
            val m3u =
                """
                #EXTM3U
                #EXTINF:200,X - Song One
                Music/Song One.flac
                Song Two.flac
                http://example.com/stream.mp3
                Missing.flac
                """.trimIndent()

            val result = (repository.importM3uPlaylist("Import", m3u) as AppResult.Success).value
            assertEquals(2, result.importedCount)
            assertEquals(1, result.skippedRemote)
            assertEquals(1, result.unresolved)
            assertEquals(
                listOf(1L, 2L),
                repository.songsOfPlaylist(result.playlistId).first().map { it.mediaStoreId },
            )
        }

    /**
     * 2026-09-27, Befund 4.7: der M3U-Import pruefte nicht gegen
     * bestehende Eintraege und baute keine Duplikate ab. Zwei Zeilen auf
     * dieselbe Datei ergaben zwei Zeilen mit derselben `songId`, und ein
     * zweiter Import derselben M3U verdoppelte die gesamte Playlist.
     */
    @Test
    fun `m3u import legt keinen song doppelt an`() =
        runTest {
            db.songDao().upsertAll(listOf(song(1, "Song One", "X", "Al")))
            val m3u =
                """
                #EXTM3U
                #EXTINF:200,X - Song One
                Music/Song One.flac
                #EXTINF:200,X - Song One (zweiter Pfad)
                Other/Song One.flac
                """.trimIndent()

            val result = (repository.importM3uPlaylist("Doppelt", m3u) as AppResult.Success).value
            assertEquals("Die Meldung zaehlte zwei, obwohl einer eingefuegt wurde", 1, result.importedCount)
            assertEquals(
                listOf(1L),
                repository.songsOfPlaylist(result.playlistId).first().map { it.mediaStoreId },
            )
        }

    /** Zweimal-Import derselben Datei darf nichts duplizieren. */
    @Test
    fun `zweiter m3u import derselben datei aendert die playlist nicht`() =
        runTest {
            db.songDao().upsertAll(
                listOf(
                    song(1, "Song One", "X", "Al"),
                    song(2, "Song Two", "X", "Al"),
                ),
            )
            val m3u =
                """
                #EXTM3U
                Song One.flac
                Song Two.flac
                """.trimIndent()

            val first = (repository.importM3uPlaylist("Zweimal", m3u) as AppResult.Success).value
            assertEquals(2, first.importedCount)

            val second = (repository.importM3uPlaylist("Zweimal", m3u) as AppResult.Success).value
            assertEquals(
                "Der zweite Import hat Eintraege erneut angelegt",
                listOf(1L, 2L),
                repository.songsOfPlaylist(second.playlistId).first().map { it.mediaStoreId },
            )
        }

    /**
     * D5/A1: Der Shuffle laedt die Statistiken in EINER IN-Query statt je
     * Titel einmal. Der Fake zaehlt die Aufrufe (Konvention "Listen statt
     * Summen"); die uebrigen Daten kommen aus der echten In-Memory-DB.
     */
    @Test
    fun `shuffleCandidates laedt statistiken in einer IN-abfrage`() =
        runTest {
            db.songDao().upsertAll((1L..3L).map { song(it, "T$it", "X", "Al") })
            val statDao =
                RecordingPlayStatDao(
                    stats =
                        mapOf(2L to PlayStatEntity(songId = 2, playCount = 7, lastPlayedAtEpochMs = 500)),
                )
            val repo = repositoryWith(playStatDao = statDao)

            val candidates = (repo.shuffleCandidates(listOf(1, 2, 3)) as AppResult.Success).value

            assertEquals(listOf(listOf(1L, 2L, 3L)), statDao.getStatsArgs)
            assertEquals(emptyList<Long>(), statDao.getStatArgs)
            assertEquals(listOf(1L, 2L, 3L), candidates.map { it.songId })
            assertEquals(7, candidates.first { it.songId == 2L }.playCount)
            assertEquals(500L, candidates.first { it.songId == 2L }.lastPlayedAtEpochMs)
            assertEquals(0, candidates.first { it.songId == 1L }.playCount)
        }

    /**
     * D5/A2: Lueckenlose Neunummerierung als DELETE + ein Batch-Insert.
     * Der Fake belegt: kein einziges Einzel-UPDATE mehr.
     */
    @Test
    fun `removeFromPlaylist nummeriert in einem batch neu`() =
        runTest {
            val playlistDao = RecordingPlaylistDao()
            playlistDao.items +=
                listOf(
                    playlistItem(id = 10, position = 0),
                    playlistItem(id = 11, position = 1),
                    playlistItem(id = 12, position = 2),
                    playlistItem(id = 13, position = 3),
                )
            val repo = repositoryWith(playlistDao = playlistDao)

            assertTrue(repo.removeFromPlaylist(playlistId = 1L, position = 1) is AppResult.Success)

            assertEquals(1, playlistDao.deleteItemsOfPlaylistCalls)
            assertEquals(1, playlistDao.insertItemsArgs.size)
            assertEquals(emptyList<Long>(), playlistDao.updateItemPositionArgs)
            assertEquals(listOf(10L, 12L, 13L), playlistDao.items.map { it.id })
            assertEquals(listOf(0, 1, 2), playlistDao.items.map { it.position })
        }

    @Test
    fun `moveInPlaylist nummeriert in einem batch neu`() =
        runTest {
            val playlistDao = RecordingPlaylistDao()
            playlistDao.items +=
                listOf(
                    playlistItem(id = 10, position = 0),
                    playlistItem(id = 11, position = 1),
                    playlistItem(id = 12, position = 2),
                )
            val repo = repositoryWith(playlistDao = playlistDao)

            assertTrue(repo.moveInPlaylist(1L, fromPosition = 0, toPosition = 2) is AppResult.Success)

            assertEquals(1, playlistDao.deleteItemsOfPlaylistCalls)
            assertEquals(1, playlistDao.insertItemsArgs.size)
            assertEquals(emptyList<Long>(), playlistDao.updateItemPositionArgs)
            assertEquals(listOf(11L, 12L, 10L), playlistDao.items.map { it.id })
            assertEquals(listOf(0, 1, 2), playlistDao.items.map { it.position })
        }

    /** D5/A7: EIN Aufruf fuer alle Playlists eines Labels, kein N+1. */
    @Test
    fun `songsForLabelOnce laedt alle playlists in einer abfrage`() =
        runTest {
            val playlistDao = RecordingPlaylistDao()
            playlistDao.songsForLabel += song(1, "A", "Artist", "Album")
            playlistDao.songsForLabel += song(2, "B", "Artist", "Album")
            val repo = repositoryWith(playlistDao = playlistDao)

            val result = repo.songsForLabelOnce(PlaylistLabel.WORK)

            assertEquals(listOf("WORK"), playlistDao.songsForLabelArgs)
            assertEquals(
                listOf(1L, 2L),
                (result as AppResult.Success).value.map { it.mediaStoreId },
            )
        }

    /** Repository mit der echten In-Memory-DB, aber waehlbaren DAO-Fakes. */
    private fun repositoryWith(
        playStatDao: PlayStatDao = db.playStatDao(),
        playlistDao: PlaylistDao = db.playlistDao(),
    ): LibraryBrowseRepositoryImpl =
        LibraryBrowseRepositoryImpl(
            browseDao = db.libraryBrowseDao(),
            playStatDao = playStatDao,
            favoriteDao = db.favoriteDao(),
            playlistDao = playlistDao,
            songDao = db.songDao(),
            transactionRunner = RoomTransactionRunner(db),
            dispatchers = TestDispatcherProvider(),
            now = { clock },
        )

    private fun playlistItem(
        id: Long,
        position: Int,
    ): PlaylistItemEntity = PlaylistItemEntity(id = id, playlistId = 1L, songId = 100L + id, position = position)
}

/** D5/A1: Zaehlt die Statistik-Abfragen (Liste der Argumente je Aufruf). */
private class RecordingPlayStatDao(
    private val stats: Map<Long, PlayStatEntity> = emptyMap(),
) : PlayStatDao {
    val getStatsArgs = mutableListOf<List<Long>>()
    val getStatArgs = mutableListOf<Long>()

    override suspend fun incrementIfExists(
        songId: Long,
        atEpochMs: Long,
    ): Int = 0

    override suspend fun insertIfMissing(stat: PlayStatEntity) = Unit

    override suspend fun getStat(songId: Long): PlayStatEntity? {
        getStatArgs += songId
        return stats[songId]
    }

    override suspend fun getStats(songIds: List<Long>): List<PlayStatEntity> {
        getStatsArgs += songIds
        return songIds.mapNotNull { stats[it] }
    }

    override fun observeAll(): Flow<List<PlayStatEntity>> = flowOf(stats.values.toList())

    /** 2026-09-27 (Befund 6.7): Reconciliation, fuer den Fake nicht relevant. */
    override suspend fun reassignSong(
        oldSongId: Long,
        newSongId: Long,
    ): Int = 0
}

/** D5/A2: Zaehlt Batch-Insert und Einzel-Updates der Playlist-Eintraege. */
private class RecordingPlaylistDao : PlaylistDao {
    val items = mutableListOf<PlaylistItemEntity>()
    val insertItemsArgs = mutableListOf<List<PlaylistItemEntity>>()
    val updateItemPositionArgs = mutableListOf<Pair<Long, Int>>()
    var deleteItemsOfPlaylistCalls = 0
        private set

    val songsForLabel = mutableListOf<SongEntity>()
    val songsForLabelArgs = mutableListOf<String>()

    override suspend fun getItemsOnce(playlistId: Long): List<PlaylistItemEntity> = items.sortedBy { it.position }

    override suspend fun getSongsForLabelOnce(label: String): List<SongEntity> {
        songsForLabelArgs += label
        return songsForLabel
    }

    override suspend fun deleteItemsOfPlaylist(playlistId: Long) {
        deleteItemsOfPlaylistCalls++
        items.clear()
    }

    override suspend fun insertItems(items: List<PlaylistItemEntity>) {
        insertItemsArgs += items
        this.items += items
    }

    override suspend fun updateItemPosition(
        itemId: Long,
        position: Int,
    ) {
        updateItemPositionArgs += itemId to position
        val index = items.indexOfFirst { it.id == itemId }
        if (index >= 0) items[index] = items[index].copy(position = position)
    }

    override suspend fun insertPlaylist(playlist: PlaylistEntity): Long = unsupported()

    override suspend fun renamePlaylist(
        id: Long,
        name: String,
    ): Unit = unsupported()

    override suspend fun deletePlaylist(id: Long): Unit = unsupported()

    override suspend fun setLabel(
        id: Long,
        label: String?,
    ): Unit = unsupported()

    override suspend fun getPlaylistIdByName(name: String): Long? = unsupported()

    override fun observePlaylists(): Flow<List<PlaylistRow>> = flowOf(emptyList())

    override fun observePlaylistsByLabel(label: String): Flow<List<PlaylistRow>> = flowOf(emptyList())

    override suspend fun insertItem(item: PlaylistItemEntity): Long = unsupported()

    override suspend fun deleteItem(itemId: Long): Unit = unsupported()

    override suspend fun getSongIdsOnce(playlistId: Long): List<Long> = unsupported()

    override suspend fun maxPosition(playlistId: Long): Int = unsupported()

    override fun observeSongsOfPlaylist(playlistId: Long): Flow<List<SongEntity>> = flowOf(emptyList())

    /** 2026-09-27 (Befund 6.7): Reconciliation, fuer den Fake nicht relevant. */
    override suspend fun reassignSong(
        oldSongId: Long,
        newSongId: Long,
    ): Int = 0

    private fun unsupported(): Nothing = error("in diesem Test nicht benutzt")
}
