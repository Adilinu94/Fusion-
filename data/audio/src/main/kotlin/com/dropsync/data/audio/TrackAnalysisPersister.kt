package com.dropsync.data.audio

import com.dropsync.core.common.AppError
import com.dropsync.core.common.Clock
import com.dropsync.core.database.dao.TrackAnalysisDao
import com.dropsync.core.database.entity.TrackAnalysisEntity
import com.dropsync.domain.audio.AnalysisProfile
import com.dropsync.domain.audio.TrackAnalysis
import com.dropsync.domain.audio.WaveformCodec

/**
 * Schreibt Analyseergebnisse in den `track_analysis`-Cache.
 *
 * Geteilt zwischen dem In-Process-Prioritaetspfad (Stufe 1, Plan Phase 3)
 * und dem WorkManager-Lauf (Stufe 2, Onset-Erkennung, Import-Bulk). Beide
 * MUESSEN dieselben Versionsregeln anwenden — sonst entstehen Zeilen, die
 * `observeAnalysis` nie als aktuell akzeptiert, und die Analyse laeuft
 * endlos im Kreis.
 *
 * Bewusst `public`: Hilt provided die Instanz im SingletonComponent und der
 * Worker-EntryPoint gibt sie zurueck. Trotzdem ein Implementierungsdetail
 * von `:data:audio` — kein Feature-Modul darf sie benutzen, die Features
 * kennen nur `TrackAnalysisRepository`.
 */
class TrackAnalysisPersister(
    private val dao: TrackAnalysisDao,
    private val clock: Clock,
) {
    /**
     * Persistiert ein Ergebnis nach den Regeln von [profile].
     *
     * Rueckgabe `false` heisst: das Metadaten-UPDATE hat keine Zeile
     * getroffen, weil Stufe 1 noch fehlt. Der Aufrufer soll es spaeter
     * erneut versuchen — niemals eine Zeile ohne Waveform anlegen, das
     * waere ein sichtbarer Rueckschritt gegenueber "noch am Laden".
     */
    suspend fun persistSuccess(
        songId: Long,
        profile: AnalysisProfile,
        analysis: TrackAnalysis,
    ): Boolean {
        val now = clock.epochMillis()
        if (profile == AnalysisProfile.MIX_METADATA) {
            return dao.updateMixMetadata(
                songId = songId,
                bpm = analysis.bpm,
                bpmConfidence = analysis.bpmConfidence,
                camelotKey = analysis.camelotKey,
                keyConfidence = analysis.keyConfidence,
                integratedLufs = analysis.integratedLufs,
                truePeakDb = analysis.truePeakDb,
                downbeatOffsetMs = analysis.downbeatOffsetMs,
                downbeatConfidence = analysis.downbeatConfidence,
                mixAnalyzerVersion = WaveformCodec.MIX_ANALYZER_VERSION,
                analyzedAtEpochMs = now,
            ) > 0
        }

        // Stufe 1 (und FULL) schreiben die ganze Zeile. Vorhandene
        // Metadaten werden uebernommen, damit ein Waveform-Neulauf nach
        // ANALYZER_VERSION-Bump die noch gueltigen BPM/Key nicht wegwirft.
        val previous = dao.getBySongId(songId)
        val includesMix = profile == AnalysisProfile.FULL
        dao.upsert(
            TrackAnalysisEntity(
                songId = songId,
                waveformData = WaveformCodec.pack(analysis.waveformBuckets),
                bucketCount = analysis.waveformBuckets.size,
                analyzerVersion = WaveformCodec.ANALYZER_VERSION,
                mixAnalyzerVersion =
                    if (includesMix) {
                        WaveformCodec.MIX_ANALYZER_VERSION
                    } else {
                        previous?.mixAnalyzerVersion ?: 0
                    },
                analyzedAtEpochMs = now,
                peakLinear = analysis.peakLinear,
                bpm = if (includesMix) analysis.bpm else previous?.bpm,
                camelotKey = if (includesMix) analysis.camelotKey else previous?.camelotKey,
                bpmConfidence = if (includesMix) analysis.bpmConfidence else previous?.bpmConfidence,
                keyConfidence = if (includesMix) analysis.keyConfidence else previous?.keyConfidence,
                integratedLufs = if (includesMix) analysis.integratedLufs else previous?.integratedLufs,
                truePeakDb = if (includesMix) analysis.truePeakDb else previous?.truePeakDb,
                downbeatOffsetMs = if (includesMix) analysis.downbeatOffsetMs else previous?.downbeatOffsetMs,
                downbeatConfidence = if (includesMix) analysis.downbeatConfidence else previous?.downbeatConfidence,
            ),
        )
        return true
    }

    /**
     * Haelt einen dauerhaften Fehler fest, ohne mehr zu zerstoeren als
     * noetig: die Metadatenstufe markiert nur ihre eigenen Spalten als
     * erledigt-aber-leer, die Waveformstufe schreibt den leeren
     * Bucket-Eintrag und uebernimmt vorhandene Metadaten.
     */
    suspend fun persistPermanentFailure(
        songId: Long,
        profile: AnalysisProfile,
    ) {
        val now = clock.epochMillis()
        if (profile == AnalysisProfile.MIX_METADATA) {
            // Ein Fehler der Hintergrundstufe darf eine bereits sichtbare
            // Waveform niemals ueberschreiben. Aktuelle Version mit leeren
            // Werten beendet den Retry-Kreis fuer dauerhaft unlesbare Medien.
            dao.updateMixMetadata(
                songId = songId,
                bpm = null,
                bpmConfidence = null,
                camelotKey = null,
                keyConfidence = null,
                integratedLufs = null,
                truePeakDb = null,
                downbeatOffsetMs = null,
                downbeatConfidence = null,
                mixAnalyzerVersion = WaveformCodec.MIX_ANALYZER_VERSION,
                analyzedAtEpochMs = now,
            )
            return
        }

        val previous = dao.getBySongId(songId)
        dao.upsert(
            TrackAnalysisEntity(
                songId = songId,
                waveformData = ByteArray(0),
                bucketCount = 0,
                analyzerVersion = WaveformCodec.ANALYZER_VERSION,
                mixAnalyzerVersion = previous?.mixAnalyzerVersion ?: 0,
                analyzedAtEpochMs = now,
                bpm = previous?.bpm,
                bpmConfidence = previous?.bpmConfidence,
                camelotKey = previous?.camelotKey,
                keyConfidence = previous?.keyConfidence,
                integratedLufs = previous?.integratedLufs,
                truePeakDb = previous?.truePeakDb,
                downbeatOffsetMs = previous?.downbeatOffsetMs,
                downbeatConfidence = previous?.downbeatConfidence,
            ),
        )
    }
}

/**
 * Unterscheidet dauerhafte von voruebergehenden Analysefehlern
 * (Umbauplan Phase 10.4). Nur dauerhafte Fehler werden gecacht;
 * voruebergehende gehen an WorkManager mit Backoff zurueck.
 */
internal fun AppError.isPermanentAnalysisFailure(): Boolean =
    when (this) {
        // Medienquelle fehlt dauerhaft (geloescht, nicht lesbar):
        // der leere Cache verhindert endloses Nachladen.
        is AppError.MediaUnavailable -> true

        // Alles andere (Unknown, DatabaseFailure, ...) ist temporaer.
        else -> false
    }
