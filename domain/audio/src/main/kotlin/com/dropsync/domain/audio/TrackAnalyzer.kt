package com.dropsync.domain.audio

import com.dropsync.core.common.AppResult
import com.dropsync.core.model.Song
import kotlinx.coroutines.flow.Flow

/**
 * Geteilte Analyse-Grundlage (Marker/Waveform-Plan Phase 2): ein einziger
 * Analysedurchgang liefert die Waveform-Peaks und die Onset-Kandidaten
 * fuer A2 — nicht zwei getrennte Decoder-Pfade.
 *
 * Implementierung in :data:audio (PCM-Beschaffung ueber den Decoder);
 * die reine Signalverarbeitung liegt hier im JVM-Modul und ist ohne
 * Android/Media3 testbar (Modulregel 3.2, ADR-0005).
 */
interface TrackAnalyzer {
    /**
     * Analysiert [song]. Ohne [detectOnsets] wird nur die Waveform
     * berechnet — der teurere Energie-/Onset-Pfad (A2) entfaellt dann,
     * was den haeufigen Nur-Waveform-Fall (Now-Playing) spuerbar
     * beschleunigt. Nur der explizite Nutzeranstoss setzt [detectOnsets].
     */
    suspend fun analyze(
        song: Song,
        detectOnsets: Boolean = false,
    ): AppResult<TrackAnalysis>
}

/**
 * Feature-Zugang zum Analyse-Cache (`track_analysis`). Die Analyse ist
 * aufschiebbar und laeuft als deduplizierter OneTimeWorkRequest
 * (`track_analysis_<songId>`); Features beobachten nur das Ergebnis.
 */
interface TrackAnalysisRepository {
    /** Gecachte Analyse des Songs; null bis zum ersten fertigen Durchgang. */
    fun observeAnalysis(songId: Long): Flow<TrackAnalysis?>

    /**
     * Stoesst die Analyse an, falls kein gueltiger Cache-Eintrag existiert
     * (Cache-Miss beim Oeffnen des Now-Playing-Screens oder expliziter
     * A2-Anstoss). Mehrfachaufrufe fuer denselben Song sind dedupliziert.
     */
    suspend fun requestAnalysis(song: Song)

    /**
     * Stoesst die Onset-Erkennung (A2) fuer genau diesen Song an, vom
     * Nutzer ausgeloest ("Drops automatisch erkennen"). Kandidaten landen
     * als SongMarker(source = AUTO_DETECTED, isEnabled = false) und
     * brauchen eine bestaetigende Aktion — nie Automatik. Dedupliziert
     * ueber den Work-Namen `onset_detection_<songId>`.
     */
    suspend fun requestOnsetDetection(song: Song)
}

/**
 * Ergebnis eines Analysedurchgangs. [waveformBuckets] sind Min/Max-Paare
 * ueber den ganzen Track (z. B. 500 Stueck); [onsetCandidatesMs] sind
 * Positionen steiler Energie-Anstiege (Phase 5), leer bis zur
 * Onset-Erkennung.
 */
data class TrackAnalysis(
    val waveformBuckets: List<WaveformBucket>,
    val onsetCandidatesMs: List<Long>,
)

/** Ein Waveform-Bucket: Mono-Min/Max, auf Int8 normalisiert. */
data class WaveformBucket(
    val min: Byte,
    val max: Byte,
)

/**
 * Packt Waveform-Buckets verlustfrei in einen BLOB fuer den
 * `track_analysis`-Cache (interleaved min,max — 2 Bytes je Bucket).
 */
object WaveformCodec {
    /** Version des Analyse-Algorithmus; invalidiert den Cache bei Aenderung. */
    const val ANALYZER_VERSION: Int = 2

    fun pack(buckets: List<WaveformBucket>): ByteArray {
        val bytes = ByteArray(buckets.size * 2)
        buckets.forEachIndexed { index, bucket ->
            bytes[index * 2] = bucket.min
            bytes[index * 2 + 1] = bucket.max
        }
        return bytes
    }

    /** Leert das Ergebnis bei ungerader Laenge (defekter BLOB) statt zu raten. */
    fun unpack(bytes: ByteArray): List<WaveformBucket> {
        if (bytes.size % 2 != 0) return emptyList()
        return List(bytes.size / 2) { index ->
            WaveformBucket(min = bytes[index * 2], max = bytes[index * 2 + 1])
        }
    }
}
