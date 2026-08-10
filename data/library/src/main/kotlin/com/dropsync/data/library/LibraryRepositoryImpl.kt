package com.dropsync.data.library

import com.dropsync.core.common.AppError
import com.dropsync.core.common.AppResult
import com.dropsync.core.common.DispatcherProvider
import com.dropsync.core.database.TransactionRunner
import com.dropsync.core.database.dao.CueTrackDao
import com.dropsync.core.database.dao.SafFileDao
import com.dropsync.core.database.dao.SongDao
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
                // Extern gelieferte Hashes ueberleben jeden Rescan; Titel aus
                // abgewaehlten Ordnern werden als nicht verfuegbar gefuehrt und
                // fallen so aus allen Ansichten (is_available = 1), Punkt 3.
                val entities =
                    scanned.map { song ->
                        val entity = song.toEntity(knownSha256 = existing[song.mediaStoreId]?.knownSha256)
                        if (song.relativePath in excludedFolders) {
                            entity.copy(isAvailable = false)
                        } else {
                            entity
                        }
                    }
                val changed = entities.count { existing[it.mediaStoreId] != it }
                val presentIds = entities.map { it.mediaStoreId }
                val presentIdSet = presentIds.toSet()
                val toUnavailable =
                    existing.values.count { it.isAvailable && it.mediaStoreId !in presentIdSet }

                transactionRunner {
                    songDao.upsertAll(entities)
                    songDao.markMissingAsUnavailable(presentIds)
                }
                scanStateStore.setLastGeneration(generation)

                // Import-Pipeline (Phase 5): neue, verfuegbare Songs stossen
                // ihre Waveform-Analyse direkt nach dem Scan automatisch an,
                // statt erst beim Oeffnen des Now-Playing-Screens. Der Worker
                // dedupliziert ueber track_analysis_<songId>, doppelte Aufrufe
                // sind also kostenlos; die UI zeigt den pulsierenden Placeholder.
                val newSongs =
                    entities
                        .filter { it.mediaStoreId !in existing && it.isAvailable }
                        .map { it.toDomain() }
                for (song in newSongs) {
                    trackAnalysisRepository.requestAnalysis(song)
                }

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
            } catch (e: Exception) {
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
            val text = safGateway.readDocument(document.documentUri) ?: continue
            val sheet = (CueSheetParser.parse(text) as? ParsedCueSheet.Success)?.sheet ?: continue
            for ((file, tracks) in sheet.tracks.groupBy { it.file }) {
                val song =
                    songs.filter { it.displayName.equals(file, ignoreCase = true) }.singleOrNull()
                        ?: continue
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
}
