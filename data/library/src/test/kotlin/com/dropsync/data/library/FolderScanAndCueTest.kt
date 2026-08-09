package com.dropsync.data.library

import com.dropsync.core.common.AppResult
import com.dropsync.core.model.Song
import com.dropsync.core.testing.TestDispatcherProvider
import com.dropsync.domain.library.ScannedFileKind
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SAF-Ordnerscan und CUE-Import (Plan Phase 3): Formatauswahl,
 * Baum-Ersetzung beim Rescan, automatischer CUE-Import mit eindeutiger
 * Songzuordnung sowie direkter Import je Song.
 */
class FolderScanAndCueTest {
    private val gateway = FakeMediaStoreGateway()
    private val songDao = FakeSongDao()
    private val cueTrackDao = FakeCueTrackDao()
    private val safFileDao = FakeSafFileDao()
    private val safGateway = FakeSafFolderGateway()

    private val repository =
        LibraryRepositoryImpl(
            gateway = gateway,
            songDao = songDao,
            scanStateStore = FakeScanStateStore(),
            transactionRunner = FakeTransactionRunner(),
            dispatchers = TestDispatcherProvider(),
            cueTrackDao = cueTrackDao,
            safFileDao = safFileDao,
            safGateway = safGateway,
            folderFilter = FakeMusicFolderFilterRepository(),
        )

    private fun song(
        id: Long,
        name: String,
    ) = Song(
        mediaStoreId = id,
        contentUri = "content://media/external/audio/media/$id",
        displayName = name,
        relativePath = "Music/Alben",
        durationMs = 3_600_000,
        sizeBytes = 100_000,
        dateModifiedSeconds = 1_700_000_000,
        title = name,
        artist = "Artist",
        album = "Album",
        isAvailable = true,
    )

    private fun doc(
        name: String,
        path: String = "Alben",
    ) = SafDocument(
        documentUri = "content://tree/x/document/$path%2F$name",
        displayName = name,
        relativePath = path,
        sizeBytes = 10,
        lastModifiedMs = 1,
    )

    private val cueText =
        """
        PERFORMER "Album Artist"
        TITLE "Album"
        FILE "album.flac" WAVE
          TRACK 01 AUDIO
            TITLE "Eins"
            INDEX 01 00:00:00
          TRACK 02 AUDIO
            TITLE "Zwei"
            INDEX 01 04:00:00
        """.trimIndent()

    @Test
    fun `ordnerscan indexiert nur mediastore-fremde formate plus cue und playlist`() =
        runTest {
            safGateway.files =
                listOf(
                    doc("a.ape"),
                    doc("b.dsf"),
                    doc("c.mp3"), // indexiert MediaStore selbst -> ignoriert
                    doc("liste.m3u"),
                    doc("album.cue"),
                    doc("readme.txt"), // unbekannt -> ignoriert
                )

            val result = repository.scanFolder("content://tree/x")
            assertTrue(result is AppResult.Success)
            val scan = (result as AppResult.Success).value
            assertEquals(2, scan.audioFiles)
            assertEquals(1, scan.cueSheets)
            assertEquals(1, scan.playlists)

            val files = repository.scannedFiles.first()
            assertEquals(4, files.size)
            assertEquals(1, files.count { it.kind == ScannedFileKind.CUE })
            assertEquals(1, files.count { it.kind == ScannedFileKind.PLAYLIST })
        }

    @Test
    fun `rescan ersetzt den baum vollstaendig`() =
        runTest {
            safGateway.files = listOf(doc("a.ape"), doc("b.tak"))
            repository.scanFolder("content://tree/x")

            safGateway.files = listOf(doc("a.ape"))
            repository.scanFolder("content://tree/x")

            assertEquals(1, repository.scannedFiles.first().size)
        }

    @Test
    fun `gefundenes cue wird eindeutig zugeordnetem song importiert`() =
        runTest {
            songDao.upsertAll(listOf(song(7, "album.flac").toEntity(knownSha256 = null)))
            val cueDoc = doc("album.cue")
            safGateway.files = listOf(cueDoc)
            safGateway.documents = mapOf(cueDoc.documentUri to cueText)

            val result = repository.scanFolder("content://tree/x")
            assertTrue(result is AppResult.Success)
            assertEquals(2, (result as AppResult.Success).value.importedCueTracks)

            val tracks = repository.observeCueTracks(7).first()
            assertEquals(listOf("Eins", "Zwei"), tracks.map { it.title })
            // Endzeit von Track 1 = Start von Track 2; letzter bis Dateiende.
            assertEquals(240_000L, tracks[0].endMs)
            assertEquals(null, tracks[1].endMs)
        }

    @Test
    fun `cue ohne eindeutigen song wird nicht importiert`() =
        runTest {
            songDao.upsertAll(
                listOf(
                    song(1, "album.flac").toEntity(knownSha256 = null),
                    song(2, "ALBUM.FLAC").toEntity(knownSha256 = null),
                ),
            )
            val cueDoc = doc("album.cue")
            safGateway.files = listOf(cueDoc)
            safGateway.documents = mapOf(cueDoc.documentUri to cueText)

            val result = repository.scanFolder("content://tree/x")
            assertEquals(0, (result as AppResult.Success).value.importedCueTracks)
        }

    @Test
    fun `direkter cue-import ersetzt vorhandene tracks`() =
        runTest {
            songDao.upsertAll(listOf(song(9, "album.flac").toEntity(knownSha256 = null)))

            val first = repository.importCueSheet(9, cueText)
            assertEquals(2, (first as AppResult.Success).value)

            // Erneuter Import ersetzt statt zu verdoppeln.
            val second = repository.importCueSheet(9, cueText)
            assertEquals(2, (second as AppResult.Success).value)
            assertEquals(2, repository.observeCueTracks(9).first().size)
        }

    @Test
    fun `defektes cue und unbekannter song schlagen fehl`() =
        runTest {
            songDao.upsertAll(listOf(song(9, "album.flac").toEntity(knownSha256 = null)))
            assertTrue(repository.importCueSheet(9, "kein cue") is AppResult.Failure)
            assertTrue(repository.importCueSheet(404, cueText) is AppResult.Failure)
        }
}
