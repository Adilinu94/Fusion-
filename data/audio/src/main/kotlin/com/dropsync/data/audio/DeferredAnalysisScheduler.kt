package com.dropsync.data.audio

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.dropsync.domain.audio.AnalysisProfile

/**
 * Die aufschiebbare Lane der Track-Analyse.
 *
 * Eigene Schnittstelle, damit der In-Process-Prioritaetspfad (Plan Phase 3)
 * ohne initialisierten WorkManager testbar bleibt. Ohne diesen Schnitt
 * scheitert jeder Test des Prioritaetspfads an
 * `WorkManager.getInstance()` — und die Alternative (WorkManager in jedem
 * Test hochziehen) wuerde Latenz und Nebenlaeufigkeit ins Testbild holen,
 * die fuer die geprueften Regeln irrelevant sind.
 */
interface DeferredAnalysisScheduler {
    /**
     * Stufe 2 (BPM/Key/LUFS) fuer einen Song, dessen Waveform bereits im
     * Cache liegt. Dedupliziert.
     */
    fun scheduleMixMetadata(songId: Long)

    /**
     * Volldurchgang fuer den Import-Bulk (A10): Waveform, Mix-Metadaten
     * und Onset-Kandidaten in EINEM Decode. Ersetzt die fruehere Kette
     * `WAVEFORM_ONLY -> MIX_METADATA`, die pro importiertem Titel zwei
     * volle Decodes kostete. Der Import-Bulk laeuft bewusst NICHT
     * in-process (hunderte Titel wuerden dem sichtbaren Titel die CPU
     * nehmen) und braucht die Zwei-Stufen-Latenz der Prioritaets-Lane
     * nicht.
     */
    fun scheduleFullAnalysis(songId: Long)

    /**
     * Nur die Waveform, ohne Mix-Metadaten: Prewarming der naechsten
     * Queue-Titel (Umbauplan Phase 4). Getrennt von
     * [scheduleFullAnalysis], weil Prewarming die Anzeige vorbereitet
     * und nicht die Bibliothek vervollstaendigt — ein zusaetzlicher
     * Metadatenlauf je vorbereitetem Titel waere Arbeit fuer Werte, die
     * noch niemand sehen will.
     */
    fun schedulePrewarmWaveform(songId: Long)

    /**
     * Vom Nutzer angestossene Onset-Erkennung (Volldurchgang). Importierte
     * Titel bekommen Kandidaten automatisch ueber [scheduleFullAnalysis];
     * dieser Aufruf bleibt fuer den manuellen Weg ("Drops automatisch
     * erkennen") und fuer Wiederholungen, wenn die Kandidaten schon
     * weggeraeumt wurden.
     */
    fun scheduleOnsetDetection(songId: Long)
}

/** WorkManager-Umsetzung der aufschiebbaren Lane. */
class WorkManagerAnalysisScheduler(
    private val context: Context,
) : DeferredAnalysisScheduler {
    override fun scheduleMixMetadata(songId: Long) {
        // Eigener Work-Name ist zwingend: unter `track_analysis_<id>` haette
        // ExistingWorkPolicy.KEEP den Metadatenlauf verworfen, solange dort
        // noch ein Eintrag existiert.
        WorkManager
            .getInstance(context)
            .enqueueUniqueWork(
                "mix_analysis_$songId",
                ExistingWorkPolicy.KEEP,
                request(songId, AnalysisProfile.MIX_METADATA),
            )
    }

    override fun scheduleFullAnalysis(songId: Long) {
        WorkManager
            .getInstance(context)
            .enqueueUniqueWork(
                "track_analysis_$songId",
                ExistingWorkPolicy.KEEP,
                request(songId, AnalysisProfile.FULL),
            )
    }

    override fun scheduleOnsetDetection(songId: Long) {
        WorkManager
            .getInstance(context)
            .enqueueUniqueWork(
                "onset_detection_$songId",
                ExistingWorkPolicy.KEEP,
                request(songId, AnalysisProfile.FULL),
            )
    }

    /**
     * Derselbe Work-Name wie [scheduleFullAnalysis] plus
     * [ExistingWorkPolicy.KEEP]: laeuft fuer den Titel schon eine Analyse,
     * wird der Prewarm verworfen statt zu duplizieren. Genau das ist hier
     * richtig — ein Prewarm hat nie Vorrang.
     */
    override fun schedulePrewarmWaveform(songId: Long) {
        WorkManager
            .getInstance(context)
            .enqueueUniqueWork(
                "track_analysis_$songId",
                ExistingWorkPolicy.KEEP,
                request(songId, AnalysisProfile.WAVEFORM_ONLY),
            )
    }

    private fun request(
        songId: Long,
        profile: AnalysisProfile,
    ) = OneTimeWorkRequestBuilder<TrackAnalysisWorker>()
        .setInputData(
            workDataOf(
                TrackAnalysisWorker.KEY_SONG_ID to songId,
                TrackAnalysisWorker.KEY_PROFILE to profile.name,
            ),
        ).build()
}
