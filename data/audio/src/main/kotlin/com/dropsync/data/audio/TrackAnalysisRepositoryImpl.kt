package com.dropsync.data.audio

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.dropsync.core.common.AppResult
import com.dropsync.core.common.Clock
import com.dropsync.core.database.dao.MarkerDao
import com.dropsync.core.database.dao.SongDao
import com.dropsync.core.database.dao.TrackAnalysisDao
import com.dropsync.core.database.entity.MarkerSongLinkEntity
import com.dropsync.core.database.entity.SongEntity
import com.dropsync.core.database.entity.SongMarkerEntity
import com.dropsync.core.model.LinkMethod
import com.dropsync.core.model.MarkerSource
import com.dropsync.core.model.Song
import com.dropsync.domain.audio.AnalysisProfile
import com.dropsync.domain.audio.MixConfidence
import com.dropsync.domain.audio.TrackAnalysis
import com.dropsync.domain.audio.TrackAnalysisRepository
import com.dropsync.domain.audio.TrackAnalyzer
import com.dropsync.domain.audio.WaveformCodec
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Cache-Zugang zur Track-Analyse.
 *
 * Zwei Lanes, bewusst getrennt (Umbauplan Phase 3):
 *
 * - **In-Process, priorisiert:** [requestAnalysis] — der Titel, den der
 *   Nutzer gerade sieht. Startet sofort im [scope], ohne
 *   WorkManager-Dispatch davor. Ein Wechsel auf einen anderen Titel bricht
 *   den ueberholten Lauf ab (Cancel-und-Ueberholen statt KEEP-Schlange).
 * - **Aufschiebbar ueber [scheduler]:** Mix-Metadaten,
 *   [requestAnalysisForNewSongs] (Import-Bulk) und [requestOnsetDetection].
 *   Dort ist Latenz gleichgueltig und Prozess-Ueberleben nuetzlich.
 *
 * Prozess-Tod im In-Process-Pfad ist unkritisch: das Ergebnis lebt
 * ausschliesslich im DB-Cache und der Lauf ist idempotent wiederholbar —
 * beim naechsten Oeffnen des Titels laeuft er erneut.
 */
class TrackAnalysisRepositoryImpl(
    private val trackAnalysisDao: TrackAnalysisDao,
    private val analyzer: TrackAnalyzer,
    private val persister: TrackAnalysisPersister,
    private val scheduler: DeferredAnalysisScheduler,
    private val scope: CoroutineScope,
) : TrackAnalysisRepository {
    /**
     * Laufende In-Process-Analysen je Song. Kein Mutex: der Aufraeumpfad
     * laeuft in [Job.invokeOnCompletion] und darf nicht suspendieren —
     * nach einem Abbruch wuerde jede Suspension sofort erneut abbrechen
     * und der Eintrag fuer immer stehen bleiben.
     */
    private val activeJobs = HashMap<Long, Job>()
    private val jobsLock = Any()

    /**
     * Begrenzt gleichzeitige Decoder-Laeufe. MediaCodec-Instanzen sind eine
     * knappe Geraeteressource, und mehr Parallelitaet als 2 macht den
     * aktuellen Titel langsamer, nicht schneller.
     */
    private val decodeSlots = Semaphore(permits = MAX_PARALLEL_DECODES)

    override fun observeAnalysis(songId: Long): Flow<TrackAnalysis?> =
        trackAnalysisDao.observeBySongId(songId).map { entity ->
            entity
                // Veraltete Analyse (analyzerVersion < CURRENT) bleibt als
                // Waveform-Fallback sichtbar, bis die Neuberechnung fertig
                // ist; nur Mix-Metadaten (BPM/Key) bleiben bis dahin null.
                ?.takeIf { it.analyzerVersion <= WaveformCodec.ANALYZER_VERSION }
                ?.let {
                    val mixCurrent = it.mixAnalyzerVersion == WaveformCodec.MIX_ANALYZER_VERSION
                    // bucket_count = 0 ist der persistierte Fehlerfall
                    // (Format ohne Plattformdecoder): leere Buckets melden,
                    // damit die UI auf die Zeitleiste zurueckfaellt.
                    //
                    // Konfidenz-Gate an der LESESEITE, nicht beim Schreiben:
                    // die Rohwerte bleiben in der DB, damit eine spaetere
                    // Kalibrierung der Schwellen (MixConfidence) ohne
                    // Neuanalyse der ganzen Bibliothek greift. Ein Bump von
                    // ANALYZER_VERSION waere sonst der einzige Weg.
                    TrackAnalysis(
                        waveformBuckets = WaveformCodec.unpack(it.waveformData),
                        onsetCandidatesMs = emptyList(),
                        peakLinear = it.peakLinear,
                        bpm =
                            MixConfidence
                                .acceptBpm(it.bpm, it.bpmConfidence)
                                ?.takeIf { mixCurrent },
                        camelotKey =
                            MixConfidence
                                .acceptKey(it.camelotKey, it.keyConfidence)
                                ?.takeIf { mixCurrent },
                        bpmConfidence = it.bpmConfidence?.takeIf { mixCurrent },
                        keyConfidence = it.keyConfidence?.takeIf { mixCurrent },
                        integratedLufs = it.integratedLufs?.takeIf { mixCurrent },
                        truePeakDb = it.truePeakDb?.takeIf { mixCurrent },
                    )
                }
        }

    override suspend fun requestAnalysis(song: Song) {
        val cached = trackAnalysisDao.getBySongId(song.mediaStoreId)
        val waveformCurrent = cached?.analyzerVersion == WaveformCodec.ANALYZER_VERSION
        val mixCurrent = cached?.mixAnalyzerVersion == WaveformCodec.MIX_ANALYZER_VERSION

        if (!waveformCurrent) {
            startPriorityWaveformRun(song, alsoNeedsMix = !mixCurrent)
            return
        }

        // Waveform ist da. Ein noch laufender Lauf zu einem ANDEREN Titel ist
        // damit ueberholt: der Nutzer sieht diesen hier.
        cancelOvertakenRuns(keepSongId = song.mediaStoreId)
        if (!mixCurrent) scheduler.scheduleMixMetadata(song.mediaStoreId)
    }

    /**
     * Startet Stufe 1 sofort im eigenen Scope. Laeufe zu anderen Titeln
     * werden abgebrochen: schnelles Durchwischen durch die Queue erzeugt
     * sonst eine Schlange konkurrierender Decoder, die dem sichtbaren
     * Titel die CPU nimmt.
     */
    private fun startPriorityWaveformRun(
        song: Song,
        alsoNeedsMix: Boolean,
    ) {
        val songId = song.mediaStoreId
        synchronized(jobsLock) {
            cancelOvertakenRunsLocked(keepSongId = songId)
            // Derselbe Song laeuft schon: nicht neu starten (idempotent).
            if (activeJobs[songId]?.isActive == true) return
            val job =
                scope.launch {
                    decodeSlots.withPermit {
                        runWaveformStage(song, alsoNeedsMix)
                    }
                }
            activeJobs[songId] = job
            // Nicht-suspendierender Aufraeumpfad, laeuft auch bei Abbruch.
            job.invokeOnCompletion {
                synchronized(jobsLock) {
                    if (activeJobs[songId] === job) activeJobs.remove(songId)
                }
            }
        }
    }

    private fun cancelOvertakenRuns(keepSongId: Long) {
        synchronized(jobsLock) { cancelOvertakenRunsLocked(keepSongId) }
    }

    private fun cancelOvertakenRunsLocked(keepSongId: Long) {
        // Ueber eine Kopie iterieren: cancel() kann invokeOnCompletion
        // synchron auf diesem Thread ausloesen, das die Map anfasst.
        activeJobs
            .filterKeys { it != keepSongId }
            .forEach { (id, job) ->
                job.cancel()
                activeJobs.remove(id)
            }
    }

    private suspend fun runWaveformStage(
        song: Song,
        alsoNeedsMix: Boolean,
    ) {
        when (val result = analyzer.analyze(song, AnalysisProfile.WAVEFORM_ONLY)) {
            is AppResult.Success -> {
                persister.persistSuccess(
                    songId = song.mediaStoreId,
                    profile = AnalysisProfile.WAVEFORM_ONLY,
                    analysis = result.value,
                )
                // Erst NACH dem Schreiben von Stufe 1 anstossen: der
                // Metadatenlauf braucht die Zeile fuer sein UPDATE.
                if (alsoNeedsMix) scheduler.scheduleMixMetadata(song.mediaStoreId)
            }

            is AppResult.Failure -> {
                if (result.error.isPermanentAnalysisFailure()) {
                    persister.persistPermanentFailure(
                        songId = song.mediaStoreId,
                        profile = AnalysisProfile.WAVEFORM_ONLY,
                    )
                } else {
                    // Voruebergehend: KEIN Cache-Eintrag. Anlass ist immer
                    // eine Nutzeraktion, der naechste Aufruf versucht es
                    // erneut - ein Backoff-Retry waere hier nur Ballast.
                    Log.i(LOG_TAG, "Analyse voruebergehend fehlgeschlagen: ${song.mediaStoreId}")
                }
            }
        }
    }

    override suspend fun requestAnalysisForNewSongs(songs: List<Song>) {
        if (songs.isEmpty()) return
        // Eine Abfrage fuer alle Songs statt N Einzel-Queries (Import-
        // Pfad, Poweramp-Scanner-Muster: Batches bei Tausenden Titeln).
        val cached =
            trackAnalysisDao
                .getBySongIds(songs.map { it.mediaStoreId })
                .associateBy { it.songId }
        songs.forEach { song ->
            val entry = cached[song.mediaStoreId]
            val waveformCurrent = entry?.analyzerVersion == WaveformCodec.ANALYZER_VERSION
            val mixCurrent = entry?.mixAnalyzerVersion == WaveformCodec.MIX_ANALYZER_VERSION
            // Import-Bulk bleibt vollstaendig aufschiebbar: hunderte
            // In-Process-Laeufe wuerden dem aktuellen Titel die CPU nehmen.
            if (!waveformCurrent) {
                scheduler.scheduleWaveformThenMix(song.mediaStoreId, alsoNeedsMix = !mixCurrent)
            } else if (!mixCurrent) {
                scheduler.scheduleMixMetadata(song.mediaStoreId)
            }
        }
    }

    override suspend fun requestOnsetDetection(song: Song) {
        scheduler.scheduleOnsetDetection(song.mediaStoreId)
    }

    override suspend fun requestAnalysisPrewarm(
        songs: List<Song>,
        limit: Int,
    ) {
        if (limit <= 0 || songs.isEmpty()) return
        val candidates = songs.take(limit)
        // Ein Batch-Query statt N Einzelabfragen; der Aufrufpfad ist der
        // Titelwechsel, dort zaehlt jede vermiedene DB-Runde.
        val cached =
            trackAnalysisDao
                .getBySongIds(candidates.map { it.mediaStoreId })
                .associateBy { it.songId }
        candidates.forEach { song ->
            val current = cached[song.mediaStoreId]?.analyzerVersion == WaveformCodec.ANALYZER_VERSION
            // Nur die Waveform: Prewarming bereitet die Anzeige vor, nicht
            // die Mix-Metadaten. Die folgen beim echten Titelwechsel.
            if (!current) scheduler.schedulePrewarmWaveform(song.mediaStoreId)
        }
    }

    private companion object {
        const val LOG_TAG = "TrackAnalysisRepo"

        /** MediaCodec-Instanzen sind knapp; mehr Parallelitaet bremst nur. */
        const val MAX_PARALLEL_DECODES = 2
    }
}

/**
 * Fuehrt einen aufschiebbaren Analysedurchgang aus (Mix-Metadaten,
 * Import-Bulk, Onset-Erkennung). Der UI-kritische Waveform-Lauf laeuft
 * NICHT hier, sondern in-process im Repository.
 *
 * Abhaengigkeiten kommen ueber einen Hilt-EntryPoint, damit kein
 * zusaetzliches hilt-work-Artefakt noetig ist.
 */
class TrackAnalysisWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Dependencies {
        fun trackAnalyzer(): TrackAnalyzer

        fun trackAnalysisPersister(): TrackAnalysisPersister

        fun songDao(): SongDao

        fun markerDao(): MarkerDao

        fun clock(): Clock
    }

    override suspend fun doWork(): Result {
        val songId = inputData.getLong(KEY_SONG_ID, -1L)
        if (songId <= 0L) return Result.failure()
        val profile =
            inputData
                .getString(KEY_PROFILE)
                ?.let { runCatching { AnalysisProfile.valueOf(it) }.getOrNull() }
                ?: AnalysisProfile.FULL
        val deps = EntryPointAccessors.fromApplication(applicationContext, Dependencies::class.java)
        val entity = deps.songDao().getById(songId) ?: return Result.failure()

        return when (val result = deps.trackAnalyzer().analyze(entity.toSong(), profile)) {
            is AppResult.Success -> {
                val persisted =
                    deps.trackAnalysisPersister().persistSuccess(
                        songId = songId,
                        profile = profile,
                        analysis = result.value,
                    )
                // Kein Treffer heisst: Stufe 1 fehlt noch. Erneut versuchen,
                // niemals eine Zeile ohne Waveform anlegen.
                if (!persisted) {
                    Result.retry()
                } else {
                    if (profile == AnalysisProfile.WAVEFORM_AND_ONSETS ||
                        profile == AnalysisProfile.FULL
                    ) {
                        writeOnsetCandidates(deps, entity, result.value.onsetCandidatesMs)
                    }
                    Result.success()
                }
            }

            is AppResult.Failure -> {
                // Umbauplan Phase 10.4: temporaere Fehler (z. B. Datei
                // gerade gesperrt, kurzzeitiger Decoder-Fehler) NICHT als
                // Cache-Eintrag speichern - WorkManager retried dann.
                if (!result.error.isPermanentAnalysisFailure()) {
                    Result.retry()
                } else {
                    deps.trackAnalysisPersister().persistPermanentFailure(songId, profile)
                    Result.failure()
                }
            }
        }
    }

    /**
     * Schreibt Onset-Kandidaten als SongMarker(source = AUTO_DETECTED,
     * isEnabled = false) + Link (Phase 5). Ein erneuter Lauf ersetzt die
     * alten, noch unbestaetigten Kandidaten desselben Songs; bestaetigte
     * Marker bleiben unberuehrt. Nie Automatik: aktiv wird ein Kandidat
     * erst durch die bestaetigende Aktion in der Review-Liste.
     */
    private suspend fun writeOnsetCandidates(
        deps: Dependencies,
        song: SongEntity,
        onsetCandidatesMs: List<Long>,
    ) {
        val markerDao = deps.markerDao()
        markerDao.deletePendingBySourceForSong(song.mediaStoreId, MarkerSource.AUTO_DETECTED.name)
        // Derselbe Fingerprint, den die Bibliothek fuer den Song fuehrt.
        val fingerprint =
            listOf(
                song.relativePath,
                song.displayName,
                song.sizeBytes.toString(),
                song.durationMs.toString(),
            ).joinToString(FINGERPRINT_SEPARATOR)
        onsetCandidatesMs.forEachIndexed { index, positionMs ->
            val markerId =
                markerDao.insert(
                    SongMarkerEntity(
                        sourceFingerprint = fingerprint,
                        label = "Drop ${index + 1}",
                        positionMs = positionMs,
                        source = MarkerSource.AUTO_DETECTED.name,
                        isEnabled = false,
                        createdAtEpochMs = deps.clock().epochMillis(),
                    ),
                )
            markerDao.insertLink(
                MarkerSongLinkEntity(
                    markerId = markerId,
                    songId = song.mediaStoreId,
                    linkMethod = LinkMethod.AUTO_DETECTED.name,
                    linkedAtEpochMs = deps.clock().epochMillis(),
                ),
            )
        }
    }

    companion object {
        const val KEY_SONG_ID = "song_id"
        const val KEY_PROFILE = "profile"

        /** Trennzeichen des Bibliotheks-Fingerprints (US, 0x1F). */
        private const val FINGERPRINT_SEPARATOR = "\u001F"
    }
}

private fun SongEntity.toSong(): Song =
    Song(
        mediaStoreId = mediaStoreId,
        contentUri = contentUri,
        displayName = displayName,
        relativePath = relativePath,
        durationMs = durationMs,
        sizeBytes = sizeBytes,
        dateModifiedSeconds = dateModifiedSeconds,
        title = title,
        artist = artist,
        album = album,
        genre = genre,
        isAvailable = isAvailable,
    )
