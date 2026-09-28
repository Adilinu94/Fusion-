package com.dropsync.data.library

import com.dropsync.core.common.AppError
import com.dropsync.core.common.AppResult
import com.dropsync.core.database.dao.SongDao
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
    private val trackAnalysis = FakeTrackAnalysisRepository()

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
            trackAnalysisRepository = trackAnalysis,
            browseDao = FakeLibraryBrowseDao(),
            // 2026-09-27 (Befund 6.7): Reconciliation. Die Fakes sind hier
            // No-Ops — die Heuristik selbst wird in `SongReconcilerTest`
            // geprueft, das Umhaengen in `FolderScanAndCueTest`.
            markerDao = FakeMarkerDao(),
            favoriteDao = FakeFavoriteDao(),
            playStatDao = FakePlayStatDao(),
            playlistDao = FakePlaylistDao(),
            flatSetDao = FakeFlatSetDao(),
            trackAnalysisDao = FakeTrackAnalysisDao(),
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

    /**
     * 2026-09-27, Befund 4.2: `NOT IN (:presentIds)` bindet einen Parameter je
     * ID. Oberhalb des SQLite-Variablenlimits (999 bis SQLite 3.32, API
     * 26/27 liefern aeltere Builds) scheiterte der Scan und damit die ganze
     * Bibliothek.
     *
     * Der Test deckt die Zerlegung ab und prueft zugleich, dass sie
     * **semantisch korrekt** bleibt: alle vorhandenen Songs muessen am Ende
     * verfuegbar sein, nicht nur der letzte Block.
     */
    @Test
    fun `scan mit mehr songs als das sqlite variablenlimit erhaelt alle als verfuegbar`() =
        runTest {
            val songCount = SongDao.BIND_CHUNK_SIZE * 3 + 17
            gateway.audio = (1L..songCount.toLong()).map { song(it) }
            gateway.generation = "v1:1"

            val result = repository.refreshLibrary(force = false)
            assertTrue(result is AppResult.Success)

            // Jeder Song muss verfuegbar sein. Ohne Chunking wuerde der
            // Scan entweder scheitern oder (falsch) alles als fehlend
            // markieren, was die letzten Bloecke betrifft.
            val unavailable = songDao.rows.filterValues { !it.isAvailable }.keys
            assertTrue(
                "Es duerfen keine Songs als fehlend markiert werden, war: ${unavailable.size}",
                unavailable.isEmpty(),
            )
            assertEquals(songCount, songDao.rows.size)
        }

    /**
     * 2026-09-27: leerer Scan darf nicht an `NOT IN ()` scheitern. Der
     * Aufrufer nutzt dafuer [SongDao.markAllUnavailable].
     */
    @Test
    fun `leerer scan markiert alles als nicht verfuegbar ohne fehler`() =
        runTest {
            gateway.audio = listOf(song(1), song(2))
            gateway.generation = "v1:1"
            repository.refreshLibrary(force = false)

            gateway.audio = emptyList()
            gateway.generation = "v1:2"
            val result = repository.refreshLibrary(force = false)

            assertTrue(result is AppResult.Success)
            assertTrue(songDao.rows.values.none { it.isAvailable })
            // Die Zeilen bleiben erhalten (Schritt 4.4), nur der Status kippt.
            assertEquals(2, songDao.rows.size)
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

    @Test
    fun `neue songs stossen automatisch die analyse an`() =
        runTest {
            // Phase 5 Import-Pipeline: neue Songs werden direkt nach dem
            // Scan analysiert, ohne dass der Now-Playing-Screen sie oeffnet.
            gateway.audio = listOf(song(1), song(2))
            repository.refreshLibrary(force = false)

            assertEquals(listOf(1L, 2L), trackAnalysis.requestedSongs.map { it.mediaStoreId })
        }

    @Test
    fun `neue songs laufen gebatcht in genau einem anstoss`() =
        runTest {
            // Poweramp-Scanner-Muster (Triage 2026-08-13): bei grossen
            // Bibliotheken ein Batch statt N Einzel-Aufrufe, damit die
            // Cache-Miss-Abfrage in EINER Query laeuft.
            gateway.audio = listOf(song(1), song(2), song(3))
            repository.refreshLibrary(force = false)

            assertEquals(1, trackAnalysis.batchRequestedSongs.size)
            assertEquals(
                listOf(1L, 2L, 3L),
                trackAnalysis.batchRequestedSongs.single().map { it.mediaStoreId },
            )
        }

    @Test
    fun `rescan stösst analyse nur fuer neue songs an`() =
        runTest {
            gateway.audio = listOf(song(1))
            repository.refreshLibrary(force = false)
            assertEquals(listOf(1L), trackAnalysis.requestedSongs.map { it.mediaStoreId })

            gateway.audio = listOf(song(1), song(2))
            gateway.generation = "v1:2"
            repository.refreshLibrary(force = false)
            assertEquals(listOf(1L, 2L), trackAnalysis.requestedSongs.map { it.mediaStoreId })
        }

    @Test
    fun `nicht verfuegbare neue songs werden nicht analysiert`() =
        runTest {
            // Abgewaehlte Ordner (isAvailable=false) fallen aus der Analyse:
            // ihr Waveform wird nie gebraucht.
            folderFilter.setExcludedFolders(setOf("Music/Podcasts"))
            gateway.audio =
                listOf(
                    song(1).copy(relativePath = "Music/Podcasts"),
                    song(2),
                )
            repository.refreshLibrary(force = true)

            assertEquals(listOf(2L), trackAnalysis.requestedSongs.map { it.mediaStoreId })
        }
}
