package com.dropsync.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.dropsync.core.database.entity.TrackAnalysisEntity
import kotlinx.coroutines.flow.Flow

/** Zugriff auf den Track-Analyse-Cache (Marker/Waveform-Plan Phase 2). */
@Dao
interface TrackAnalysisDao {
    /** Ersetzt eine bestehende Analyse desselben Songs (neuer Durchgang gewinnt). */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: TrackAnalysisEntity)

    @Query("SELECT * FROM track_analysis WHERE song_id = :songId")
    suspend fun getBySongId(songId: Long): TrackAnalysisEntity?

    /**
     * Alle Cache-Eintraege einer Song-Liste in einer Abfrage; Ergebnis
     * enthaelt nur Songs MIT Eintrag (fehlende = Cache-Miss). Basis fuer
     * den Batch-Anstoss beim Import statt N Einzel-Queries.
     */
    @Query("SELECT * FROM track_analysis WHERE song_id IN (:songIds)")
    suspend fun getBySongIds(songIds: List<Long>): List<TrackAnalysisEntity>

    /** Beobachtet den Cache-Eintrag eines Songs (null bis zur ersten Analyse). */
    @Query("SELECT * FROM track_analysis WHERE song_id = :songId")
    fun observeBySongId(songId: Long): Flow<TrackAnalysisEntity?>

    /** Loescht veraltete Eintraege nach einer Algorithmus-Aenderung. */
    @Query("DELETE FROM track_analysis WHERE analyzer_version < :minVersion")
    suspend fun deleteOlderThanVersion(minVersion: Int)
}
