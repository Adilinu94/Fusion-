package com.dropsync.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Cache eines Track-Analysedurchgangs (Marker/Waveform-Plan Phase 2):
 * gepackte Min/Max-Waveform-Buckets je Song. `analyzer_version` erlaubt,
 * den Cache bei Algorithmus-Aenderungen zu invalidieren, ohne andere
 * Tabellen anzufassen. Song-Identitaet ist die MediaStore-ID (5.1);
 * bewusst ohne Fremdschluessel auf `songs`, damit ein Bibliotheks-Rescan
 * den Analyse-Cache nicht mitreisst.
 */
@Entity(tableName = "track_analysis")
data class TrackAnalysisEntity(
    @PrimaryKey
    @ColumnInfo(name = "song_id")
    val songId: Long,
    /** Gepackte Min/Max-Paare (2 Bytes je Bucket, WaveformCodec). */
    @ColumnInfo(name = "waveform_data")
    val waveformData: ByteArray,
    @ColumnInfo(name = "bucket_count")
    val bucketCount: Int,
    @ColumnInfo(name = "analyzer_version")
    val analyzerVersion: Int,
    @ColumnInfo(name = "analyzed_at_epoch_ms")
    val analyzedAtEpochMs: Long,
) {
    // ByteArray braucht inhaltsbasierte Gleichheit (data class vergleicht Referenzen).
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TrackAnalysisEntity) return false
        return songId == other.songId &&
            waveformData.contentEquals(other.waveformData) &&
            bucketCount == other.bucketCount &&
            analyzerVersion == other.analyzerVersion &&
            analyzedAtEpochMs == other.analyzedAtEpochMs
    }

    override fun hashCode(): Int {
        var result = songId.hashCode()
        result = 31 * result + waveformData.contentHashCode()
        result = 31 * result + bucketCount
        result = 31 * result + analyzerVersion
        result = 31 * result + analyzedAtEpochMs.hashCode()
        return result
    }
}
