package com.dropsync.data.library

import com.dropsync.core.database.dao.FavoriteDao
import com.dropsync.core.database.dao.FlatSetDao
import com.dropsync.core.database.dao.FlatSetSummaries
import com.dropsync.core.database.dao.PlayStatDao
import com.dropsync.core.database.dao.PlaylistDao
import com.dropsync.core.database.dao.PlaylistRow
import com.dropsync.core.database.dao.TrackAnalysisDao
import com.dropsync.core.database.entity.FavoriteEntity
import com.dropsync.core.database.entity.FlatSetEntity
import com.dropsync.core.database.entity.PlayStatEntity
import com.dropsync.core.database.entity.PlaylistEntity
import com.dropsync.core.database.entity.PlaylistItemEntity
import com.dropsync.core.database.entity.SongEntity
import com.dropsync.core.database.entity.TrackAnalysisEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf

/**
 * Test-Fakes fuer die Reconciliation (2026-09-27, Befund 6.7).
 *
 * **Warum sie hier und nicht in [Fakes.kt]:** sie gehoeren zu genau
 * einem Befund. `Fakes.kt` waechst sonst mit jedem neuen Test-Harness
 * weiter und verliert den Ueberblick darueber, welcher Fake zu welcher
 * Aenderung gehoert.
 *
 * **Was sie pruefen:** nicht die SQL-Semantik (das macht der
 * Migrationstest gegen eine echte Datenbank), sondern die Frage, ob das
 * Repository das Umhaengen **ueberhaupt ausloest** und mit den richtigen
 * Paaren. Die Heuristik prueft `SongReconcilerTest`.
 *
 * **Kein gemeinsamer Recorder:** die Klassen sind ohnehin zu gross fuer
 * eine gemeinsame Basis, und die Vererbung macht die `override`-Liste
 * unlesbar. Jeder Fake haelt seine eigene `reassignCalls`-Liste — die
 * drei Zeilen Wiederholung sind billiger als eine Basisklasse, die man
 * beim Lesen aufloesen muss.
 */
internal class FakeFavoriteDao : FavoriteDao {
    private val state = MutableStateFlow<List<FavoriteEntity>>(emptyList())

    /** Aufgerufene `(alt, neu)`-Paare; der Test prueft die Reihenfolge nicht, nur den Inhalt. */
    val reassignCalls: MutableList<Pair<Long, Long>> = mutableListOf()

    override suspend fun add(favorite: FavoriteEntity) {
        state.value = state.value + favorite
    }

    override suspend fun remove(songId: Long) {
        state.value = state.value.filterNot { it.songId == songId }
    }

    override fun observeIsFavorite(songId: Long): Flow<Boolean> = flowOf(state.value.any { it.songId == songId })

    override fun observeFavorites(): Flow<List<SongEntity>> = flowOf(emptyList())

    override suspend fun reassignSong(
        oldSongId: Long,
        newSongId: Long,
    ): Int {
        reassignCalls += oldSongId to newSongId
        val affected = state.value.filter { it.songId == oldSongId }
        // UPDATE OR REPLACE: eine vorhandene Zeile der Ziel-ID gewinnt.
        state.value =
            state.value.filterNot { it.songId == oldSongId || it.songId == newSongId } +
            affected.map { it.copy(songId = newSongId) }
        return affected.size
    }
}

internal class FakePlayStatDao : PlayStatDao {
    private val state = MutableStateFlow<List<PlayStatEntity>>(emptyList())

    val reassignCalls: MutableList<Pair<Long, Long>> = mutableListOf()

    /** Nur fuer Tests: Statistikzeilen direkt setzen. */
    fun seed(vararg stats: PlayStatEntity) {
        state.value = stats.toList()
    }

    override suspend fun incrementIfExists(
        songId: Long,
        atEpochMs: Long,
    ): Int {
        val current = state.value.find { it.songId == songId } ?: return 0
        state.value =
            state.value.map {
                if (it.songId == songId) {
                    it.copy(playCount = it.playCount + 1, lastPlayedAtEpochMs = atEpochMs)
                } else {
                    it
                }
            }
        return 1
    }

    override suspend fun insertIfMissing(stat: PlayStatEntity) {
        if (state.value.none { it.songId == stat.songId }) {
            state.value = state.value + stat
        }
    }

    override suspend fun getStat(songId: Long): PlayStatEntity? = state.value.find { it.songId == songId }

    override suspend fun getStats(songIds: List<Long>): List<PlayStatEntity> =
        state.value.filter { it.songId in songIds }

    override fun observeAll(): Flow<List<PlayStatEntity>> = flowOf(state.value)

    override suspend fun reassignSong(
        oldSongId: Long,
        newSongId: Long,
    ): Int {
        reassignCalls += oldSongId to newSongId
        val affected = state.value.filter { it.songId == oldSongId }
        state.value =
            state.value.filterNot { it.songId == oldSongId || it.songId == newSongId } +
            affected.map { it.copy(songId = newSongId) }
        return affected.size
    }
}

internal class FakePlaylistDao : PlaylistDao {
    private val items = MutableStateFlow<List<PlaylistItemEntity>>(emptyList())

    val reassignCalls: MutableList<Pair<Long, Long>> = mutableListOf()

    /** Nur fuer Tests: Eintraege direkt setzen. */
    fun seed(vararg entries: PlaylistItemEntity) {
        items.value = entries.toList()
    }

    /** Nur fuer Tests: aktueller Zustand. */
    fun currentItems(): List<PlaylistItemEntity> = items.value

    override suspend fun insertPlaylist(playlist: PlaylistEntity): Long = 1L

    override suspend fun renamePlaylist(
        id: Long,
        name: String,
    ) = Unit

    override suspend fun deletePlaylist(id: Long) = Unit

    override suspend fun setLabel(
        id: Long,
        label: String?,
    ) = Unit

    override suspend fun getPlaylistIdByName(name: String): Long? = null

    override fun observePlaylists(): Flow<List<PlaylistRow>> = flowOf(emptyList())

    override fun observePlaylistsByLabel(label: String): Flow<List<PlaylistRow>> = flowOf(emptyList())

    override suspend fun insertItem(item: PlaylistItemEntity): Long {
        items.value = items.value + item
        return item.id
    }

    override suspend fun insertItems(newItems: List<PlaylistItemEntity>) {
        items.value = items.value + newItems
    }

    override suspend fun deleteItem(itemId: Long) {
        items.value = items.value.filterNot { it.id == itemId }
    }

    override suspend fun updateItemPosition(
        itemId: Long,
        position: Int,
    ) {
        items.value = items.value.map { if (it.id == itemId) it.copy(position = position) else it }
    }

    override suspend fun deleteItemsOfPlaylist(playlistId: Long) {
        items.value = items.value.filterNot { it.playlistId == playlistId }
    }

    override suspend fun getItemsOnce(playlistId: Long): List<PlaylistItemEntity> =
        items.value.filter { it.playlistId == playlistId }.sortedBy { it.position }

    override suspend fun getSongIdsOnce(playlistId: Long): List<Long> =
        items.value.filter { it.playlistId == playlistId }.map { it.songId }

    override suspend fun maxPosition(playlistId: Long): Int =
        items.value.filter { it.playlistId == playlistId }.maxOfOrNull { it.position } ?: -1

    override suspend fun reassignSong(
        oldSongId: Long,
        newSongId: Long,
    ): Int {
        reassignCalls += oldSongId to newSongId
        val affected = items.value.filter { it.songId == oldSongId }
        // UPDATE OR REPLACE: bei Kollision gewinnt die bestehende Zeile.
        items.value =
            items.value.filterNot { it.songId == oldSongId || it.songId == newSongId } +
            affected.map { it.copy(songId = newSongId) }
        return affected.size
    }

    override fun observeSongsOfPlaylist(playlistId: Long): Flow<List<SongEntity>> = flowOf(emptyList())

    override suspend fun getSongsForLabelOnce(label: String): List<SongEntity> = emptyList()
}

/**
 * Analyse-Cache (2026-09-27, Befunde 6.7 und 6.18).
 *
 * Bildet **beide** Seiten nach: das Umhaengen bei einer Verschiebung und
 * das Aufraeumen verwaister Zeilen. Der Aufraeumer braucht den
 * Titelbestand, um "verwaist" zu erkennen — im echten Lauf liefert die
 * Abfrage ihn ueber ein Subselect, hier ueber [setKnownSongIds].
 */
internal class FakeTrackAnalysisDao : TrackAnalysisDao {
    private val state = MutableStateFlow<List<TrackAnalysisEntity>>(emptyList())
    private var knownSongIds: Set<Long> = emptySet()

    val reassignCalls: MutableList<Pair<Long, Long>> = mutableListOf()

    /** Wie oft [deleteOrphans] gelaufen ist (der Test zaehlt das). */
    var deleteOrphansCalls: Int = 0
        private set

    /** Nur fuer Tests: Titelbestand setzen, gegen den "verwaist" gemessen wird. */
    fun setKnownSongIds(ids: Set<Long>) {
        knownSongIds = ids
    }

    /** Nur fuer Tests: Analysezeilen setzen. */
    fun seed(vararg rows: TrackAnalysisEntity) {
        state.value = rows.toList()
    }

    /** Nur fuer Tests: verbleibende `song_id`s (fuer die Pruefung). */
    fun currentSongIds(): List<Long> = state.value.map { it.songId }

    override suspend fun upsert(entity: TrackAnalysisEntity) {
        state.value = state.value.filterNot { it.songId == entity.songId } + entity
    }

    override suspend fun updateMixMetadata(
        songId: Long,
        bpm: Float?,
        bpmConfidence: Float?,
        camelotKey: String?,
        keyConfidence: Float?,
        integratedLufs: Float?,
        truePeakDb: Float?,
        downbeatOffsetMs: Long?,
        downbeatConfidence: Float?,
        mixAnalyzerVersion: Int,
        analyzedAtEpochMs: Long,
    ): Int = 0

    override suspend fun getBySongId(songId: Long): TrackAnalysisEntity? = state.value.find { it.songId == songId }

    override suspend fun getBySongIdsChunk(songIds: List<Long>): List<TrackAnalysisEntity> =
        state.value.filter { it.songId in songIds }

    override fun observeBySongId(songId: Long): Flow<TrackAnalysisEntity?> =
        flowOf(state.value.find { it.songId == songId })

    override suspend fun deleteOlderThanVersion(minVersion: Int) {
        state.value = state.value.filter { it.analyzerVersion >= minVersion }
    }

    override suspend fun deleteOrphans(): Int {
        deleteOrphansCalls++
        val before = state.value.size
        state.value = state.value.filter { it.songId in knownSongIds }
        return before - state.value.size
    }

    override suspend fun reassignSong(
        oldSongId: Long,
        newSongId: Long,
    ): Int {
        reassignCalls += oldSongId to newSongId
        val affected = state.value.count { it.songId == oldSongId }
        state.value = state.value.map { if (it.songId == oldSongId) it.copy(songId = newSongId) else it }
        return affected
    }
}

internal class FakeFlatSetDao : FlatSetDao {
    private val state = MutableStateFlow<List<FlatSetEntity>>(emptyList())

    val reassignCalls: MutableList<Pair<Long, Long>> = mutableListOf()

    /** Nur fuer Tests: Saetze direkt setzen. */
    fun seed(vararg sets: FlatSetEntity) {
        state.value = sets.toList()
    }

    override suspend fun insert(set: FlatSetEntity): Long {
        val id = state.value.size + 1L
        state.value = state.value + set.copy(id = id)
        return id
    }

    override suspend fun delete(id: Long) {
        state.value = state.value.filterNot { it.id == id }
    }

    override suspend fun getById(id: Long): FlatSetEntity? = state.value.find { it.id == id }

    override fun observeForExercise(exerciseId: Long): Flow<List<FlatSetEntity>> =
        flowOf(state.value.filter { it.exerciseId == exerciseId })

    override suspend fun getForExercise(exerciseId: Long): List<FlatSetEntity> =
        state.value.filter { it.exerciseId == exerciseId }

    override fun observeAll(): Flow<List<FlatSetEntity>> = flowOf(state.value)

    override fun observeRecent(limit: Int): Flow<List<FlatSetEntity>> = flowOf(state.value.take(limit))

    override suspend fun getLastForExercise(exerciseId: Long): FlatSetEntity? =
        state.value.filter { it.exerciseId == exerciseId }.maxByOrNull { it.loggedAtEpochMs }

    override suspend fun getMaxVolumeForExercise(exerciseId: Long): Long? =
        state.value
            .filter { it.exerciseId == exerciseId }
            .maxOfOrNull { it.weightMilliKg * it.reps }

    override suspend fun getVolumeForDay(
        dayStart: Long,
        dayEnd: Long,
    ): Long? =
        state.value
            .filter { it.loggedAtEpochMs in dayStart until dayEnd }
            .sumOf { it.weightMilliKg * it.reps }
            .takeIf { it > 0 }

    override suspend fun getRecent(limit: Int): List<FlatSetEntity> =
        state.value.sortedByDescending { it.loggedAtEpochMs }.take(limit)

    override suspend fun reassignSong(
        oldSongId: Long,
        newSongId: Long,
    ): Int {
        reassignCalls += oldSongId to newSongId
        val affected = state.value.count { it.songId == oldSongId }
        state.value = state.value.map { if (it.songId == oldSongId) it.copy(songId = newSongId) else it }
        return affected
    }

    override suspend fun getSummaries(
        exerciseId: Long,
        recentLimit: Int,
    ): FlatSetSummaries =
        FlatSetSummaries(
            last = getLastForExercise(exerciseId),
            maxVolumeMilliKg = getMaxVolumeForExercise(exerciseId),
            recent = getRecent(recentLimit),
        )
}
