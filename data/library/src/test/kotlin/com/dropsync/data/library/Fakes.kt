package com.dropsync.data.library

import com.dropsync.core.database.TransactionRunner
import com.dropsync.core.database.dao.CueTrackDao
import com.dropsync.core.database.dao.MarkerDao
import com.dropsync.core.database.dao.PendingMarkerRow
import com.dropsync.core.database.dao.SafFileDao
import com.dropsync.core.database.dao.SongDao
import com.dropsync.core.database.entity.CueTrackEntity
import com.dropsync.core.database.entity.MarkerSongLinkEntity
import com.dropsync.core.database.entity.SafFileEntity
import com.dropsync.core.database.entity.SongEntity
import com.dropsync.core.database.entity.SongMarkerEntity
import com.dropsync.core.model.Song
import com.dropsync.domain.audio.TrackAnalysis
import com.dropsync.domain.audio.TrackAnalysisRepository
import com.dropsync.domain.library.MusicFolderFilterRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

// In-Memory-Fakes fuer JVM-Repositorytests: gleiche Vertraege wie die
// Room-DAOs, aber ohne Android. Atomaritaet echter Transaktionen wird
// separat in :core:database mit Room getestet.

class FakeSongDao : SongDao {
    val rows = linkedMapOf<Long, SongEntity>()
    private val state = MutableStateFlow<List<SongEntity>>(emptyList())

    private fun emit() {
        state.value = rows.values.toList()
    }

    override suspend fun upsertAll(songs: List<SongEntity>) {
        for (song in songs) rows[song.mediaStoreId] = song
        emit()
    }

    override suspend fun getById(mediaStoreId: Long): SongEntity? = rows[mediaStoreId]

    override suspend fun getAllOnce(): List<SongEntity> = rows.values.toList()

    override suspend fun setKnownSha256(
        mediaStoreId: Long,
        sha256: String,
    ) {
        rows[mediaStoreId]?.let { rows[mediaStoreId] = it.copy(knownSha256 = sha256) }
        emit()
    }

    override fun observeAvailable(): Flow<List<SongEntity>> = state.map { list -> list.filter { it.isAvailable } }

    override fun observeAll(): Flow<List<SongEntity>> = state

    override suspend fun markMissingAsUnavailable(presentIds: List<Long>) {
        for ((id, song) in rows) {
            if (id !in presentIds) rows[id] = song.copy(isAvailable = false)
        }
        emit()
    }

    override suspend fun setAvailability(
        mediaStoreId: Long,
        isAvailable: Boolean,
    ) {
        rows[mediaStoreId]?.let { rows[mediaStoreId] = it.copy(isAvailable = isAvailable) }
        emit()
    }
}

class FakeMarkerDao : MarkerDao {
    val markers = linkedMapOf<Long, SongMarkerEntity>()
    val links = linkedMapOf<Long, MarkerSongLinkEntity>()
    private var nextMarkerId = 1L
    private var nextLinkId = 1L
    private val unmatchedState = MutableStateFlow<List<SongMarkerEntity>>(emptyList())
    private val pendingVersion = MutableStateFlow(0)

    private fun emit() {
        val linkedIds = links.values.map { it.markerId }.toSet()
        unmatchedState.value = markers.values.filter { it.id !in linkedIds }
        pendingVersion.value++
    }

    override suspend fun insert(marker: SongMarkerEntity): Long {
        val id = nextMarkerId++
        markers[id] = marker.copy(id = id)
        emit()
        return id
    }

    override suspend fun getById(id: Long): SongMarkerEntity? = markers[id]

    override suspend fun getByFingerprint(fingerprint: String): List<SongMarkerEntity> =
        markers.values.filter { it.sourceFingerprint == fingerprint }

    override suspend fun update(
        id: Long,
        label: String,
        positionMs: Long,
        isEnabled: Boolean,
    ) {
        markers[id]?.let {
            markers[id] = it.copy(label = label, positionMs = positionMs, isEnabled = isEnabled)
        }
        emit()
    }

    override fun observeUnmatched(): Flow<List<SongMarkerEntity>> = unmatchedState

    override suspend fun insertLink(link: MarkerSongLinkEntity): Long {
        // Bildet den Unique-Index auf marker_id nach (ABORT bei Konflikt).
        check(links.values.none { it.markerId == link.markerId }) {
            "Link fuer Marker ${link.markerId} existiert bereits"
        }
        val id = nextLinkId++
        links[id] = link.copy(id = id)
        emit()
        return id
    }

    override suspend fun deleteLinkForMarker(markerId: Long) {
        links.values.filter { it.markerId == markerId }.forEach { links.remove(it.id) }
        emit()
    }

    override suspend fun getLinkForMarker(markerId: Long): MarkerSongLinkEntity? =
        links.values.firstOrNull { it.markerId == markerId }

    override suspend fun deleteMarker(markerId: Long) {
        // Bildet ON DELETE CASCADE nach: die Linkzeile faellt mit.
        markers.remove(markerId)
        links.values.filter { it.markerId == markerId }.forEach { links.remove(it.id) }
        emit()
    }

    override suspend fun getEnabledMarkersForSong(songId: Long): List<SongMarkerEntity> {
        val ids =
            links.values
                .filter { it.songId == songId }
                .map { it.markerId }
                .toSet()
        return markers.values.filter { it.id in ids && it.isEnabled }.sortedBy { it.positionMs }
    }

    override fun observePendingBySource(source: String): Flow<List<PendingMarkerRow>> =
        pendingVersion.map {
            markers.values
                .filter { it.source == source && !it.isEnabled }
                .mapNotNull { marker ->
                    links.values
                        .firstOrNull { it.markerId == marker.id }
                        ?.let { link -> PendingMarkerRow(marker, link.songId) }
                }.sortedWith(compareBy({ it.linkedSongId }, { it.marker.positionMs }))
        }

    override suspend fun deletePendingBySourceForSong(
        songId: Long,
        source: String,
    ) {
        val ids =
            links.values
                .filter { it.songId == songId }
                .map { it.markerId }
                .toSet()
        markers.values
            .filter { it.id in ids && it.source == source && !it.isEnabled }
            .forEach { marker ->
                markers.remove(marker.id)
                links.values.filter { it.markerId == marker.id }.forEach { links.remove(it.id) }
            }
        emit()
    }
}

class FakeMediaStoreGateway(
    var permissionGranted: Boolean = true,
    var generation: String = "v1:1",
    var audio: List<Song> = emptyList(),
) : MediaStoreGateway {
    var queryCount = 0
        private set

    override fun hasAudioPermission(): Boolean = permissionGranted

    override fun requiredPermission(): String = "android.permission.READ_MEDIA_AUDIO"

    override fun currentGeneration(): String = generation

    override fun queryAudio(): List<Song> {
        queryCount++
        return audio
    }
}

class FakeScanStateStore : ScanStateStore {
    var stored: String? = null

    override suspend fun lastGeneration(): String? = stored

    override suspend fun setLastGeneration(value: String) {
        stored = value
    }
}

class FakeTransactionRunner : TransactionRunner {
    override suspend fun <T> invoke(block: suspend () -> T): T = block()
}

class FakeCueTrackDao : CueTrackDao {
    val rows = mutableListOf<CueTrackEntity>()
    private var nextId = 1L
    private val state = MutableStateFlow<List<CueTrackEntity>>(emptyList())

    private fun emit() {
        state.value = rows.toList()
    }

    override suspend fun insertAll(tracks: List<CueTrackEntity>) {
        for (track in tracks) rows += track.copy(id = nextId++)
        emit()
    }

    override suspend fun deleteForSong(songId: Long) {
        rows.removeAll { it.songId == songId }
        emit()
    }

    override fun observeForSong(songId: Long): Flow<List<CueTrackEntity>> =
        state.map { list -> list.filter { it.songId == songId }.sortedBy { it.trackNumber } }

    override suspend fun getForSong(songId: Long): List<CueTrackEntity> =
        rows
            .filter {
                it.songId == songId
            }.sortedBy { it.trackNumber }
}

class FakeSafFileDao : SafFileDao {
    val rows = mutableListOf<SafFileEntity>()
    private var nextId = 1L
    private val state = MutableStateFlow<List<SafFileEntity>>(emptyList())

    private fun emit() {
        state.value = rows.toList()
    }

    override suspend fun insertAll(files: List<SafFileEntity>) {
        for (file in files) rows += file.copy(id = nextId++)
        emit()
    }

    override suspend fun deleteForTree(treeUri: String) {
        rows.removeAll { it.treeUri == treeUri }
        emit()
    }

    override fun observeAll(): Flow<List<SafFileEntity>> = state

    override suspend fun getForTree(treeUri: String): List<SafFileEntity> = rows.filter { it.treeUri == treeUri }
}

class FakeSafFolderGateway(
    var files: List<SafDocument> = emptyList(),
    var documents: Map<String, String> = emptyMap(),
) : SafFolderGateway {
    override fun listFiles(treeUri: String): List<SafDocument> = files

    override fun readDocument(documentUri: String): String? = documents[documentUri]
}

class FakeMusicFolderFilterRepository(
    excluded: Set<String> = emptySet(),
) : MusicFolderFilterRepository {
    private val state = MutableStateFlow(excluded)
    override val excludedFolders: Flow<Set<String>> = state

    override suspend fun setExcludedFolders(paths: Set<String>) {
        state.value = paths
    }
}

class FakeTrackAnalysisRepository : TrackAnalysisRepository {
    val requestedSongs = mutableListOf<Song>()

    override fun observeAnalysis(songId: Long): Flow<TrackAnalysis?> = flowOf(null)

    override suspend fun requestAnalysis(song: Song) {
        requestedSongs += song
    }

    override suspend fun requestAnalysisForNewSongs(songs: List<Song>) {
        batchRequestedSongs += songs
        requestedSongs += songs
    }

    val batchRequestedSongs = mutableListOf<List<Song>>()

    override suspend fun requestOnsetDetection(song: Song) {
        // Nicht in dieser Phase relevant.
    }
}
