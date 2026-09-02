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
     * Waveform und danach Metadaten als Kette — fuer den Import-Bulk, der
     * bewusst NICHT in-process laeuft (hunderte Titel wuerden dem
     * sichtbaren Titel die CPU nehmen).
     */
    fun scheduleWaveformThenMix(
        songId: Long,
        alsoNeedsMix: Boolean,
    )

    /**
     * Nur die Waveform, ohne Mix-Metadaten: Prewarming der naechsten
     * Queue-Titel (Umbauplan Phase 4). Getrennt von
     * [scheduleWaveformThenMix], weil Prewarming die Anzeige vorbereitet
     * und nicht die Bibliothek vervollstaendigt — ein zusaetzlicher
     * Metadatenlauf je vorbereitetem Titel waere Arbeit fuer Werte, die
     * noch niemand sehen will.
     */
    fun schedulePrewarmWaveform(songId: Long)

    /** Vom Nutzer angestossene Onset-Erkennung (Volldurchgang). */
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

    override fun scheduleWaveformThenMix(
        songId: Long,
        alsoNeedsMix: Boolean,
    ) {
        val continuation =
            WorkManager
                .getInstance(context)
                .beginUniqueWork(
                    "track_analysis_$songId",
                    ExistingWorkPolicy.KEEP,
                    request(songId, AnalysisProfile.WAVEFORM_ONLY),
                )
        if (alsoNeedsMix) {
            // Die Kette garantiert die Reihenfolge: Stufe 2 aktualisiert eine
            // Zeile, die Stufe 1 erst anlegen muss.
            continuation
                .then(request(songId, AnalysisProfile.MIX_METADATA))
                .enqueue()
        } else {
            continuation.enqueue()
        }
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
     * Derselbe Work-Name wie [scheduleWaveformThenMix] plus
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
