package com.dropsync.data.library

import com.dropsync.core.common.AppError
import com.dropsync.core.common.AppResult
import com.dropsync.core.common.Clock
import com.dropsync.core.common.DispatcherProvider
import com.dropsync.core.database.TransactionRunner
import com.dropsync.core.database.dao.MarkerDao
import com.dropsync.core.database.dao.SongDao
import com.dropsync.core.database.entity.MarkerSongLinkEntity
import com.dropsync.core.database.entity.SongMarkerEntity
import com.dropsync.core.model.LinkMethod
import com.dropsync.core.model.MarkerSource
import com.dropsync.core.model.SongMarker
import com.dropsync.domain.library.ImportReport
import com.dropsync.domain.library.ImportValidation
import com.dropsync.domain.library.ImportValidator
import com.dropsync.domain.library.ImportedTrack
import com.dropsync.domain.library.MarkerMatcher
import com.dropsync.domain.library.MarkerRepository
import com.dropsync.domain.library.MatchMethod
import com.dropsync.domain.library.MatchResult
import com.dropsync.domain.library.SongFingerprint
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * Markerimport und -zuordnung (Bauplan Schritt 6).
 *
 * - Der Import ist eine einzige Transaktion: ein Regelverstoss oder
 *   Datenbankfehler veraendert keine Tabelle (6.2, Abnahme 6).
 * - Re-Import desselben Tracks aktualisiert Marker ueber den
 *   Quell-Fingerprint statt zu duplizieren (6.5).
 * - SHA-256 kommt ausschliesslich aus dem Importdokument (Abschnitt 2)
 *   und wird bei erfolgreicher Zuordnung am Song gespeichert, damit
 *   die Stufe-1-Zuordnung bei spaeteren Importen greift.
 */
class MarkerRepositoryImpl(
    private val markerDao: MarkerDao,
    private val songDao: SongDao,
    private val transactionRunner: TransactionRunner,
    private val validator: ImportValidator,
    private val matcher: MarkerMatcher,
    private val clock: Clock,
    private val dispatchers: DispatcherProvider,
) : MarkerRepository {
    override val unmatchedMarkers: Flow<List<SongMarker>> =
        markerDao.observeUnmatched().map { entities ->
            entities.map { it.toDomain(linkedSongId = null) }
        }

    override val pendingAutoDetectedMarkers: Flow<List<SongMarker>> =
        markerDao.observePendingBySource(MarkerSource.AUTO_DETECTED.name).map { rows ->
            rows.map { it.marker.toDomain(linkedSongId = it.linkedSongId) }
        }

    override suspend fun confirmMarker(markerId: Long): AppResult<Unit> =
        withContext(dispatchers.io) {
            val marker =
                markerDao.getById(markerId)
                    ?: return@withContext AppResult.failure(AppError.MarkerUnmatched(null))
            try {
                markerDao.update(markerId, marker.label, marker.positionMs, isEnabled = true)
                AppResult.success(Unit)
            } catch (e: Exception) {
                AppResult.failure(AppError.DatabaseFailure("confirmMarker"))
            }
        }

    override suspend fun importDocument(
        schemaVersion: Int,
        tracks: List<ImportedTrack>,
    ): AppResult<ImportReport> =
        withContext(dispatchers.io) {
            when (val validation = validator.validate(schemaVersion, tracks)) {
                is ImportValidation.Invalid -> {
                    // Abgelehnt, bevor irgendetwas geschrieben wird (6.2).
                    AppResult.success(
                        ImportReport(
                            added = 0,
                            updated = 0,
                            unmatched = 0,
                            rejectedViolations = validation.violations,
                        ),
                    )
                }

                ImportValidation.Valid -> {
                    try {
                        AppResult.success(transactionRunner { runImport(tracks) })
                    } catch (e: Exception) {
                        AppResult.failure(AppError.DatabaseFailure("importDocument"))
                    }
                }
            }
        }

    private suspend fun runImport(tracks: List<ImportedTrack>): ImportReport {
        val candidates =
            songDao.getAllOnce().map { entity ->
                SongFingerprint(
                    mediaStoreId = entity.mediaStoreId,
                    relativePath = entity.relativePath,
                    displayName = entity.displayName,
                    sizeBytes = entity.sizeBytes,
                    durationMs = entity.durationMs,
                    knownSha256 = entity.knownSha256,
                )
            }

        var added = 0
        var updated = 0
        var unmatched = 0

        for (track in tracks) {
            val fingerprint = fingerprintOf(track)
            val existingByPosition =
                markerDao.getByFingerprint(fingerprint).associateBy { it.positionMs }

            val markerIds = mutableListOf<Long>()
            for (marker in track.markers) {
                val existing = existingByPosition[marker.positionMs]
                if (existing == null) {
                    markerIds +=
                        markerDao.insert(
                            SongMarkerEntity(
                                sourceFingerprint = fingerprint,
                                label = marker.label,
                                positionMs = marker.positionMs,
                                source = MarkerSource.IMPORT.name,
                                isEnabled = true,
                                createdAtEpochMs = clock.epochMillis(),
                            ),
                        )
                    added++
                } else {
                    // Re-Import: gleiche Position => aktualisieren (6.5).
                    markerDao.update(existing.id, marker.label, marker.positionMs, existing.isEnabled)
                    markerIds += existing.id
                    updated++
                }
            }

            when (val match = matcher.match(track, candidates)) {
                is MatchResult.Matched -> {
                    val sha256 = track.sha256
                    if (sha256 != null) {
                        songDao.setKnownSha256(match.mediaStoreId, sha256)
                    }
                    val method =
                        when (match.method) {
                            MatchMethod.HASH -> LinkMethod.HASH

                            MatchMethod.METADATA_STRICT,
                            MatchMethod.METADATA_LOOSE,
                            -> LinkMethod.METADATA
                        }
                    for (markerId in markerIds) {
                        if (markerDao.getLinkForMarker(markerId) == null) {
                            markerDao.insertLink(
                                MarkerSongLinkEntity(
                                    markerId = markerId,
                                    songId = match.mediaStoreId,
                                    linkMethod = method.name,
                                    linkedAtEpochMs = clock.epochMillis(),
                                ),
                            )
                        }
                    }
                }

                // Kein Raten (5.1): ohne eindeutigen Treffer bleibt der
                // Marker gespeichert, aber ohne Linkzeile.
                MatchResult.Unmatched, is MatchResult.Ambiguous -> {
                    unmatched += track.markers.size
                }
            }
        }
        return ImportReport(added, updated, unmatched, rejectedViolations = emptyList())
    }

    override suspend fun linkManually(
        markerId: Long,
        songId: Long,
    ): AppResult<Unit> =
        withContext(dispatchers.io) {
            markerDao.getById(markerId)
                ?: return@withContext AppResult.failure(AppError.MarkerUnmatched(null))
            songDao.getById(songId)
                ?: return@withContext AppResult.failure(AppError.MediaUnavailable(songId))
            try {
                transactionRunner {
                    markerDao.deleteLinkForMarker(markerId)
                    markerDao.insertLink(
                        MarkerSongLinkEntity(
                            markerId = markerId,
                            songId = songId,
                            linkMethod = LinkMethod.MANUAL.name,
                            linkedAtEpochMs = clock.epochMillis(),
                        ),
                    )
                }
                AppResult.success(Unit)
            } catch (e: Exception) {
                AppResult.failure(AppError.DatabaseFailure("linkManually"))
            }
        }

    override suspend fun getEnabledMarkersForSong(songId: Long): AppResult<List<SongMarker>> =
        withContext(dispatchers.io) {
            try {
                val markers =
                    markerDao
                        .getEnabledMarkersForSong(songId)
                        .map { it.toDomain(linkedSongId = songId) }
                AppResult.success(markers)
            } catch (e: Exception) {
                AppResult.failure(AppError.DatabaseFailure("getEnabledMarkersForSong"))
            }
        }

    override suspend fun createManualMarker(
        songId: Long,
        label: String,
        positionMs: Long,
    ): AppResult<SongMarker> =
        withContext(dispatchers.io) {
            val song =
                songDao.getById(songId)
                    ?: return@withContext AppResult.failure(AppError.MediaUnavailable(songId))
            if (positionMs < 0L || (song.durationMs > 0L && positionMs > song.durationMs)) {
                return@withContext AppResult.failure(
                    AppError.Unknown("positionMs $positionMs ausserhalb der Songdauer ${song.durationMs}"),
                )
            }
            val effectiveLabel = label.ifBlank { DEFAULT_MANUAL_LABEL }
            // Derselbe Fingerprint, den die Bibliothek fuer den Song fuehrt
            // (Pfad/Name/Groesse/Dauer) - Wiederverwendung statt neuer Logik.
            val fingerprint =
                listOf(
                    song.relativePath,
                    song.displayName,
                    song.sizeBytes.toString(),
                    song.durationMs.toString(),
                ).joinToString(SEPARATOR.toString())
            try {
                val markerId =
                    transactionRunner {
                        val id =
                            markerDao.insert(
                                SongMarkerEntity(
                                    sourceFingerprint = fingerprint,
                                    label = effectiveLabel,
                                    positionMs = positionMs,
                                    source = MarkerSource.MANUAL.name,
                                    isEnabled = true,
                                    createdAtEpochMs = clock.epochMillis(),
                                ),
                            )
                        markerDao.insertLink(
                            MarkerSongLinkEntity(
                                markerId = id,
                                songId = songId,
                                linkMethod = LinkMethod.MANUAL.name,
                                linkedAtEpochMs = clock.epochMillis(),
                            ),
                        )
                        id
                    }
                AppResult.success(
                    SongMarker(
                        id = markerId,
                        label = effectiveLabel,
                        positionMs = positionMs,
                        source = MarkerSource.MANUAL,
                        isEnabled = true,
                        linkedSongId = songId,
                    ),
                )
            } catch (e: Exception) {
                AppResult.failure(AppError.DatabaseFailure("createManualMarker"))
            }
        }

    override suspend fun deleteMarker(markerId: Long): AppResult<Unit> =
        withContext(dispatchers.io) {
            markerDao.getById(markerId)
                ?: return@withContext AppResult.failure(AppError.MarkerUnmatched(null))
            try {
                // Loeschen reicht: die Linkzeile faellt per Cascade mit.
                markerDao.deleteMarker(markerId)
                AppResult.success(Unit)
            } catch (e: Exception) {
                AppResult.failure(AppError.DatabaseFailure("deleteMarker"))
            }
        }

    companion object {
        // Trennzeichen, das in Dateinamen nicht vorkommt (US, 0x1F).
        private const val SEPARATOR = '\u001F'

        /** Standardlabel fuer manuelle Marker ohne eigene Eingabe (Phase 4). */
        const val DEFAULT_MANUAL_LABEL = "Drop"

        /**
         * Fachliche Identitaet eines Import-Tracks ohne Hash: so bleibt der
         * Fingerprint stabil, wenn ein spaeterer Import einen Hash ergaenzt.
         */
        fun fingerprintOf(track: ImportedTrack): String =
            listOf(
                track.relativePath,
                track.displayName,
                track.sizeBytes.toString(),
                track.durationMs.toString(),
            ).joinToString(SEPARATOR.toString())
    }
}
