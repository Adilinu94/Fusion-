package com.dropsync.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.dropsync.core.database.entity.CueTrackEntity
import com.dropsync.core.database.entity.SafFileEntity
import kotlinx.coroutines.flow.Flow

/** Virtuelle CUE-Tracks je Song (Plan Phase 3). */
@Dao
interface CueTrackDao {
    @Insert
    suspend fun insertAll(tracks: List<CueTrackEntity>)

    /** Neuimport ersetzt alle Tracks des Songs (im Repository transaktional). */
    @Query("DELETE FROM cue_tracks WHERE song_id = :songId")
    suspend fun deleteForSong(songId: Long)

    @Query("SELECT * FROM cue_tracks WHERE song_id = :songId ORDER BY track_number")
    fun observeForSong(songId: Long): Flow<List<CueTrackEntity>>

    @Query("SELECT * FROM cue_tracks WHERE song_id = :songId ORDER BY track_number")
    suspend fun getForSong(songId: Long): List<CueTrackEntity>
}

/** Index des SAF-Ordnerscans (Plan Phase 3). */
@Dao
interface SafFileDao {
    @Insert
    suspend fun insertAll(files: List<SafFileEntity>)

    /** Rescan ersetzt den kompletten Baum (im Repository transaktional). */
    @Query("DELETE FROM saf_files WHERE tree_uri = :treeUri")
    suspend fun deleteForTree(treeUri: String)

    @Query("SELECT * FROM saf_files ORDER BY relative_path, display_name COLLATE NOCASE")
    fun observeAll(): Flow<List<SafFileEntity>>

    @Query("SELECT * FROM saf_files WHERE tree_uri = :treeUri")
    suspend fun getForTree(treeUri: String): List<SafFileEntity>
}
