package com.dropsync.feature.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dropsync.core.common.AppError
import com.dropsync.core.common.onFailure
import com.dropsync.core.common.onSuccess
import com.dropsync.core.model.PlaylistLabel
import com.dropsync.core.model.Song
import com.dropsync.domain.audio.TrackAnalysisRepository
import com.dropsync.domain.audio.WaveformBucket
import com.dropsync.domain.library.Album
import com.dropsync.domain.library.Artist
import com.dropsync.domain.library.Genre
import com.dropsync.domain.library.LibraryBrowseRepository
import com.dropsync.domain.library.LibraryFolder
import com.dropsync.domain.library.LibraryListConfig
import com.dropsync.domain.library.LibraryRepository
import com.dropsync.domain.library.LibraryViewConfig
import com.dropsync.domain.library.LibraryViewPreferencesRepository
import com.dropsync.domain.library.MusicFolderFilterRepository
import com.dropsync.domain.library.Playlist
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Sichtbarer Zustand der Bibliothek (Schritt 12.3: Berechtigung -> Bibliothek -> Play). */
enum class LibraryError { NONE, PERMISSION_MISSING, SCAN_FAILED }

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
    ) : ViewModel() {
        private val _error = MutableStateFlow(LibraryError.NONE)
        val error: StateFlow<LibraryError> = _error.asStateFlow()

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
                analysis?.waveformBuckets?.let(::normalizeBuckets)
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
            viewModelScope.launch { browseRepository.addToPlaylist(playlistId, ids) }
            clearSelection()
        }

        /** Intelligentes Shuffle aktiv (A5)? Steuert [shufflePlay]; Schalter in den Einstellungen. */
        val smartShuffleEnabled: StateFlow<Boolean> = viewPreferences.smartShuffleEnabled.asState(false)

        /** Nutzerplaylisten (Musik-Workout-Kopplung Phase 1); Datenschicht existiert bereits. */
        val playlists: StateFlow<List<Playlist>> = browseRepository.playlists.asState(emptyList())

        private val selectedPlaylistId = MutableStateFlow<Long?>(null)

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
            browseRepository.search(query).onSuccess { result = it }
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
            viewModelScope.launch { browseRepository.setFavorite(songId, makeFavorite) }
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
        fun detectDrops(song: Song) {
            viewModelScope.launch { trackAnalysisRepository.requestOnsetDetection(song) }
        }

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
            viewModelScope.launch { browseRepository.createPlaylist(trimmed) }
        }

        /** Legt eine Playlist an und fuegt [song] direkt hinzu. */
        fun createPlaylistWithSong(
            name: String,
            song: Song,
        ) {
            val trimmed = name.trim()
            if (trimmed.isEmpty()) return
            viewModelScope.launch {
                browseRepository.createPlaylist(trimmed).onSuccess { id ->
                    browseRepository.addToPlaylist(id, listOf(song.mediaStoreId))
                }
            }
        }

        /** Benennt eine Playlist um; leerer Name wird ignoriert. */
        fun renamePlaylist(
            playlistId: Long,
            name: String,
        ) {
            val trimmed = name.trim()
            if (trimmed.isEmpty()) return
            viewModelScope.launch { browseRepository.renamePlaylist(playlistId, trimmed) }
        }

        /** Loescht eine Playlist und schliesst ggf. deren Detailansicht. */
        fun deletePlaylist(playlistId: Long) {
            if (selectedPlaylistId.value == playlistId) selectedPlaylistId.value = null
            viewModelScope.launch { browseRepository.deletePlaylist(playlistId) }
        }

        /** Fuegt [song] der Playlist [playlistId] hinzu. */
        fun addSongToPlaylist(
            playlistId: Long,
            song: Song,
        ) {
            viewModelScope.launch { browseRepository.addToPlaylist(playlistId, listOf(song.mediaStoreId)) }
        }

        /** Entfernt den Eintrag an [position] aus der Playlist. */
        fun removeFromPlaylist(
            playlistId: Long,
            position: Int,
        ) {
            viewModelScope.launch { browseRepository.removeFromPlaylist(playlistId, position) }
        }

        /** Verschiebt einen Playlist-Eintrag; Reihenfolge bleibt lueckenlos. */
        fun moveInPlaylist(
            playlistId: Long,
            fromPosition: Int,
            toPosition: Int,
        ) {
            viewModelScope.launch { browseRepository.moveInPlaylist(playlistId, fromPosition, toPosition) }
        }

        /** Setzt oder entfernt (null) das Workout-Label einer Playlist (Phase 2). */
        fun setPlaylistLabel(
            playlistId: Long,
            label: PlaylistLabel?,
        ) {
            viewModelScope.launch { browseRepository.setPlaylistLabel(playlistId, label) }
        }

        private fun <T> Flow<T>.asState(initial: T): StateFlow<T> =
            stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), initial)
    }
