package com.dropsync.data.library

import com.dropsync.core.common.AppError
import com.dropsync.core.common.AppResult
import com.dropsync.core.model.Song
import com.dropsync.core.testing.TestDispatcherProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryRepositoryImplTest {
    private val gateway = FakeMediaStoreGateway()
    private val songDao = FakeSongDao()
    private val scanState = FakeScanStateStore()
    private val folderFilter = FakeMusicFolderFilterRepository()

    private val repository =
        LibraryRepositoryImpl(
            gateway = gateway,
            songDao = songDao,
            scanStateStore = scanState,
            transactionRunner = FakeTransactionRunner(),
            dispatchers = TestDispatcherProvider(),
            cueTrackDao = FakeCueTrackDao(),
            safFileDao = FakeSafFileDao(),
            safGateway = FakeSafFolderGateway(),
            folderFilter = folderFilter,
        )

    private fun song(
        id: Long,
        name: String = "track$id.mp3",
    ) = Song(
        mediaStoreId = id,
        contentUri = "content://media/external/audio/media/$id",
        displayName = name,
        relativePath = "Music/Training",
        durationMs = 200_000,
        sizeBytes = 1_000 + id,
        dateModifiedSeconds = 1_700_000_000,
        title = "Track $id",
        artist = "Artist",
        album = "Album",
        isAvailable = true,
    )

    @Test
    fun `fehlende berechtigung liefert PermissionDenied statt leerem screen`() =
        runTest {
            gateway.permissionGranted = false
            val result = repository.refreshLibrary(force = false)
            assertTrue(result is AppResult.Failure)
            val error = (result as AppResult.Failure).error
            assertTrue(error is AppError.PermissionDenied)
        }

    @Test
    fun `unveraenderte generation loest keinen vollscan aus`() =
        runTest {
            // Abnahmekriterium Schritt 4: zweiter Aufruf ohne Aenderung
            // fuehrt keinen Vollscan durch.
            gateway.audio = listOf(song(1))
            repository.refreshLibrary(force = false)
            assertEquals(1, gateway.queryCount)

            val second = repository.refreshLibrary(force = false)
            assertEquals(1, gateway.queryCount)
            val value = (second as AppResult.Success).value
            assertTrue(value.skippedBecauseUnchanged)
            assertEquals(1, value.totalSongs)
        }

    @Test
    fun `force erzwingt vollscan trotz gleicher generation`() =
        runTest {
            gateway.audio = listOf(song(1))
            repository.refreshLibrary(force = false)
            val second = repository.refreshLibrary(force = true)
            assertEquals(2, gateway.queryCount)
            assertFalse((second as AppResult.Success).value.skippedBecauseUnchanged)
        }

    @Test
    fun `verschwundene songs werden nur als nicht verfuegbar markiert`() =
        runTest {
            // Schritt 4.4: Song verschwindet aus MediaStore, bleibt aber
            // mit isAvailable = false in der Datenbank erhalten.
            gateway.audio = listOf(song(1), song(2))
            repository.refreshLibrary(force = false)

            gateway.audio = listOf(song(1))
            gateway.generation = "v1:2"
            val result = repository.refreshLibrary(force = false)

            val value = (result as AppResult.Success).value
            assertEquals(1, value.markedUnavailable)
            val gone = songDao.rows.getValue(2)
            assertFalse(gone.isAvailable)
            assertTrue(songDao.rows.getValue(1).isAvailable)
        }

    @Test
    fun `rescan erhaelt den extern importierten hash`() =
        runTest {
            gateway.audio = listOf(song(1))
            repository.refreshLibrary(force = false)
            songDao.setKnownSha256(1, "a".repeat(64))

            gateway.generation = "v1:2"
            repository.refreshLibrary(force = false)

            assertEquals("a".repeat(64), songDao.rows.getValue(1).knownSha256)
        }

    @Test
    fun `titel in abgewaehltem ordner werden nicht verfuegbar`() =
        runTest {
            // Punkt 3: abgewaehlte Ordner werden beim Abgleich als nicht
            // verfuegbar gefuehrt und fallen so aus allen Ansichten.
            folderFilter.setExcludedFolders(setOf("Music/Podcasts"))
            gateway.audio =
                listOf(
                    song(1).copy(relativePath = "Music/Training"),
                    song(2).copy(relativePath = "Music/Podcasts"),
                )
            repository.refreshLibrary(force = true)

            assertTrue(songDao.rows.getValue(1).isAvailable)
            assertFalse(songDao.rows.getValue(2).isAvailable)
        }

    @Test
    fun `getSong liefert MediaUnavailable fuer unbekannte id`() =
        runTest {
            val result = repository.getSong(99)
            assertTrue(result is AppResult.Failure)
            assertEquals(
                AppError.MediaUnavailable(99),
                (result as AppResult.Failure).error,
            )
        }
}
