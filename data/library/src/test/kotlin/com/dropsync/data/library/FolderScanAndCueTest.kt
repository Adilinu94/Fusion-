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

    // 2026-09-27 (Befund 6.7): fuer die Reconciliation-Tests. Die
    // Fakes sind eigene Felder, damit die Tests zusaetzlich auf
    // `reassignCalls` lesen koennen.
    private val markerDao = FakeMarkerDao()
    private val favoriteDao = FakeFavoriteDao()
    private val playStatDao = FakePlayStatDao()
    private val playlistDao = FakePlaylistDao()
    private val flatSetDao = FakeFlatSetDao()
    private val trackAnalysisDao = FakeTrackAnalysisDao()

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
            trackAnalysisRepository = FakeTrackAnalysisRepository(),
            browseDao = FakeLibraryBrowseDao(),
            // 2026-09-27 (Befund 6.7): Reconciliation-Fakes.
            markerDao = markerDao,
            favoriteDao = favoriteDao,
            playStatDao = playStatDao,
            playlistDao = playlistDao,
            flatSetDao = flatSetDao,
            trackAnalysisDao = trackAnalysisDao,
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

    /**
     * Reconciliation nach einem Ordnerwechsel (2026-09-27, Befund 6.7).
     *
     * Der Test prueft die **Wirkung**, nicht die Heuristik (die prueft
     * `SongReconcilerTest`): das Repository muss beim Scan alle fuenf
     * verknuepften DAOs mit dem richtigen Paar aufrufen. Ohne das blieben
     * nach dem Verschieben eines Titels seine Marker, Favoriten,
     * Playlist-Eintraege, Abspielstatistik und Satz-Log **stillschweigend**
     * auf der alten MediaStore-ID.
     */
    @Test
    fun `verschobener titel zieht alle verknuepfungen nach`() =
        runTest {
            // Bestand: der Titel hat die ID 100.
            songDao.upsertAll(listOf(song(100, "track.mp3").toEntity(knownSha256 = null)))
            gateway.audio = listOf(song(100, "track.mp3"))

            // Der Nutzer verschiebt die Datei: neue ID 200, sonst identisch.
            gateway.audio = listOf(song(200, "track.mp3"))
            val result = repository.refreshLibrary(force = true)
            assertTrue("Der Scan muss erfolgreich sein: $result", result is AppResult.Success)

            val expected = listOf(100L to 200L)
            assertEquals("Marker nicht nachgezogen", expected, markerDao.reassignCalls)
            assertEquals("Favoriten nicht nachgezogen", expected, favoriteDao.reassignCalls)
            assertEquals("Abspielstatistik nicht nachgezogen", expected, playStatDao.reassignCalls)
            assertEquals("Playlists nicht nachgezogen", expected, playlistDao.reassignCalls)
            assertEquals("Satz-Log nicht nachgezogen", expected, flatSetDao.reassignCalls)
        }

    /**
     * Gegenprobe: ohne Verschiebung darf **kein** Umhaengen stattfinden.
     * Sonst wuerde ein normaler Scan, bei dem nur ein neuer Titel
     * hinzukommt, die Verknuepfungen eines anderen Titels verschieben.
     */
    @Test
    fun `neuer titel ohne passenden alten bewegt nichts`() =
        runTest {
            songDao.upsertAll(listOf(song(100, "a.mp3").toEntity(knownSha256 = null)))
            // Der alte Titel ist weg, ein voellig anderer ist neu.
            gateway.audio = listOf(song(200, "voellig_anders.mp3"))

            repository.refreshLibrary(force = true)

            assertTrue(
                "Ohne passenden Fingerabdruck darf nichts umgehaengt werden: " +
                    "${markerDao.reassignCalls}",
                markerDao.reassignCalls.isEmpty(),
            )
        }

    /**
     * Der Analyse-Cache wird bei jeder Gelegenheit aufgeraeumt
     * (2026-09-27, Befund 6.18 / B-DB-2).
     *
     * `track_analysis` hat bewusst keinen Fremdschluessel auf `songs`, damit
     * ein Rescan den Cache nicht mitreisst. Die Kehrseite war, dass
     * geloeschte Titel ihre Waveform fuer immer behielten — bei 10.000
     * Titeln ~6 MB, ueber Jahre ungebundenes Wachstum.
     *
     * **Der entscheidende Punkt ist das `setKnownSongIds`:** der
     * Aufraeumer prueft gegen `songs`, **nicht** gegen `is_available`.
     * Ein Titel, der nur temporaer nicht verfuegbar ist (SD-Karte
     * gezogen), ist weiterhin in `songs` und muss seine Analyse behalten.
     */
    @Test
    fun `verwaiste analysezeilen werden beim scan geraeumt`() =
        runTest {
            // Zwei Analysen: eine zu einem existierenden, eine zu einem
            // Titel, den es nicht mehr gibt.
            trackAnalysisDao.seed(
                analysis(songId = 100),
                analysis(songId = 999),
            )
            songDao.upsertAll(listOf(song(100, "a.mp3").toEntity(knownSha256 = null)))
            trackAnalysisDao.setKnownSongIds(setOf(100L))
            gateway.audio = listOf(song(100, "a.mp3"))

            repository.refreshLibrary(force = true)

            assertEquals(
                "Die verwaiste Analysezeile (999) muss entfernt sein, " +
                    "die zum existierenden Titel (100) bleiben",
                listOf(100L),
                trackAnalysisDao.currentSongIds(),
            )
            assertEquals(
                "Der Aufraeumer muss bei jedem Scan laufen",
                1,
                trackAnalysisDao.deleteOrphansCalls,
            )
        }

    /**
     * **Die Reihenfolge ist der ganze Punkt.** Bei einer Verschiebung
     * muss der Cache **mitgezogen** werden, bevor der Aufraeumer laeuft —
     * sonst loescht er gerade die Zeile, die er behalten soll, und der
     * vierminuetige Titel wird erneut analysiert.
     */
    @Test
    fun `verschobener titel behaelt seinen analyse-cache`() =
        runTest {
            songDao.upsertAll(listOf(song(100, "track.mp3").toEntity(knownSha256 = null)))
            trackAnalysisDao.seed(analysis(songId = 100))
            // Die Datei ist verschoben: die alte ID verschwindet, die
            // Analysezeile haengt noch daran.
            gateway.audio = listOf(song(200, "track.mp3"))
            trackAnalysisDao.setKnownSongIds(setOf(200L))

            repository.refreshLibrary(force = true)

            assertEquals(
                "Die Analysezeile muss der Verschiebung gefolgt sein",
                listOf(200L),
                trackAnalysisDao.currentSongIds(),
            )
        }

    /**
     * Eine vollstaendige Analysezeile. Nur [songId] ist fuer diese Tests
     * relevant, der Rest steht auf plausiblen Werten, damit die Entity
     * nicht an einer Validierung scheitert.
     */
    private fun analysis(songId: Long) =
        com.dropsync.core.database.entity.TrackAnalysisEntity(
            songId = songId,
            waveformData = ByteArray(8),
            bucketCount = 4,
            analyzerVersion = 1,
            analyzedAtEpochMs = 1_700_000_000_000L,
            peakLinear = 0.9,
            bpm = 120f,
            bpmConfidence = 0.8f,
            camelotKey = "8A",
            keyConfidence = 0.7f,
            integratedLufs = -14f,
            truePeakDb = -1f,
            downbeatOffsetMs = 120L,
            downbeatConfidence = 0.6f,
        )
}
