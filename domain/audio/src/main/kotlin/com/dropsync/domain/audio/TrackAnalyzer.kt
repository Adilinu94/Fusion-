package com.dropsync.domain.audio

import com.dropsync.core.common.AppResult
import com.dropsync.core.model.Song
import kotlinx.coroutines.flow.Flow

/**
 * Profilgesteuerte Analyse-Grundlage. Der UI-kritische Lauf dekodiert nur
 * Waveform + Peak; Mix-Metadaten folgen in einem unabhaengigen Lauf. Der
 * zweite Decode ist bewusst guenstiger als die bisherige Wartezeit auf
 * Chroma/Goertzel vor der ersten sichtbaren Waveform.
 *
 * Implementierung in :data:audio (PCM-Beschaffung ueber den Decoder);
 * die reine Signalverarbeitung liegt hier im JVM-Modul und ist ohne
 * Android/Media3 testbar (Modulregel 3.2, ADR-0005).
 */
interface TrackAnalyzer {
    /**
     * Analysiert [song] mit genau den Akkumulatoren aus [profile].
     * `WAVEFORM_ONLY` darf insbesondere keine Tempo-, Chroma- oder
     * Lautheitsarbeit ausfuehren.
     */
    suspend fun analyze(
        song: Song,
        profile: AnalysisProfile = AnalysisProfile.FULL,
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
     * Stoesst die Analyse fuer mehrere Songs in einem Rutsch an.
     * Implementierungen muessen den Cache-Miss fuer alle Songs in
     * EINER Abfrage bestimmen (Import-Pfad, Poweramp-Scanner-Muster:
     * Batches statt N Einzel-Queries bei Tausenden von Titeln).
     * Songs ohne Cache-Eintrag werden einzeln enqueued und sind
     * untereinander dedupliziert.
     */
    suspend fun requestAnalysisForNewSongs(songs: List<Song>)

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
    /**
     * Groesster Betrag des Mono-Downmix (0..1) ueber den ganzen Track.
     * Grundlage der visuellen Lautheits-Normalisierung (Phase 8):
     * leise gemasterte Tracks wuerden sonst als fast flache Linie
     * erscheinen; der Peak erlaubt einen ehrlichen Boden (leise
     * hochskalieren), ohne laute Tracks zu verzerren.
     */
    val peakLinear: Double = 0.0,
    /** Geschaetztes Tempo; null, solange die Erkennung nicht sicher ist. */
    val bpm: Float? = null,
    /** Camelot-Notation (z. B. "8A"); null, wenn nicht sicher bestimmbar. */
    val camelotKey: String? = null,
    /** Konfidenz der Tempo-Schaetzung 0..1; null wenn kein BPM. */
    val bpmConfidence: Float? = null,
    /** Konfidenz der Tonart-Schaetzung 0..1; null wenn kein Key. */
    val keyConfidence: Float? = null,
    /** Integrierte Lautheit (LUFS); null wenn nicht laut genug messbar. */
    val integratedLufs: Float? = null,
    /** True-Peak-Naeherung in dBFS; null wenn kein Peak vorhanden. */
    val truePeakDb: Float? = null,
)

/** Ein Waveform-Bucket: Mono-Min/Max, auf Int8 normalisiert. */
data class WaveformBucket(
    val min: Byte,
    val max: Byte,
)

/**
 * Analyseprofil (Offtrack Phase 8): nicht jeder Track muss alle
 * Akkumulatoren berechnen. Der Worker waehlt damit den Aufwand.
 */
enum class AnalysisProfile {
    WAVEFORM_ONLY,
    WAVEFORM_AND_ONSETS,
    MIX_METADATA,
    FULL,
}

/**
 * Packt Waveform-Buckets verlustfrei in einen BLOB fuer den
 * `track_analysis`-Cache (interleaved min,max — 2 Bytes je Bucket).
 */
object WaveformCodec {
    /** Version des Analyse-Algorithmus; invalidiert den Cache bei Aenderung. */
    const val ANALYZER_VERSION: Int = 4

    /** Version der unabhaengig gecachten BPM-/Key-/Lautheitsanalyse. */
    const val MIX_ANALYZER_VERSION: Int = 1

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
