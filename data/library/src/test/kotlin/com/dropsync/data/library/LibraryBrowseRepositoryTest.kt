package com.dropsync.data.library

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dropsync.core.common.AppResult
import com.dropsync.core.database.DropSyncDatabase
import com.dropsync.core.database.RoomTransactionRunner
import com.dropsync.core.database.entity.SongEntity
import com.dropsync.core.model.PlaylistLabel
import com.dropsync.core.testing.TestDispatcherProvider
import kotlinx.coroutines.flow.first
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

            val byTitle = (repository.search("bohem") as AppResult.Success).value
            assertEquals(listOf(1L), byTitle.map { it.mediaStoreId })

            val byArtist = (repository.search("queen") as AppResult.Success).value
            assertEquals(listOf(1L), byArtist.map { it.mediaStoreId })

            assertTrue((repository.search("   ") as AppResult.Success).value.isEmpty())
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
}
