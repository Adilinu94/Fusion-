package com.dropsync.feature.library

import com.dropsync.core.model.Song
import com.dropsync.core.model.SongMarker
import com.dropsync.domain.audio.TrackAnalysisRepository
import com.dropsync.domain.library.LibraryBrowseRepository
import com.dropsync.domain.library.MarkerRepository
import com.dropsync.domain.playback.PlaybackRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** C4/C10: Vorlauf beim Anhoeren eines Markers ("ab Marker minus 2-3 s"). */
internal const val MARKER_PREVIEW_LEAD_MS = 2_500L

/**
 * C4 (U-2/U-3)/C10: Marker-Review und DropSync-Einstiege der Musik-Bibliothek.
 *
 * D2 (LargeClass): Der Abschnitt ist in sich geschlossen — er liest
 * Playlists/Marker, startet Wiedergabe bzw. Analyse und quittiert
 * Review-Aktionen — und liegt deshalb als eigener Baustein neben dem
 * [LibraryViewModel] statt in ihm; der ViewModel reicht nur durch. Der
 * geteilte Scope ist bewusst derselbe (`viewModelScope`), damit die
 * Sharing-Strategie der Fluesse identisch bleibt.
 */
internal class LibraryMarkerReview(
    private val browseRepository: LibraryBrowseRepository,
    private val markerRepository: MarkerRepository,
    private val playbackRepository: PlaybackRepository,
    private val trackAnalysisRepository: TrackAnalysisRepository,
    private val scope: CoroutineScope,
    /** Aktuelle Kandidatenliste des ViewModels (dieselbe Quelle wie die UI). */
    private val pendingMarkers: () -> List<SongMarker>,
    /** Titel-Lookup fuer das Anhoeren (Bibliothek zuerst, DB als Rueckfall). */
    private val songLookup: suspend (Long) -> Song?,
) {
    /** C4: Work-/Rest-Karten auf Music Home aus den gelabelten Playlisten. */
    val dropSyncCards: StateFlow<List<DropSyncCard>> =
        combine(
            browseRepository.playlists,
            markerRepository.songsWithEnabledMarkers,
            markerRepository.pendingAutoDetectedMarkers,
        ) { playlists, withMarkers, pending -> Triple(playlists, withMarkers, pending) }
            .map { (playlists, withMarkers, pending) ->
                playlists.mapNotNull { playlist ->
                    val label = playlist.label ?: return@mapNotNull null
                    DropSyncCard(
                        playlistId = playlist.id,
                        name = playlist.name,
                        label = label,
                        coverage = coverageOf(playlist.id, withMarkers, pending),
                    )
                }
            }.stateIn(scope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** C4: Quittung fuer Snackbar+Undo nach Bestaetigen/Verwerfen. */
    private val _feedback = MutableSharedFlow<MarkerReviewAction>(extraBufferCapacity = 8)
    val feedback: SharedFlow<MarkerReviewAction> = _feedback.asSharedFlow()

    /** C4: zuletzt abgeschlossene Review-Aktion fuer Undo (null = keine). */
    private var lastReview: Pair<MarkerReviewAction, SongMarker>? = null

    /** C4: Abdeckung einer Playlist fuer die Detailansicht. */
    fun dropCoverage(playlistId: Long): Flow<DropCoverage> =
        combine(
            browseRepository.songsOfPlaylist(playlistId),
            markerRepository.songsWithEnabledMarkers,
            markerRepository.pendingAutoDetectedMarkers,
        ) { _, withMarkers, pending -> coverageOf(playlistId, withMarkers, pending) }

    private suspend fun coverageOf(
        playlistId: Long,
        withMarkers: Set<Long>,
        pending: List<SongMarker>,
    ): DropCoverage {
        val songs = browseRepository.songsOfPlaylist(playlistId).first()
        val ids = songs.map { it.mediaStoreId }.toSet()
        return DropCoverage(
            totalSongs = songs.size,
            songsWithDrop = ids.count { it in withMarkers },
            pendingReviews = pending.count { it.linkedSongId in ids },
        )
    }

    /**
     * C4 (U-2): "DropSync verwenden" — startet die Playlist als
     * Warteschlange (Kandidatenquelle der Landung), ohne die Bibliothek
     * zu verlassen.
     */
    fun useWithDropSync(playlistId: Long) {
        scope.launch {
            val songs = browseRepository.songsOfPlaylist(playlistId).first()
            if (songs.isEmpty()) return@launch
            browseRepository.recordPlayback(songs.first().mediaStoreId)
            playbackRepository.setQueue(songs, 0, playWhenReady = true)
        }
    }

    /**
     * C4 (U-3)/C10: spielt einen Marker mit Vorlauf an (2,5 s davor)
     * und startet die Wiedergabe — der Drop soll hoerbar sein, nicht
     * erst nach dem Sprung beginnen.
     */
    fun previewMarker(marker: SongMarker) {
        val songId = marker.linkedSongId ?: return
        scope.launch {
            val song = songLookup(songId) ?: return@launch
            playbackRepository.playSongAt(
                song,
                (marker.positionMs - MARKER_PREVIEW_LEAD_MS).coerceAtLeast(0L),
            )
        }
    }

    /** C4 (U-3): stoesst die Onset-Erkennung fuer alle noch blinden Titel an. */
    fun detectDropsForPlaylist(playlistId: Long) {
        scope.launch {
            val songs = browseRepository.songsOfPlaylist(playlistId).first()
            val withMarkers = markerRepository.songsWithEnabledMarkers.first()
            songs
                .filter { it.mediaStoreId !in withMarkers }
                .forEach { trackAnalysisRepository.requestOnsetDetection(it) }
        }
    }

    /** C4: Onset-Erkennung fuer einen einzelnen Titel (Home-Karte). */
    fun detectDrops(song: Song) {
        scope.launch { trackAnalysisRepository.requestOnsetDetection(song) }
    }

    /** C4: true, solange eine Review-Aktion rueckgaengig gemacht werden kann. */
    fun hasUndo(): Boolean = lastReview != null

    /** C4: macht die letzte Review-Aktion rueckgaengig. */
    fun undo() {
        val (action, marker) = lastReview ?: return
        lastReview = null
        scope.launch {
            when (action) {
                MarkerReviewAction.CONFIRMED -> markerRepository.setMarkerEnabled(marker.id, false)
                MarkerReviewAction.DISCARDED -> markerRepository.restoreMarker(marker)
            }
        }
    }

    /** C4 (U-3): bestaetigt einen Kandidaten und merkt ihn fuer Undo. */
    fun confirmMarker(markerId: Long) {
        val marker = pendingMarkers().firstOrNull { it.id == markerId }
        scope.launch {
            markerRepository.confirmMarker(markerId)
            if (marker != null) {
                lastReview = MarkerReviewAction.CONFIRMED to marker
                _feedback.tryEmit(MarkerReviewAction.CONFIRMED)
            }
        }
    }

    /** C4 (U-3): verwirft einen Kandidaten und merkt ihn fuer Undo. */
    fun discardMarker(markerId: Long) {
        val marker = pendingMarkers().firstOrNull { it.id == markerId }
        scope.launch {
            markerRepository.deleteMarker(markerId)
            if (marker != null) {
                lastReview = MarkerReviewAction.DISCARDED to marker
                _feedback.tryEmit(MarkerReviewAction.DISCARDED)
            }
        }
    }
}
