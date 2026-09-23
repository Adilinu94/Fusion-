package com.dropsync.feature.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dropsync.core.common.AppError
import com.dropsync.core.common.AppResult
import com.dropsync.core.common.getOrNull
import com.dropsync.core.common.onFailure
import com.dropsync.core.common.onSuccess
import com.dropsync.core.model.PlaylistLabel
import com.dropsync.core.model.Song
import com.dropsync.core.model.SongMarker
import com.dropsync.domain.audio.TrackAnalysisRepository
import com.dropsync.domain.audio.WaveformBucket
import com.dropsync.domain.audio.WaveformDisplayGain
import com.dropsync.domain.library.Album
import com.dropsync.domain.library.Artist
import com.dropsync.domain.library.FolderScanResult
import com.dropsync.domain.library.Genre
import com.dropsync.domain.library.LibraryBrowseRepository
import com.dropsync.domain.library.LibraryFolder
import com.dropsync.domain.library.LibraryListConfig
import com.dropsync.domain.library.LibraryRepository
import com.dropsync.domain.library.LibraryViewConfig
import com.dropsync.domain.library.LibraryViewPreferencesRepository
import com.dropsync.domain.library.MarkerRepository
import com.dropsync.domain.library.MusicFolderFilterRepository
import com.dropsync.domain.library.Playlist
import com.dropsync.domain.library.PlaylistImportResult
import com.dropsync.domain.library.SmartShuffle
import com.dropsync.domain.library.SongPlayStat
import com.dropsync.domain.library.SongSort
import com.dropsync.domain.playback.PlaybackRepository
import com.dropsync.domain.playback.PlaybackState
import com.dropsync.domain.playback.QueueItem
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Sichtbarer Zustand der Bibliothek (Schritt 12.3: Berechtigung -> Bibliothek -> Play). */
enum class LibraryError { NONE, PERMISSION_MISSING, SCAN_FAILED }

/**
 * Befund 6.2: sichtbare Playlist-/Favoriten-/Such-/Abspielfehler als
 * Einmal-Ereignis — vorher waren diese Pfade stumm (der Dialog schloss
 * einfach, der Toggle wirkte nicht). Die Shell zeigt eine Snackbar.
 */
enum class PlaylistNotice {
    CREATE_FAILED,
    RENAME_FAILED,
    CHANGE_FAILED,
    RESTORE_FAILED,
    FAVORITE_FAILED,
    SEARCH_FAILED,
    PLAY_FAILED,
}

/** Fortschritt des laufenden Titels fuer die Library-Waveform (Phase 8). */
data class CurrentProgress(
    val songId: Long,
    val fraction: Float,
)

/** Fortschritt des laufenden Titels aus dem Player-Zustand; null ohne Titel. */
internal fun progressFromState(state: PlaybackState): CurrentProgress? {
    val songId = state.currentSongId ?: return null
    val fraction =
        if (state.durationMs > 0) {
            (state.positionMs.toFloat() / state.durationMs).coerceIn(0f, 1f)
        } else {
            0f
        }
    return CurrentProgress(songId, fraction)
}

/** Normalisierte Min/Max-Paare fuer die Mini-Waveform; null ohne Buckets. */
internal fun normalizeBuckets(buckets: List<WaveformBucket>): List<Pair<Float, Float>>? =
    buckets
        .takeIf { it.isNotEmpty() }
        ?.map { bucket -> bucket.min / 127f to bucket.max / 127f }

/** Auswaehlbare Bibliotheksansichten (Plan Phase 6, Punkt 2). */
enum class LibraryView {
    SONGS,
    ARTISTS,
    ALBUMS,
    GENRES,
    FOLDERS,
    FAVORITES,
    RECENTLY_ADDED,
    MOST_PLAYED,
    PLAYLISTS,
}

/** Aufgeklappte Detailliste einer Sammlung (Album/Kuenstler/Genre/Ordner). */
data class BucketDetail(
    val view: LibraryView,
    val key: String,
    val label: String,
)

/** B4: Gemerkter Playlist-Eintrag fuer Undo (Playlist + Song + alte Position). */
private data class RemovedPlaylistEntry(
    val playlistId: Long,
    val songId: Long,
    val position: Int,
)

/** B4: Gemerkte geloeschte Playlist fuer Undo (neue ID beim Wiederherstellen). */
private data class DeletedPlaylist(
    val name: String,
    val songIds: List<Long>,
    val label: PlaylistLabel?,
)

/** C4 (U-3): Quittung einer Review-Aktion; die Shell zeigt Snackbar + Undo. */
enum class MarkerReviewAction { CONFIRMED, DISCARDED }

/**
 * C4 (U-2): Drop-Abdeckung einer Playlist — aktive Marker vs. Titel und
 * offene Kandidaten ("9/12 Drops", "Noch 3 Marker pruefen").
 */
data class DropCoverage(
    val totalSongs: Int,
    val songsWithDrop: Int,
    val pendingReviews: Int,
)

/** C4 (U-2): Work-/Rest-Einstieg auf Music Home (Playlist mit Label). */
data class DropSyncCard(
    val playlistId: Long,
    val name: String,
    val label: PlaylistLabel,
    val coverage: DropCoverage,
)

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
@HiltViewModel
class LibraryViewModel
    @Inject
    constructor(
        private val libraryRepository: LibraryRepository,
        private val browseRepository: LibraryBrowseRepository,
        private val playbackRepository: PlaybackRepository,
        private val viewPreferences: LibraryViewPreferencesRepository,
        private val trackAnalysisRepository: TrackAnalysisRepository,
        private val folderFilter: MusicFolderFilterRepository,
        private val markerRepository: MarkerRepository,
    ) : ViewModel() {
        private val _error = MutableStateFlow(LibraryError.NONE)
        val error: StateFlow<LibraryError> = _error.asStateFlow()

        /** UI-Befund 4.2.2: Anzahl uebersprungener Duplikate beim Playlist-Hinzufuegen (0 = keins). */
        private val _duplicateSkips = MutableSharedFlow<Int>(extraBufferCapacity = 8)
        val duplicateSkips: SharedFlow<Int> = _duplicateSkips.asSharedFlow()

        /** UI-Befund 4.2.4: Ergebnis des SAF-Ordnerscans; null bei Fehler. */
        private val _folderScanResult = MutableSharedFlow<FolderScanResult?>(extraBufferCapacity = 4)
        val folderScanResult: SharedFlow<FolderScanResult?> = _folderScanResult.asSharedFlow()

        /** UI-Befund 4.2.4: Ergebnis des M3U-Imports; null bei Fehler. */
        private val _m3uImportResult = MutableSharedFlow<PlaylistImportResult?>(extraBufferCapacity = 4)
        val m3uImportResult: SharedFlow<PlaylistImportResult?> = _m3uImportResult.asSharedFlow()

        /** Befund 6.2: Playlist-/Favoriten-/Such-/Abspielfehler fuer die Snackbar. */
        private val _playlistNotice = MutableSharedFlow<PlaylistNotice>(extraBufferCapacity = 8)
        val playlistNotice: SharedFlow<PlaylistNotice> = _playlistNotice.asSharedFlow()

        private val _isRefreshing = MutableStateFlow(false)
        val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

        private val _detail = MutableStateFlow<BucketDetail?>(null)
        val detail: StateFlow<BucketDetail?> = _detail.asStateFlow()

        private val _searchQuery = MutableStateFlow("")
        val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

        val albums: StateFlow<List<Album>> = browseRepository.albums.asState(emptyList())
        val artists: StateFlow<List<Artist>> = browseRepository.artists.asState(emptyList())
        val genres: StateFlow<List<Genre>> = browseRepository.genres.asState(emptyList())
        val folders: StateFlow<List<LibraryFolder>> = browseRepository.folders.asState(emptyList())
        val favorites: StateFlow<List<Song>> = browseRepository.favorites.asState(emptyList())
        val recentlyAdded: StateFlow<List<Song>> = browseRepository.recentlyAdded().asState(emptyList())
        val recentlyPlayed: StateFlow<List<Song>> = browseRepository.recentlyPlayed().asState(emptyList())
        val mostPlayed: StateFlow<List<Song>> = browseRepository.mostPlayed().asState(emptyList())

        /** Rohe, ungefilterte Titelliste fuer die Kategorie "Alle Titel" (Poweramp-Umbau). */
        val allSongs: StateFlow<List<Song>> = libraryRepository.availableSongs.asState(emptyList())

        // --- Poweramp-Umbau Punkt 3: Ordnerauswahl + Kategorie-Sichtbarkeit ----

        /**
         * Alle bekannten Ordner (relative_path), auch abgewaehlte, als Quelle
         * des Ordnerauswahl-Dialogs. Speist sich aus [LibraryRepository.songs]
         * (inkl. nicht verfuegbarer Titel), damit ausgeschlossene Ordner
         * erneut waehlbar bleiben.
         */
        val allFolderPaths: StateFlow<List<String>> =
            libraryRepository.songs
                .map { songs ->
                    songs
                        .mapNotNull { it.relativePath.ifEmpty { null } }
                        .distinct()
                        .sorted()
                }.asState(emptyList())

        /** Aktuell abgewaehlte Ordner (Poweramp "Folders and Library"). */
        val excludedFolders: StateFlow<Set<String>> = folderFilter.excludedFolders.asState(emptySet())

        /** Persistierte Ansichts-Konfiguration; steuert die Kategorie-Sichtbarkeit der Startseite. */
        val viewConfig: StateFlow<LibraryViewConfig?> = viewPreferences.config.asState(null)

        /** Wiedergabestatistik je Titel-ID; Grundlage der Sortierung nach Zaehler/zuletzt. */
        val playStats: StateFlow<Map<Long, SongPlayStat>> =
            browseRepository.playStats
                .map { list -> list.associateBy { it.songId } }
                .asState(emptyMap())

        /** Aktuelle Warteschlange (Kategorie "Warteschlange", Poweramp-Umbau). */
        val queue: StateFlow<List<QueueItem>> =
            playbackRepository.state.map { it.queue }.asState(emptyList())

        /** Vollstaendiger Player-Zustand fuer den Now-Playing-Einstieg auf Music Home. */
        val playbackState: StateFlow<PlaybackState> = playbackRepository.state.asState(PlaybackState())

        /** Unbestaetigte Drop-Kandidaten werden dort geprueft, wo Musik verwaltet wird. */
        val pendingMarkerReviews: StateFlow<List<SongMarker>> =
            markerRepository.pendingAutoDetectedMarkers.asState(emptyList())

        // --- C4 (U-2/U-3): DropSync-Einstiege + Review-Rueckmeldung --------

        /**
         * D2 (LargeClass): Der geschlossene Marker-Review-Abschnitt liegt in
         * [LibraryMarkerReview]; hier stehen nur die Durchreichungen (die
         * UI-Vertraege des ViewModels).
         */
        private val markerReview =
            LibraryMarkerReview(
                browseRepository = browseRepository,
                markerRepository = markerRepository,
                playbackRepository = playbackRepository,
                trackAnalysisRepository = trackAnalysisRepository,
                scope = viewModelScope,
                pendingMarkers = { pendingMarkerReviews.value },
                songLookup = { id ->
                    allSongs.value.firstOrNull { it.mediaStoreId == id }
                        ?: libraryRepository.getSong(id).getOrNull()
                },
            )

        /** C4: Work-/Rest-Karten auf Music Home aus den gelabelten Playlisten. */
        val dropSyncCards: StateFlow<List<DropSyncCard>> = markerReview.dropSyncCards

        /** C4: Quittung fuer Snackbar+Undo nach Bestaetigen/Verwerfen. */
        val markerReviewFeedback: SharedFlow<MarkerReviewAction> = markerReview.feedback

        /** C4: Abdeckung einer Playlist fuer die Detailansicht. */
        fun dropCoverage(playlistId: Long): Flow<DropCoverage> = markerReview.dropCoverage(playlistId)

        /**
         * C4 (U-2): "DropSync verwenden" — startet die Playlist als
         * Warteschlange (Kandidatenquelle der Landung), ohne die Bibliothek
         * zu verlassen.
         */
        fun useWithDropSync(playlistId: Long) = markerReview.useWithDropSync(playlistId)

        /**
         * C4 (U-3)/C10: spielt einen Marker mit Vorlauf an (2,5 s davor)
         * und startet die Wiedergabe — der Drop soll hoerbar sein, nicht
         * erst nach dem Sprung beginnen.
         */
        fun previewMarker(marker: SongMarker) = markerReview.previewMarker(marker)

        /** C4 (U-3): stoesst die Onset-Erkennung fuer alle noch blinden Titel an. */
        fun detectDropsForPlaylist(playlistId: Long) = markerReview.detectDropsForPlaylist(playlistId)

        /** C4: true, solange eine Review-Aktion rueckgaengig gemacht werden kann. */
        fun hasMarkerReviewUndo(): Boolean = markerReview.hasUndo()

        /** C4: macht die letzte Review-Aktion rueckgaengig. */
        fun undoMarkerReview() = markerReview.undo()

        /**
         * Fortschritt des laufenden Titels fuer die Library-Waveform
         * (Phase 8): songId + gespielter Anteil [0..1]; null ohne Titel.
         */
        val currentProgress: StateFlow<CurrentProgress?> =
            playbackRepository.state.map(::progressFromState).asState(null)

        /**
         * Analyse-Waveform eines Songs als normalisierte Min/Max-Paare
         * fuer die Mini-Waveform in Listen (Phase 8); null bis zur
         * fertigen Analyse (oder dauerhaft ohne Cache-Eintrag). Es wird
         * nur gelesen, nie angestossen — die Analyse stösst der
         * PlayerViewModel beim Abspielen an (Import bleibt schnell).
         */
        fun waveformFor(songId: Long): Flow<List<Pair<Float, Float>>?> =
            trackAnalysisRepository.observeAnalysis(songId).map { analysis ->
                analysis
                    ?.waveformBuckets
                    ?.let { buckets ->
                        normalizeBuckets(buckets)?.let { normalized ->
                            // Phase 8: visuelle Lautheits-Normalisierung wie im Player.
                            val gain = WaveformDisplayGain.gainForPeak(analysis.peakLinear).toFloat()
                            normalized.map { (min, max) -> min * gain to max * gain }
                        }
                    }
            }

        /** Position des laufenden Titels in der Warteschlange; -1 wenn leer. */
        val queueIndex: StateFlow<Int> =
            playbackRepository.state.map { it.currentIndex }.asState(-1)

        /** Springt in der Warteschlange zu [index] und spielt dort. */
        fun playQueueIndex(index: Int) {
            viewModelScope.launch { playbackRepository.skipToQueueIndex(index) }
        }

        // --- Poweramp-Kategorien: Listen-Optionen (Sortierung/Ansicht) je Kategorie ----

        /**
         * Persistierte Listen-Optionen je Kategorie; leerer/fehlender Eintrag
         * faellt auf [defaultListConfig] zurueck. Ein StateFlow pro Kategorie,
         * einmal beim Erzeugen gebaut.
         */
        val listConfigs: Map<LibraryCategory, StateFlow<CategoryListConfig>> =
            LibraryCategory.entries.associateWith { category ->
                viewPreferences
                    .listConfig(category.key)
                    .map { stored -> stored?.toUi() ?: defaultListConfig(category) }
                    .stateIn(
                        viewModelScope,
                        SharingStarted.WhileSubscribed(5_000),
                        defaultListConfig(category),
                    )
            }

        fun setSortFor(
            category: LibraryCategory,
            sort: SongSort,
        ) {
            val current = listConfigs.getValue(category).value
            persistListConfig(category, current.copy(sort = sort))
        }

        fun setDescendingFor(
            category: LibraryCategory,
            descending: Boolean,
        ) {
            val current = listConfigs.getValue(category).value
            persistListConfig(category, current.copy(descending = descending))
        }

        fun setViewModeFor(
            category: LibraryCategory,
            mode: LibraryViewMode,
        ) {
            val current = listConfigs.getValue(category).value
            persistListConfig(category, current.copy(viewMode = mode))
        }

        private fun persistListConfig(
            category: LibraryCategory,
            config: CategoryListConfig,
        ) {
            viewModelScope.launch {
                viewPreferences.setListConfig(
                    category.key,
                    LibraryListConfig(
                        sortKey = config.sort.name,
                        descending = config.descending,
                        viewModeKey = config.viewMode.name,
                    ),
                )
            }
        }

        // --- Poweramp-Kategorien: Mehrfachauswahl (Langdruck) --------------------------

        private val _selectionActive = MutableStateFlow(false)
        val selectionActive: StateFlow<Boolean> = _selectionActive.asStateFlow()

        private val _selectedIds = MutableStateFlow<Set<Long>>(emptySet())
        val selectedIds: StateFlow<Set<Long>> = _selectedIds.asStateFlow()

        /** Startet den Auswahlmodus mit einem ersten Titel (Langdruck). */
        fun startSelection(songId: Long) {
            _selectionActive.value = true
            _selectedIds.value = setOf(songId)
        }

        /** Kippt die Auswahl eines Titels; leert sich die Menge, endet der Modus. */
        fun toggleSelection(songId: Long) {
            val next = _selectedIds.value.toMutableSet()
            if (!next.add(songId)) next.remove(songId)
            _selectedIds.value = next
            if (next.isEmpty()) _selectionActive.value = false
        }

        /** Waehlt alle [ids] aus (Kopf "Alle"); leere Liste beendet den Modus. */
        fun selectAll(ids: List<Long>) {
            _selectedIds.value = ids.toSet()
            _selectionActive.value = ids.isNotEmpty()
        }

        fun clearSelection() {
            _selectedIds.value = emptySet()
            _selectionActive.value = false
        }

        /** Reiht alle ausgewaehlten Titel aus [pool] ans Ende der Warteschlange. */
        fun addSelectionToQueue(pool: List<Song>) {
            val selected = pool.filter { it.mediaStoreId in _selectedIds.value }
            viewModelScope.launch { selected.forEach { playbackRepository.addToQueueEnd(it) } }
            clearSelection()
        }

        /** Spielt alle ausgewaehlten Titel als Naechstes (Reihenfolge wie [pool]). */
        fun playSelectionNext(pool: List<Song>) {
            val selected = pool.filter { it.mediaStoreId in _selectedIds.value }
            viewModelScope.launch { selected.asReversed().forEach { playbackRepository.playNext(it) } }
            clearSelection()
        }

        /** Fuegt alle ausgewaehlten Titel aus [pool] der Playlist [playlistId] hinzu. */
        fun addSelectionToPlaylist(
            playlistId: Long,
            pool: List<Song>,
        ) {
            val ids = pool.filter { it.mediaStoreId in _selectedIds.value }.map { it.mediaStoreId }
            viewModelScope.launch {
                browseRepository.addToPlaylist(playlistId, ids)
                    .onSuccess { added ->
                        val skipped = ids.size - added
                        if (skipped > 0) _duplicateSkips.tryEmit(skipped)
                    }
                    .onFailure { _playlistNotice.tryEmit(PlaylistNotice.CHANGE_FAILED) }
            }
            clearSelection()
        }

        /** Intelligentes Shuffle aktiv (A5)? Steuert [shufflePlay]; Schalter in den Einstellungen. */
        val smartShuffleEnabled: StateFlow<Boolean> = viewPreferences.smartShuffleEnabled.asState(false)

        /** Nutzerplaylisten (Musik-Workout-Kopplung Phase 1); Datenschicht existiert bereits. */
        val playlists: StateFlow<List<Playlist>> = browseRepository.playlists.asState(emptyList())

        private val selectedPlaylistId = MutableStateFlow<Long?>(null)

        /** B4: Zuletzt entfernter Playlist-Eintrag fuer Undo (null = keins offen). */
        private var lastRemovedPlaylistEntry: RemovedPlaylistEntry? = null

        /** B4: Zuletzt geloeschte Playlist fuer Undo (null = keins offen). */
        private var lastDeletedPlaylist: DeletedPlaylist? = null

        /** Aktuell geoeffnete Playlist-Detailansicht; null = Liste. */
        val openPlaylist: StateFlow<Playlist?> =
            combine(selectedPlaylistId, browseRepository.playlists) { id, all ->
                all.firstOrNull { it.id == id }
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

        /** Titel der geoeffneten Playlist in gespeicherter Reihenfolge. */
        val playlistSongs: StateFlow<List<Song>> =
            selectedPlaylistId
                .flatMapLatest { id ->
                    if (id == null) flowOf(emptyList()) else browseRepository.songsOfPlaylist(id)
                }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

        /** IDs favorisierter Songs; erlaubt jedem Listeneintrag ein Herz-Toggle. */
        val favoriteIds: StateFlow<Set<Long>> =
            browseRepository.favorites
                .map { list -> list.map { it.mediaStoreId }.toSet() }
                .asState(emptySet())

        /** Songs der aufgeklappten Sammlung; leer solange keine Detailansicht offen ist. */
        val detailSongs: StateFlow<List<Song>> =
            _detail
                .flatMapLatest { detail ->
                    when (detail?.view) {
                        LibraryView.ALBUMS -> browseRepository.songsByAlbum(detail.key)
                        LibraryView.ARTISTS -> browseRepository.songsByArtist(detail.key)
                        LibraryView.GENRES -> browseRepository.songsByGenre(detail.key)
                        LibraryView.FOLDERS -> browseRepository.songsByFolder(detail.key)
                        else -> flowOf(emptyList())
                    }
                }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

        /** Suchergebnisse; leerer Query liefert eine leere Liste. */
        val searchResults: StateFlow<List<Song>> =
            _searchQuery
                .debounce(250)
                .flatMapLatest { query ->
                    if (query.isBlank()) {
                        flowOf(emptyList())
                    } else {
                        flowOf(runSearch(query))
                    }
                }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

        private suspend fun runSearch(query: String): List<Song> {
            var result = emptyList<Song>()
            browseRepository.search(query)
                .onSuccess { result = it }
                .onFailure { _playlistNotice.tryEmit(PlaylistNotice.SEARCH_FAILED) }
            return result
        }

        fun openBucket(
            view: LibraryView,
            key: String,
            label: String,
        ) {
            _detail.value = BucketDetail(view, key, label)
        }

        fun closeDetail() {
            _detail.value = null
        }

        fun setSearchQuery(query: String) {
            _searchQuery.value = query
        }

        fun toggleFavorite(songId: Long) {
            val makeFavorite = songId !in favoriteIds.value
            viewModelScope.launch {
                browseRepository.setFavorite(songId, makeFavorite)
                    .onFailure { _playlistNotice.tryEmit(PlaylistNotice.FAVORITE_FAILED) }
            }
        }

        /** Nach erteilter Berechtigung oder Pull-to-Refresh. */
        fun refresh(force: Boolean = false) {
            viewModelScope.launch {
                _isRefreshing.value = true
                libraryRepository
                    .refreshLibrary(force)
                    .onSuccess { _error.value = LibraryError.NONE }
                    .onFailure { error ->
                        _error.value =
                            if (error is AppError.PermissionDenied) {
                                LibraryError.PERMISSION_MISSING
                            } else {
                                LibraryError.SCAN_FAILED
                            }
                    }
                _isRefreshing.value = false
            }
        }

        /**
         * SAF-Ordnerscan (UI-Befund 4.2.4): indexiert eine vom Nutzer gewaehlte
         * SD-Karte/USB-Quelle inkl. CUE-Sheets; Ergebnis als Snackbar-Zeile.
         */
        fun scanSafFolder(treeUri: String) {
            viewModelScope.launch {
                libraryRepository
                    .scanFolder(treeUri)
                    .onSuccess { result ->
                        _folderScanResult.tryEmit(result)
                    }.onFailure { _folderScanResult.tryEmit(null) }
            }
        }

        /**
         * M3U-Import (UI-Befund 4.2.4): liest eine vom Nutzer gewaehlte
         * Playlist-Datei und legt sie als lokale Playlist an.
         */
        fun importM3u(
            name: String,
            m3uText: String,
        ) {
            viewModelScope.launch {
                browseRepository
                    .importM3uPlaylist(name, m3uText)
                    .onSuccess { result ->
                        _m3uImportResult.tryEmit(result)
                    }.onFailure { _m3uImportResult.tryEmit(null) }
            }
        }

        /** UI-Befund 4.2.4: M3U-Datei war leer/fehlerhaft; als Fehler melden. */
        fun onM3uReadFailed() {
            _m3uImportResult.tryEmit(null)
        }

        /**
         * Speichert die Ordnerauswahl (Poweramp "Folders and Library") und
         * liest die Bibliothek anschliessend neu ein, damit abgewaehlte Ordner
         * sofort verschwinden und neu aufgenommene wieder erscheinen.
         */
        fun setExcludedFolders(paths: Set<String>) {
            viewModelScope.launch {
                folderFilter.setExcludedFolders(paths)
                refresh(force = true)
            }
        }

        /** Blendet eine Kategorie auf der Startseite ein/aus (Poweramp Listenoptionen). */
        fun setCategoryVisible(
            category: LibraryCategory,
            visible: Boolean,
        ) {
            viewModelScope.launch {
                val currentHidden = viewConfig.value?.hiddenKeys ?: emptySet()
                val nextHidden =
                    if (visible) currentHidden - category.key else currentHidden + category.key
                viewPreferences.setConfig(
                    LibraryViewConfig(
                        orderedKeys = LibraryCategory.entries.map { it.key },
                        hiddenKeys = nextHidden,
                    ),
                )
            }
        }

        /** Ersetzt die Queue durch [list] und startet bei [index]; zaehlt die Wiedergabe. */
        fun play(
            list: List<Song>,
            index: Int,
        ) {
            if (index !in list.indices) return
            viewModelScope.launch {
                browseRepository.recordPlayback(list[index].mediaStoreId)
                playbackRepository.setQueue(list, index, playWhenReady = true)
                    .onFailure { _playlistNotice.tryEmit(PlaylistNotice.PLAY_FAILED) }
            }
        }

        /**
         * Spielt [list] zufaellig ab (A5). Ist das intelligente Shuffle aktiv,
         * ordnet [SmartShuffle] ueber play_stats/Favoriten und meidet zuletzt
         * Gespielte; sonst dient die einfache Zufallsreihenfolge als Fallback.
         */
        fun shufflePlay(list: List<Song>) {
            if (list.isEmpty()) return
            viewModelScope.launch {
                val ordered =
                    if (smartShuffleEnabled.value) {
                        val byId = list.associateBy { it.mediaStoreId }
                        var ids = emptyList<Long>()
                        browseRepository
                            .shuffleCandidates(list.map { it.mediaStoreId })
                            .onSuccess { ids = SmartShuffle.order(it, System.currentTimeMillis()) }
                        ids.mapNotNull { byId[it] }.ifEmpty { list.shuffled() }
                    } else {
                        list.shuffled()
                    }
                browseRepository.recordPlayback(ordered.first().mediaStoreId)
                playbackRepository.setQueue(ordered, 0, playWhenReady = true)
                    .onFailure { _playlistNotice.tryEmit(PlaylistNotice.PLAY_FAILED) }
            }
        }

        /** Reiht [song] direkt hinter dem laufenden Titel ein ("als Naechstes"). */
        fun playNext(song: Song) {
            viewModelScope.launch { playbackRepository.playNext(song) }
        }

        /** Haengt [song] ans Ende der Warteschlange an. */
        fun addToQueue(song: Song) {
            viewModelScope.launch { playbackRepository.addToQueueEnd(song) }
        }

        /**
         * Stoesst die Onset-Erkennung (A2, Marker/Waveform-Plan Phase 5) fuer
         * [song] an — explizit vom Nutzer ueber das Kontextmenue ("Drops
         * automatisch erkennen"). Kandidaten erscheinen als unbestaetigte
         * AUTO_DETECTED-Marker in der Review-Liste der Einstellungen.
         */
        fun detectDrops(song: Song) = markerReview.detectDrops(song)

        /** C4 (U-3): bestaetigt einen Kandidaten und merkt ihn fuer Undo. */
        fun confirmMarker(markerId: Long) = markerReview.confirmMarker(markerId)

        /** C4 (U-3): verwirft einen Kandidaten und merkt ihn fuer Undo. */
        fun discardMarker(markerId: Long) = markerReview.discardMarker(markerId)

        // --- Playlist-Aktionen (Musik-Workout-Kopplung Phase 1) ---------------

        /** Oeffnet die Detailansicht einer Playlist. */
        fun openPlaylist(playlistId: Long) {
            selectedPlaylistId.value = playlistId
        }

        /** Schliesst die Playlist-Detailansicht. */
        fun closePlaylist() {
            selectedPlaylistId.value = null
        }

        /** Legt eine neue Playlist an; leerer Name wird ignoriert. */
        fun createPlaylist(name: String) {
            val trimmed = name.trim()
            if (trimmed.isEmpty()) return
            viewModelScope.launch {
                browseRepository.createPlaylist(trimmed)
                    .onFailure { _playlistNotice.tryEmit(PlaylistNotice.CREATE_FAILED) }
            }
        }

        /** Legt eine Playlist an und fuegt [song] direkt hinzu. */
        fun createPlaylistWithSong(
            name: String,
            song: Song,
        ) {
            val trimmed = name.trim()
            if (trimmed.isEmpty()) return
            viewModelScope.launch {
                browseRepository.createPlaylist(trimmed)
                    .onSuccess { id ->
                        browseRepository.addToPlaylist(id, listOf(song.mediaStoreId))
                            .onFailure { _playlistNotice.tryEmit(PlaylistNotice.CHANGE_FAILED) }
                    }
                    .onFailure { _playlistNotice.tryEmit(PlaylistNotice.CREATE_FAILED) }
            }
        }

        /** Benennt eine Playlist um; leerer Name wird ignoriert. */
        fun renamePlaylist(
            playlistId: Long,
            name: String,
        ) {
            val trimmed = name.trim()
            if (trimmed.isEmpty()) return
            viewModelScope.launch {
                browseRepository.renamePlaylist(playlistId, trimmed)
                    .onFailure { _playlistNotice.tryEmit(PlaylistNotice.RENAME_FAILED) }
            }
        }

        /**
         * Loescht eine Playlist und schliesst ggf. deren Detailansicht.
         *
         * Suspend statt Fire-and-forget (B4): Die UI zeigt erst danach Undo an —
         * sonst wuerde `hasPlaylistUndo()` noch den alten Stand lesen und ein
         * schnelles Undo liefe ins Leere (oder stellte spaeter die falsche
         * Playlist wieder her).
         */
        suspend fun deletePlaylist(playlistId: Long) {
            if (selectedPlaylistId.value == playlistId) selectedPlaylistId.value = null
            // B4: Stand fuer Undo merken (Name, Songs, Label).
            val info = playlists.value.find { it.id == playlistId }
            val songIds = browseRepository.songsOfPlaylist(playlistId).first().map { it.mediaStoreId }
            browseRepository.deletePlaylist(playlistId)
                .onSuccess { lastDeletedPlaylist = info?.let { DeletedPlaylist(it.name, songIds, it.label) } }
                .onFailure { _playlistNotice.tryEmit(PlaylistNotice.CHANGE_FAILED) }
        }

        /** B4: true, solange eine geloeschte Playlist wiederherstellbar ist. */
        fun hasPlaylistUndo(): Boolean = lastDeletedPlaylist != null

        /**
         * B4: Legt die geloeschte Playlist mit Songs und Label wieder an
         * (neue ID — Verknuepfungen anderswo zeigen auf die alte).
         */
        fun undoDeletePlaylist() {
            val deleted = lastDeletedPlaylist ?: return
            lastDeletedPlaylist = null
            viewModelScope.launch {
                browseRepository.createPlaylist(deleted.name)
                    .onSuccess { id ->
                        if (deleted.songIds.isNotEmpty()) {
                            browseRepository.addToPlaylist(id, deleted.songIds)
                                .onFailure { _playlistNotice.tryEmit(PlaylistNotice.RESTORE_FAILED) }
                        }
                        browseRepository.setPlaylistLabel(id, deleted.label)
                            .onFailure { _playlistNotice.tryEmit(PlaylistNotice.RESTORE_FAILED) }
                    }
                    .onFailure { _playlistNotice.tryEmit(PlaylistNotice.RESTORE_FAILED) }
            }
        }

        /** Fuegt [song] der Playlist [playlistId] hinzu; Duplikate werden gemeldet. */
        fun addSongToPlaylist(
            playlistId: Long,
            song: Song,
        ) {
            viewModelScope.launch {
                browseRepository.addToPlaylist(playlistId, listOf(song.mediaStoreId))
                    .onSuccess { added ->
                        if (added == 0) _duplicateSkips.tryEmit(1)
                    }
                    .onFailure { _playlistNotice.tryEmit(PlaylistNotice.CHANGE_FAILED) }
            }
        }

        /** Entfernt den Eintrag an [position] aus der Playlist. */
        fun removeFromPlaylist(
            playlistId: Long,
            position: Int,
            songId: Long,
        ) {
            lastRemovedPlaylistEntry = RemovedPlaylistEntry(playlistId, songId, position)
            viewModelScope.launch {
                browseRepository.removeFromPlaylist(playlistId, position)
                    .onFailure { _playlistNotice.tryEmit(PlaylistNotice.CHANGE_FAILED) }
            }
        }

        /** B4: true, solange ein entfernter Playlist-Eintrag wiederherstellbar ist. */
        fun hasPlaylistEntryUndo(): Boolean = lastRemovedPlaylistEntry != null

        /**
         * B4: Haengt den entfernten Eintrag wieder an und schiebt ihn an die
         * alte Position (Best-Effort: ohne Insert-API ueber Ende + Move).
         */
        fun undoRemoveFromPlaylist() {
            val removed = lastRemovedPlaylistEntry ?: return
            lastRemovedPlaylistEntry = null
            viewModelScope.launch {
                val sizeBefore = playlistSongs.value.size
                // Undo umgeht bewusst den Duplikat-Schutz (der Eintrag wurde
                // gerade entfernt und ist deshalb nicht mehr in der Playlist);
                // schlaegt das Wiedereinfuegen fehl, bricht ab statt doppelt.
                val reinserted =
                    browseRepository.addToPlaylist(removed.playlistId, listOf(removed.songId))
                if (reinserted !is AppResult.Success) {
                    _playlistNotice.tryEmit(PlaylistNotice.RESTORE_FAILED)
                    return@launch
                }
                val target = removed.position.coerceAtMost(sizeBefore)
                if (target < sizeBefore) {
                    browseRepository.moveInPlaylist(removed.playlistId, sizeBefore, target)
                        .onFailure { _playlistNotice.tryEmit(PlaylistNotice.RESTORE_FAILED) }
                }
            }
        }

        /** Verschiebt einen Playlist-Eintrag; Reihenfolge bleibt lueckenlos. */
        fun moveInPlaylist(
            playlistId: Long,
            fromPosition: Int,
            toPosition: Int,
        ) {
            viewModelScope.launch {
                browseRepository.moveInPlaylist(playlistId, fromPosition, toPosition)
                    .onFailure { _playlistNotice.tryEmit(PlaylistNotice.CHANGE_FAILED) }
            }
        }

        /** Setzt oder entfernt (null) das Workout-Label einer Playlist (Phase 2). */
        fun setPlaylistLabel(
            playlistId: Long,
            label: PlaylistLabel?,
        ) {
            viewModelScope.launch {
                browseRepository.setPlaylistLabel(playlistId, label)
                    .onFailure { _playlistNotice.tryEmit(PlaylistNotice.CHANGE_FAILED) }
            }
        }

        private fun <T> Flow<T>.asState(initial: T): StateFlow<T> =
            stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), initial)
    }
