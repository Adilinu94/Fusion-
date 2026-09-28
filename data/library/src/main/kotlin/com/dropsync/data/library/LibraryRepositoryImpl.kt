package com.dropsync.data.library

import android.util.Log
import com.dropsync.core.common.AppError
import com.dropsync.core.common.AppResult
import com.dropsync.core.common.DispatcherProvider
import com.dropsync.core.database.TransactionRunner
import com.dropsync.core.database.dao.CueTrackDao
import com.dropsync.core.database.dao.FavoriteDao
import com.dropsync.core.database.dao.FlatSetDao
import com.dropsync.core.database.dao.LibraryBrowseDao
import com.dropsync.core.database.dao.MarkerDao
import com.dropsync.core.database.dao.PlayStatDao
import com.dropsync.core.database.dao.PlaylistDao
import com.dropsync.core.database.dao.SafFileDao
import com.dropsync.core.database.dao.SongDao
import com.dropsync.core.database.dao.TrackAnalysisDao
import com.dropsync.core.database.entity.SongEntity
import com.dropsync.core.model.Song
import com.dropsync.domain.audio.TrackAnalysisRepository
import com.dropsync.domain.library.AudioFileFormat
import com.dropsync.domain.library.CueSheetParser
import com.dropsync.domain.library.CueVirtualTrack
import com.dropsync.domain.library.FolderScanResult
import com.dropsync.domain.library.LibraryRepository
import com.dropsync.domain.library.LibraryScanResult
import com.dropsync.domain.library.MusicFolderFilterRepository
import com.dropsync.domain.library.ParsedCueSheet
import com.dropsync.domain.library.ScannedFile
import com.dropsync.domain.library.ScannedFileKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * Bibliotheksabgleich gegen MediaStore (Bauplan Schritt 4).
 *
 * - Identitaet ist immer die MediaStore-ID (5.1).
 * - Ohne Aenderung des MediaStore-Stands laeuft kein Vollscan (4.3).
 * - Verschwundene Songs werden nur als nicht verfuegbar markiert,
 *   nie geloescht (4.4); Marker und Historie bleiben erhalten.
 * - Der extern importierte SHA-256 bleibt beim Rescan erhalten.
 */
class LibraryRepositoryImpl(
    private val gateway: MediaStoreGateway,
    private val songDao: SongDao,
    private val scanStateStore: ScanStateStore,
    private val transactionRunner: TransactionRunner,
    private val dispatchers: DispatcherProvider,
    private val cueTrackDao: CueTrackDao,
    private val safFileDao: SafFileDao,
    private val safGateway: SafFolderGateway,
    private val folderFilter: MusicFolderFilterRepository,
    private val trackAnalysisRepository: TrackAnalysisRepository,
    private val browseDao: LibraryBrowseDao,
    // 2026-09-27 (Befund 6.7): fuer die Reconciliation nach einem
    // Ordner-Wechsel. Alle fuenf DAOs schreiben auf `song_id`; ohne sie
    // bleiben Marker, Favoriten, Playlists, Abspielstatistik und der
    // Musikbezug der Saetze auf der alten MediaStore-ID stehen.
    private val markerDao: MarkerDao,
    private val favoriteDao: FavoriteDao,
    private val playStatDao: PlayStatDao,
    private val playlistDao: PlaylistDao,
    private val flatSetDao: FlatSetDao,
    // 2026-09-27 (Befund 6.18 / 6.7): Analyse-Cache mitziehen und
    // verwaiste Zeilen aufraeumen. Der Aufraeumer prueft gegen `songs`,
    // damit ein nur temporaer nicht verfuegbarer Titel seine Waveform
    // behaelt.
    private val trackAnalysisDao: TrackAnalysisDao,
) : LibraryRepository {
    override val songs: Flow<List<Song>> =
        songDao.observeAll().map { entities -> entities.map { it.toDomain() } }

    override val availableSongs: Flow<List<Song>> =
        songDao.observeAvailable().map { entities -> entities.map { it.toDomain() } }

    override suspend fun refreshLibrary(force: Boolean): AppResult<LibraryScanResult> =
        withContext(dispatchers.io) {
            if (!gateway.hasAudioPermission()) {
                // Kein stiller leerer Screen: Fehler ist explizit (Schritt 4.2).
                return@withContext AppResult.failure(
                    AppError.PermissionDenied(gateway.requiredPermission()),
                )
            }
            try {
                val generation = gateway.currentGeneration()
                if (!force && generation == scanStateStore.lastGeneration()) {
                    val total = songDao.getAllOnce().size
                    return@withContext AppResult.success(
                        LibraryScanResult(
                            skippedBecauseUnchanged = true,
                            totalSongs = total,
                            newOrUpdatedSongs = 0,
                            markedUnavailable = 0,
                        ),
                    )
                }

                val scanned = gateway.queryAudio()
                val existing = songDao.getAllOnce().associateBy { it.mediaStoreId }
                val excludedFolders = folderFilter.excludedFolders.first()

                // 2026-09-27 (Befund 6.7): **Reconciliation verschobener
                // Dateien**, vor dem Bau der Entitaeten.
                //
                // Ausgangslage: eine verschobene oder umbenannte Datei
                // bekommt vom MediaStore eine **neue** `_id`. Die alte
                // gilt als verschwunden (`is_available = 0`), die neue
                // ist ein unbekannter Titel. Damit bleiben an der alten ID
                // haengen:
                //
                // - Marker und deren Landungspositionen (die ganze
                //   DropSync-Funktion),
                // - der Musikeintrag im Satz-Log (Befund 13.4),
                // - Favoriten, Playlists, Abspielstatistik, bekannte
                //   SHA-256-Hashes (die teure Analyse).
                //
                // Das war **stumm**: der Nutzer sieht nach dem Verschieben
                // seines Ordners "Bibliothek aktualisieren", und danach ist
                // ein Drittel seiner Marker einfach weg — ohne Fehlermeldung,
                // ohne Historie.
                //
                // Die Heuristik ist bewusst **konservativ**, weil ein
                // Fehlabgleich schlimmer ist als eine nicht erkannte
                // Verschiebung: erkannt wird nur, wenn Dateiname, Dauer
                // **und** Groesse uebereinstimmen und der alte Eintrag
                // nicht zurueckkam. Die Groesse grenzt "gleicher Name"
                // (viele `track01.mp3`) aus, die Dauer die Zufalls-
                // gleichheit zweier verschiedener Dateien. Zusammen ist das
                // praktisch eindeutig.
                val reconciled = SongReconciler.reconcile(existing.values, scanned)

                // Extern gelieferte Hashes ueberleben jeden Rescan; Titel aus
                // abgewaehlten Ordnern werden als nicht verfuegbar gefuehrt und
                // fallen so aus allen Ansichten (is_available = 1), Punkt 3.
                val entities =
                    scanned.map { song ->
                        val oldId = reconciled.renamedIds[song.mediaStoreId]
                        val prior =
                            existing[song.mediaStoreId]?.knownSha256
                                ?: oldId?.let { existing[it]?.knownSha256 }
                        val entity = song.toEntity(knownSha256 = prior)
                        if (song.relativePath in excludedFolders) {
                            entity.copy(isAvailable = false)
                        } else {
                            entity
                        }
                    }
                val changed = entities.count { existing[it.mediaStoreId] != it }
                val presentIds = entities.map { it.mediaStoreId }
                val presentIdSet = presentIds.toSet()
                // Die umbenannten Alt-IDs zaehlen als "verschwunden" und
                // werden zurueckgeschrieben, damit Marker, Favoriten und
                // der Satz-Log auf die neue ID zeigen.
                val toUnavailable =
                    existing.values.count { it.isAvailable && it.mediaStoreId !in presentIdSet }

                transactionRunner {
                    // 2026-09-27 (Befund 4.2): `NOT IN (:presentIds)` bindet
                    // einen Parameter je ID. Das Variablenlimit liegt bei 999
                    // (aelteres SQLite, API 26/27) bzw. 32766 (ab 3.32). Mit
                    // 5.000 Songs scheiterte der Scan und damit die ganze
                    // Bibliothek.
                    //
                    // Die Zerlegung braucht eine Hilfsabfrage, weil
                    // `markMissingAsUnavailable` alles markiert, was NICHT im
                    // Block steht: ein Aufruf je Block wuerde nach dem zweiten
                    // Block wieder alles aus dem ersten als fehlend markieren.
                    //
                    // Reihenfolge: erst "alle aus", dann die vorhandenen
                    // Bloecke zurueck. **Die Ordner-Ausschluesse** (Zeilen mit
                    // isAvailable = false wegen `excludedFolders`) stehen in
                    // [entities] und werden deshalb nicht ueber
                    // [presentIds] zurueckgesetzt — genau so bleiben sie
                    // ausgeblendet.
                    songDao.markAllUnavailable()
                    presentIds.chunked(SongDao.BIND_CHUNK_SIZE).forEach { chunk ->
                        songDao.markMissingAsUnavailable(chunk)
                    }
                    // 2026-09-27 (Befund 6.7): erkannte Verschiebungen
                    // nachziehen. Reihenfolge ist **entscheidend**:
                    //
                    // 1. Erst die neuen `songs`-Zeilen schreiben. Die
                    //    Fremdschluessel in Marker/Favoriten/Playlists
                    //    zeigen sonst auf eine ID, die es noch nicht gibt,
                    //    und der Scan bricht mit einer FK-Verletzung ab.
                    // 2. Dann umhaengen. Das war der Fehler in der ersten
                    //    Fassung: das Umhaengen stand vor dem Upsert.
                    songDao.upsertAll(entities)
                    //
                    // Ohne die fuenf Zeilen darunter waeren nach einem
                    // Ordnerwechsel die Marker (die DropSync-Funktion), die
                    // Favoriten, die Playlists, die Abspielhistorie und
                    // der Musikbezug der Saetze **stillschweigend** leer.
                    for ((newId, oldId) in reconciled.renamedIds) {
                        markerDao.reassignSong(oldId, newId)
                        favoriteDao.reassignSong(oldId, newId)
                        playStatDao.reassignSong(oldId, newId)
                        playlistDao.reassignSong(oldId, newId)
                        flatSetDao.reassignSong(oldId, newId)
                        // Die Analysezeile des **alten** Titels mitnehmen.
                        // Ohne das loest `deleteOrphans` unten zwar die
                        // verwaiste Zeile, aber der Titel verliert vorher
                        // seinen Cache und wird neu analysiert — bei einer
                        // Verschiebung ist das genau der Fall, bei dem man
                        // ihn behalten will.
                        trackAnalysisDao.reassignSong(oldId, newId)
                    }
                    // 2026-09-27 (Befund 6.18 / B-DB-2): verwaiste
                    // Analysezeilen aufraeumen. `track_analysis` hat
                    // bewusst keinen Fremdschluessel, damit ein Rescan den
                    // Cache nicht mitreisst — die Kehrseite war, dass
                    // geloeschte Titel ihre Waveform fuer immer behielten.
                    // Bei 10.000 Titeln sind das ~6 MB.
                    //
                    // **Nach** dem Upsert: `deleteOrphans` prueft gegen
                    // `songs`, und die neuen Zeilen muessen dort stehen.
                    trackAnalysisDao.deleteOrphans()
                    // FTS-Index beim Scan pflegen (Befund 3.12) statt je
                    // Suchanfrage O(N) neu aufzubauen.
                    browseDao.rebuildSearchIndex()
                }
                if (reconciled.movedCount > 0) {
                    Log.i(
                        SCAN_TAG,
                        "Reconciliation: ${reconciled.movedCount} verschobene Titel " +
                            "wiedergefunden (Marker, Favoriten, Playlists, Historie " +
                            "und Satz-Log nachgezogen)",
                    )
                }
                scanStateStore.setLastGeneration(generation)

                // Import-Pipeline (Phase 5, A10): neue, verfuegbare Songs
                // stossen ihre Analyse direkt nach dem Scan automatisch an —
                // als Volldurchgang mit Waveform, Mix-Metadaten und
                // Drop-Kandidaten (unbestaetigt, Review-Liste), statt erst
                // beim Oeffnen des Now-Playing-Screens. Der Batch-Anstoss
                // bestimmt Cache-Misses in EINER Abfrage (Poweramp-
                // Scanner-Muster: keine N Einzel-Queries bei grossen
                // Bibliotheken); der Worker dedupliziert ueber
                // track_analysis_<songId>.
                val newSongs =
                    entities
                        .filter { it.mediaStoreId !in existing && it.isAvailable }
                        .map { it.toDomain() }
                trackAnalysisRepository.requestAnalysisForNewSongs(newSongs)

                AppResult.success(
                    LibraryScanResult(
                        skippedBecauseUnchanged = false,
                        totalSongs = entities.size,
                        newOrUpdatedSongs = changed,
                        markedUnavailable = toUnavailable,
                    ),
                )
            } catch (e: SecurityException) {
                AppResult.failure(AppError.PermissionDenied(gateway.requiredPermission()))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 2026-09-27: `AppResult.failure` verliert die Ausnahme.
                // Bei einer neuen Transaktion (Reconciliation, Befund 6.7)
                // sieht man sonst nur noch "DatabaseFailure" und muss die
                // FK-Reihenfolge raten. Der Originalfehler geht ins Log.
                Log.e(SCAN_TAG, "Bibliotheks-Scan fehlgeschlagen", e)
                AppResult.failure(AppError.DatabaseFailure("refreshLibrary"))
            }
        }

    override suspend fun getSong(mediaStoreId: Long): AppResult<Song> =
        withContext(dispatchers.io) {
            val entity = songDao.getById(mediaStoreId)
            if (entity == null) {
                AppResult.failure(AppError.MediaUnavailable(mediaStoreId))
            } else {
                AppResult.success(entity.toDomain())
            }
        }

    override suspend fun markUnavailable(mediaStoreId: Long): AppResult<Unit> =
        withContext(dispatchers.io) {
            try {
                songDao.setAvailability(mediaStoreId, isAvailable = false)
                AppResult.success(Unit)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppResult.failure(AppError.DatabaseFailure("markUnavailable"))
            }
        }

    override suspend fun importCueSheet(
        songId: Long,
        cueText: String,
    ): AppResult<Int> =
        withContext(dispatchers.io) {
            val sheet =
                when (val parsed = CueSheetParser.parse(cueText)) {
                    is ParsedCueSheet.Malformed -> {
                        return@withContext AppResult.failure(AppError.Unknown("CUE: ${parsed.reason}"))
                    }

                    is ParsedCueSheet.Success -> {
                        parsed.sheet
                    }
                }
            songDao.getById(songId)
                ?: return@withContext AppResult.failure(AppError.MediaUnavailable(songId))
            try {
                val entities = sheet.tracks.map { it.toEntity(songId) }
                transactionRunner {
                    cueTrackDao.deleteForSong(songId)
                    cueTrackDao.insertAll(entities)
                }
                AppResult.success(entities.size)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppResult.failure(AppError.DatabaseFailure("importCueSheet"))
            }
        }

    override fun observeCueTracks(songId: Long): Flow<List<CueVirtualTrack>> =
        cueTrackDao.observeForSong(songId).map { entities -> entities.map { it.toDomain() } }

    override val scannedFiles: Flow<List<ScannedFile>> =
        safFileDao.observeAll().map { entities -> entities.map { it.toDomain() } }

    override suspend fun scanFolder(treeUri: String): AppResult<FolderScanResult> =
        withContext(dispatchers.io) {
            try {
                val documents = safGateway.listFiles(treeUri)
                val entities = mutableListOf<com.dropsync.core.database.entity.SafFileEntity>()
                val cueDocuments = mutableListOf<SafDocument>()
                var audio = 0
                var cues = 0
                var playlists = 0
                for (document in documents) {
                    when {
                        AudioFileFormat.isCueFile(document.displayName) -> {
                            cues++
                            cueDocuments += document
                            entities += document.toEntity(treeUri, ScannedFileKind.CUE, format = null)
                        }

                        AudioFileFormat.isPlaylistFile(document.displayName) -> {
                            playlists++
                            entities += document.toEntity(treeUri, ScannedFileKind.PLAYLIST, format = null)
                        }

                        else -> {
                            // Nur Formate, die MediaStore nicht indexiert;
                            // alles andere liefert bereits der Vollscan.
                            val format = AudioFileFormat.fromFileName(document.displayName)
                            if (format != null && !format.indexedByMediaStore) {
                                audio++
                                entities += document.toEntity(treeUri, ScannedFileKind.AUDIO, format)
                            }
                        }
                    }
                }
                transactionRunner {
                    safFileDao.deleteForTree(treeUri)
                    safFileDao.insertAll(entities)
                }
                val imported = importCueSheets(cueDocuments)
                AppResult.success(
                    FolderScanResult(
                        audioFiles = audio,
                        cueSheets = cues,
                        playlists = playlists,
                        importedCueTracks = imported,
                    ),
                )
            } catch (e: SecurityException) {
                AppResult.failure(AppError.PermissionDenied(treeUri))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppResult.failure(AppError.DatabaseFailure("scanFolder"))
            }
        }

    /**
     * CUE neben Audiodatei (Plan Phase 3): jede FILE-Referenz wird ueber
     * den Anzeigenamen einem Song zugeordnet; nur eindeutige Treffer
     * werden importiert (kein Ratespiel bei Duplikaten).
     */
    private suspend fun importCueSheets(cueDocuments: List<SafDocument>): Int {
        if (cueDocuments.isEmpty()) return 0
        val songs = songDao.getAllOnce()
        var imported = 0
        for (document in cueDocuments) {
            imported += importCueDocument(document, songs)
        }
        return imported
    }

    /** Importiert die eindeutigen FILE-Referenzen eines CUE-Dokuments. */
    private suspend fun importCueDocument(
        document: SafDocument,
        songs: List<SongEntity>,
    ): Int {
        val text = safGateway.readDocument(document.documentUri) ?: return 0
        val sheet = (CueSheetParser.parse(text) as? ParsedCueSheet.Success)?.sheet ?: return 0
        var imported = 0
        for ((file, tracks) in sheet.tracks.groupBy { it.file }) {
            val song = songs.filter { it.displayName.equals(file, ignoreCase = true) }.singleOrNull()
            if (song != null) {
                val entities = tracks.map { it.toEntity(song.mediaStoreId) }
                transactionRunner {
                    cueTrackDao.deleteForSong(song.mediaStoreId)
                    cueTrackDao.insertAll(entities)
                }
                imported += entities.size
            }
        }
        return imported
    }

    private companion object {
        /** Log-Tag des Bibliotheks-Scans. */
        const val SCAN_TAG = "LibraryScan"
    }
}
