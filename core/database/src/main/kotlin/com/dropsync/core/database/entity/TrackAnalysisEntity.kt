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
    /** Version der Mix-Metadaten; 0 bedeutet noch nicht/alt analysiert. */
    @ColumnInfo(name = "mix_analyzer_version", defaultValue = "0")
    val mixAnalyzerVersion: Int = 0,
    @ColumnInfo(name = "analyzed_at_epoch_ms")
    val analyzedAtEpochMs: Long,
    /** Track-Peak (0..1) fuer die visuelle Lautheits-Normalisierung (Phase 8). */
    @ColumnInfo(name = "peak_linear", defaultValue = "0.0")
    val peakLinear: Double = 0.0,
    /** Geschaetztes Tempo (Mix-Phase 1); null bis zur sicheren Erkennung. */
    @ColumnInfo(name = "bpm")
    val bpm: Float? = null,
    /** Konfidenz der Tempo-Schaetzung 0..1 (Offtrack Phase 8). */
    @ColumnInfo(name = "bpm_confidence")
    val bpmConfidence: Float? = null,
    /** Camelot-Notation (Mix-Phase 1); null, wenn nicht sicher bestimmbar. */
    @ColumnInfo(name = "camelot_key")
    val camelotKey: String? = null,
    /** Konfidenz der Tonart-Schaetzung 0..1 (Offtrack Phase 8). */
    @ColumnInfo(name = "key_confidence")
    val keyConfidence: Float? = null,
    /** Integrierte Lautheit in LUFS (Offtrack Phase 8). */
    @ColumnInfo(name = "integrated_lufs")
    val integratedLufs: Float? = null,
    /** True-Peak-Naeherung in dBFS (Offtrack Phase 8). */
    @ColumnInfo(name = "true_peak_db")
    val truePeakDb: Float? = null,
    /**
     * Phase des Beat-Rasters in ms (B4/RC-22; kein Taktanfang, siehe
     * `DownbeatAccumulator`). null = unbekannt/Altbestand; das Snap
     * rastet dann nicht.
     */
    @ColumnInfo(name = "downbeat_offset_ms")
    val downbeatOffsetMs: Long? = null,
    /** Konfidenz der Raster-Schaetzung 0..1 (B4/RC-22). */
    @ColumnInfo(name = "downbeat_confidence")
    val downbeatConfidence: Float? = null,
) {
    // ByteArray braucht inhaltsbasierte Gleichheit (data class vergleicht Referenzen).
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TrackAnalysisEntity) return false
        return songId == other.songId &&
            waveformData.contentEquals(other.waveformData) &&
            bucketCount == other.bucketCount &&
            analyzerVersion == other.analyzerVersion &&
            mixAnalyzerVersion == other.mixAnalyzerVersion &&
            analyzedAtEpochMs == other.analyzedAtEpochMs &&
            peakLinear == other.peakLinear &&
            bpm == other.bpm &&
            bpmConfidence == other.bpmConfidence &&
            camelotKey == other.camelotKey &&
            keyConfidence == other.keyConfidence &&
            integratedLufs == other.integratedLufs &&
            truePeakDb == other.truePeakDb &&
            downbeatOffsetMs == other.downbeatOffsetMs &&
            downbeatConfidence == other.downbeatConfidence
    }

    override fun hashCode(): Int {
        var result = songId.hashCode()
        result = 31 * result + waveformData.contentHashCode()
        result = 31 * result + bucketCount
        result = 31 * result + analyzerVersion
        result = 31 * result + mixAnalyzerVersion
        result = 31 * result + analyzedAtEpochMs.hashCode()
        result = 31 * result + peakLinear.hashCode()
        result = 31 * result + (bpm?.hashCode() ?: 0)
        result = 31 * result + (bpmConfidence?.hashCode() ?: 0)
        result = 31 * result + (camelotKey?.hashCode() ?: 0)
        result = 31 * result + (keyConfidence?.hashCode() ?: 0)
        result = 31 * result + (integratedLufs?.hashCode() ?: 0)
        result = 31 * result + (truePeakDb?.hashCode() ?: 0)
        result = 31 * result + (downbeatOffsetMs?.hashCode() ?: 0)
        result = 31 * result + (downbeatConfidence?.hashCode() ?: 0)
        return result
    }
}
