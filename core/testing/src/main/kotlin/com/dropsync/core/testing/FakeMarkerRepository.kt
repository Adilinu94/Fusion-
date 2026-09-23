package com.dropsync.core.testing

import com.dropsync.core.common.AppError
import com.dropsync.core.common.AppResult
import com.dropsync.core.model.SongMarker
import com.dropsync.domain.library.ImportReport
import com.dropsync.domain.library.ImportedTrack
import com.dropsync.domain.library.MarkerRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow

/**
 * Steuerbarer Marker-Fake (C4): Review-Liste, Abdeckung und die
 * Undo-Pfade (setMarkerEnabled/restoreMarker) sind beobachtbar.
 */
class FakeMarkerRepository : MarkerRepository {
    val pendingFlow = MutableStateFlow<List<SongMarker>>(emptyList())
    val songsWithMarkersFlow = MutableStateFlow<Set<Long>>(emptySet())
    val enabledMarkersBySong = mutableMapOf<Long, List<SongMarker>>()

    val confirmCalls = mutableListOf<Long>()
    val deleteCalls = mutableListOf<Long>()
    val enabledCalls = mutableListOf<Pair<Long, Boolean>>()
    val restoredMarkers = mutableListOf<SongMarker>()

    /** D5/A7: Batch-Aufrufe (Liste der Song-IDs je Aufruf). */
    val batchMarkerCalls = mutableListOf<List<Long>>()

    /** D5/A8: Flow-Abos je Song-ID. */
    val observedMarkerSongs = mutableListOf<Long>()

    override val unmatchedMarkers: Flow<List<SongMarker>> = emptyFlow()

    override val pendingAutoDetectedMarkers: Flow<List<SongMarker>> = pendingFlow

    override val songsWithEnabledMarkers: Flow<Set<Long>> = songsWithMarkersFlow

    override suspend fun importDocument(
        schemaVersion: Int,
        tracks: List<ImportedTrack>,
    ): AppResult<ImportReport> = AppResult.failure(AppError.Unknown("nicht Teil dieses Tests"))

    override suspend fun linkManually(
        markerId: Long,
        songId: Long,
    ): AppResult<Unit> = AppResult.Success(Unit)

    override suspend fun getEnabledMarkersForSong(songId: Long): AppResult<List<SongMarker>> =
        AppResult.Success(enabledMarkersBySong[songId].orEmpty())

    // D5/A7: Batch-Abfrage aus denselben steuerbaren Daten.
    override suspend fun getEnabledMarkersForSongs(songIds: List<Long>): AppResult<Map<Long, List<SongMarker>>> {
        batchMarkerCalls += songIds
        return AppResult.Success(
            songIds.mapNotNull { id -> enabledMarkersBySong[id]?.let { id to it } }.toMap(),
        )
    }

    // D5/A8: Flow-Abo je Song; zaehlt die Abos fuer den Gate-Test.
    override fun observeEnabledMarkersForSong(songId: Long): Flow<List<SongMarker>> {
        observedMarkerSongs += songId
        return MutableStateFlow(enabledMarkersBySong[songId].orEmpty())
    }

    override suspend fun createManualMarker(
        songId: Long,
        label: String,
        positionMs: Long,
    ): AppResult<SongMarker> = AppResult.failure(AppError.Unknown("nicht Teil dieses Tests"))

    override suspend fun deleteMarker(markerId: Long): AppResult<Unit> {
        deleteCalls += markerId
        return AppResult.Success(Unit)
    }

    override suspend fun confirmMarker(markerId: Long): AppResult<Unit> {
        confirmCalls += markerId
        return AppResult.Success(Unit)
    }

    override suspend fun setMarkerEnabled(
        markerId: Long,
        enabled: Boolean,
    ): AppResult<Unit> {
        enabledCalls += markerId to enabled
        return AppResult.Success(Unit)
    }

    override suspend fun restoreMarker(marker: SongMarker): AppResult<Unit> {
        restoredMarkers += marker
        return AppResult.Success(Unit)
    }

    override suspend fun moveMarker(
        markerId: Long,
        newPositionMs: Long,
    ): AppResult<Unit> = AppResult.Success(Unit)

    override suspend fun renameMarker(
        markerId: Long,
        newLabel: String,
    ): AppResult<Unit> = AppResult.Success(Unit)
}
