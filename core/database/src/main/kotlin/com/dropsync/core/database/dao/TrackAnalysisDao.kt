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

    /** Reichert eine vorhandene Waveform-Zeile um unabhaengig versionierte Mix-Metadaten an. */
    @Query(
        "UPDATE track_analysis SET bpm = :bpm, bpm_confidence = :bpmConfidence, " +
            "camelot_key = :camelotKey, key_confidence = :keyConfidence, " +
            "integrated_lufs = :integratedLufs, true_peak_db = :truePeakDb, " +
            "downbeat_offset_ms = :downbeatOffsetMs, downbeat_confidence = :downbeatConfidence, " +
            "mix_analyzer_version = :mixAnalyzerVersion, analyzed_at_epoch_ms = :analyzedAtEpochMs " +
            "WHERE song_id = :songId",
    )
    suspend fun updateMixMetadata(
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
    ): Int

    @Query("SELECT * FROM track_analysis WHERE song_id = :songId")
    suspend fun getBySongId(songId: Long): TrackAnalysisEntity?

    /**
     * Alle Cache-Eintraege einer Song-Liste in einer Abfrage; Ergebnis
     * enthaelt nur Songs MIT Eintrag (fehlende = Cache-Miss). Basis fuer
     * den Batch-Anstoss beim Import statt N Einzel-Queries.
     *
     * `IN (:songIds)` bindet einen Parameter je ID und scheitert damit
     * oberhalb des SQLite-Variablenlimits. Statt das Limit zu erzwingen,
     * zerlegt ein [DefaultImpls]-Baustein die Liste: das Verhalten bleibt
     * fuer jeden Aufrufer identisch, unabhaengig von der Bibliotheksgroesse.
     */
    @Query("SELECT * FROM track_analysis WHERE song_id IN (:songIds)")
    suspend fun getBySongIdsChunk(songIds: List<Long>): List<TrackAnalysisEntity>

    /**
     * Erweiterte Variante: zerlegt [songIds] in Bloecke von
     * [BIND_CHUNK_SIZE] und vereinigt die Ergebnisse.
     *
     * 2026-09-27 (Befund 17.4): `getBySongIds` wurde aus
     * `requestAnalysisForNewSongs` mit **allen** neu gescannten Songs
     * aufgerufen. Bei 10.000 Titeln sind das 10.000 Bindings in einer
     * Abfrage — auf aelteren SQLite-Builds ein harter Fehler, der den
     * gesamten Import abbrach.
     */
    suspend fun getBySongIds(songIds: List<Long>): List<TrackAnalysisEntity> {
        if (songIds.isEmpty()) return emptyList()
        return songIds
            .chunked(BIND_CHUNK_SIZE)
            .flatMap { getBySongIdsChunk(it) }
    }

    /** Beobachtet den Cache-Eintrag eines Songs (null bis zur ersten Analyse). */
    @Query("SELECT * FROM track_analysis WHERE song_id = :songId")
    fun observeBySongId(songId: Long): Flow<TrackAnalysisEntity?>

    /** Loescht veraltete Eintraege nach einer Algorithmus-Aenderung. */
    @Query("DELETE FROM track_analysis WHERE analyzer_version < :minVersion")
    suspend fun deleteOlderThanVersion(minVersion: Int)

    /**
     * Loescht Analysezeilen zu Titeln, die es **nicht mehr gibt**
     * (2026-09-27, Befund 6.18 / B-DB-2).
     *
     * **Warum ueberhaupt:** `track_analysis` hat bewusst **keinen**
     * Fremdschluessel auf `songs` — der Kommentar begruendet das
     * korrekt, damit ein Rescan den Cache nicht mitreisst. Die Kehrseite
     * war, dass geloeschte Titel ihre Waveform **fuer immer** behielten.
     * Bei 10.000 analysierten Titeln sind das ~6 MB, ueber Jahre mit
     * wechselnden Bibliotheken ungebundenes Wachstum.
     *
     * **Gegen `songs`, nicht gegen `is_available`:** das ist der
     * entscheidende Unterschied. Ein Titel, der nur *temporaer* nicht
     * verfuegbar ist (SD-Karte herausgezogen, Ordner abgewaehlt), ist
     * weiterhin in `songs` und behelt seine Analyse. Nur wer wirklich
     * aus der Bibliothek verschwunden ist, verliert die Waveform.
     *
     * **Warum ein Subselect statt einer ID-Liste:** dieselbe
     * Parametergrenze wie bei `markMissingAsUnavailable` (Befund 4.2) —
     * eine Liste wuerde bei 5.000 Titeln scheitern. Das Subselect hat
     * keinen Parameter.
     */
    @Query("DELETE FROM track_analysis WHERE song_id NOT IN (SELECT media_store_id FROM songs)")
    suspend fun deleteOrphans(): Int

    /**
     * Haengt den Analyse-Cache auf eine neue MediaStore-ID um
     * (2026-09-27, Befund 6.7).
     *
     * **Warum das noetig ist:** bei einer Verschiebung waere die
     * Waveform sonst verloren und der vierminuetige Titel muesste erneut
     * analysiert werden — fuer den Nutzer eine Verzoegerung von rund
     * 1,5 s ohne sichtbaren Grund, jeden Mal, wenn er einen Ordner
     * umsortiert.
     *
     * **Reihenfolge:** muss vor [deleteOrphans] laufen, sonst loescht der
     * Aufraeumer die gerade umgehaengte Zeile wieder.
     */
    @Query("UPDATE track_analysis SET song_id = :newSongId WHERE song_id = :oldSongId")
    suspend fun reassignSong(
        oldSongId: Long,
        newSongId: Long,
    ): Int

    companion object {
        /**
         * Blockgroesse fuer [getBySongIds]. 500 liegt unter dem Limit des
         * aeltesten unterstuetzten SQLite (999, API 26/27) und traegt
         * zugleich den Reservbedarf fuer Subqueries.
         */
        const val BIND_CHUNK_SIZE: Int = 500
    }
}
