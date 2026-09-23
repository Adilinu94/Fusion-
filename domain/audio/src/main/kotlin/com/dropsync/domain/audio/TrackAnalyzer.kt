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
 * Feature-Zugang zum Analyse-Cache (`track_analysis`).
 *
 * Zwei Lanes mit unterschiedlicher Dringlichkeit (Umbauplan Phase 3):
 * [requestAnalysis] betrifft den Titel, den der Nutzer gerade sieht, und
 * laeuft sofort; alles andere ist aufschiebbar. Features beobachten
 * ausschliesslich das Ergebnis ueber [observeAnalysis].
 */
interface TrackAnalysisRepository {
    /** Gecachte Analyse des Songs; null bis zum ersten fertigen Durchgang. */
    fun observeAnalysis(songId: Long): Flow<TrackAnalysis?>

    /**
     * Stoesst die Analyse fuer den GERADE SICHTBAREN Song an, falls kein
     * gueltiger Cache-Eintrag existiert. Die Waveform-Stufe laeuft sofort
     * (kein Scheduler-Vorlauf); die Mix-Metadaten folgen aufschiebbar.
     *
     * Mehrfachaufrufe fuer denselben Song sind dedupliziert; ein Aufruf
     * mit einem ANDEREN Song bricht den vorigen Lauf ab — beim schnellen
     * Durchwischen gewinnt immer der zuletzt geoeffnete Titel.
     */
    suspend fun requestAnalysis(song: Song)

    /**
     * Stoesst die Analyse fuer mehrere Songs in einem Rutsch an.
     * Implementierungen muessen den Cache-Miss fuer alle Songs in
     * EINER Abfrage bestimmen (Import-Pfad, Poweramp-Scanner-Muster:
     * Batches statt N Einzel-Queries bei Tausenden von Titeln).
     * Vollstaendig aufschiebbar: hunderte Titel duerfen dem laufenden
     * Song nie die CPU nehmen.
     *
     * Neue Titel bekommen dabei ohne Nutzeraktion auch Drop-Vorschlaege
     * (A10): die Kandidaten landen als unbestaetigte
     * `AUTO_DETECTED`-Marker in der Review-Liste — sichtbar, aber nie
     * automatisch aktiv.
     */
    suspend fun requestAnalysisForNewSongs(songs: List<Song>)

    /**
     * Bereitet die Waveform der naechsten [limit] Queue-Titel vor, damit
     * sie beim Titelwechsel schon im Cache liegt (Umbauplan Phase 4).
     *
     * Bewusst NUR die Waveform: Prewarming soll die Anzeige vorbereiten,
     * nicht die Mix-Metadaten. Die folgen beim echten Titelwechsel ueber
     * [requestAnalysis].
     *
     * Aufrufer muessen warten, bis die Waveform des LAUFENDEN Titels
     * fertig ist. Frueher angestossen, konkurrieren die Prewarm-Decodes
     * mit dem einzigen Lauf, auf den der Nutzer tatsaechlich wartet.
     */
    suspend fun requestAnalysisPrewarm(
        songs: List<Song>,
        limit: Int = DEFAULT_PREWARM_LIMIT,
    )

    /**
     * Stoesst die Onset-Erkennung (A2) fuer genau diesen Song an, vom
     * Nutzer ausgeloest ("Drops automatisch erkennen"). Kandidaten landen
     * als SongMarker(source = AUTO_DETECTED, isEnabled = false) und
     * brauchen eine bestaetigende Aktion — nie Automatik.
     */
    suspend fun requestOnsetDetection(song: Song)

    companion object {
        /**
         * Wie viele Queue-Titel vorbereitet werden. Zwei reichen: bei
         * sequenzieller Wiedergabe ist der naechste Titel immer dabei, und
         * jeder weitere kostet einen vollen Decode fuer einen Titel, den
         * der Nutzer moeglicherweise nie erreicht.
         */
        const val DEFAULT_PREWARM_LIMIT: Int = 2
    }
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
    /**
     * Phase des Beat-Rasters in ms (B4/RC-22): Position des Kick/Bass
     * innerhalb eines Beat-Intervalls, aus der Low-Band-Energie
     * geschaetzt ([DownbeatAccumulator]). **Kein musikalischer
     * Taktanfang** (Umbauplan Phase 3.2). null = unbekannt; dann rastet
     * das Marker-Snap nicht (statt auf ein geratenes Raster).
     */
    val downbeatOffsetMs: Long? = null,
    /** Konfidenz der Raster-Schaetzung 0..1; null wenn kein Offset. */
    val downbeatConfidence: Float? = null,
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

    /**
     * Version der unabhaengig gecachten BPM-/Key-/Lautheitsanalyse.
     *
     * 2: Raster-Offset (B4) ergaenzt — Mix-Metadaten alter Laeufe werden
     *    neu berechnet, die Waveform bleibt gueltig (ADR-0015).
     */
    const val MIX_ANALYZER_VERSION: Int = 2

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
