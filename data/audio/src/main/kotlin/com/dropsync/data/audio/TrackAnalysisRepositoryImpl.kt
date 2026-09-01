package com.dropsync.data.audio

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.dropsync.core.common.AppResult
import com.dropsync.core.common.Clock
import com.dropsync.core.database.dao.MarkerDao
import com.dropsync.core.database.dao.SongDao
import com.dropsync.core.database.dao.TrackAnalysisDao
import com.dropsync.core.database.entity.MarkerSongLinkEntity
import com.dropsync.core.database.entity.SongEntity
import com.dropsync.core.database.entity.SongMarkerEntity
import com.dropsync.core.database.entity.TrackAnalysisEntity
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Cache-Zugang zur Track-Analyse (Marker/Waveform-Plan Phase 2): liest
 * `track_analysis` und stoesst bei Cache-Miss einen aufschiebbaren,
 * ueber den Work-Namen `track_analysis_<songId>` deduplizierten
 * OneTimeWorkRequest an (WorkManager nur fuer aufschiebbare Aufgaben,
 * nie Timer).
 */
class TrackAnalysisRepositoryImpl(
    private val context: Context,
    private val trackAnalysisDao: TrackAnalysisDao,
) : TrackAnalysisRepository {
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
        if (waveformCurrent && mixCurrent) return
        enqueueAnalysis(song.mediaStoreId, waveformCurrent, mixCurrent)
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
            if (!waveformCurrent || !mixCurrent) {
                enqueueAnalysis(song.mediaStoreId, waveformCurrent, mixCurrent)
            }
        }
    }

    private fun analysisRequest(
        mediaStoreId: Long,
        profile: AnalysisProfile,
        expedited: Boolean,
    ) = OneTimeWorkRequestBuilder<TrackAnalysisWorker>()
        .setInputData(
            workDataOf(
                TrackAnalysisWorker.KEY_SONG_ID to mediaStoreId,
                TrackAnalysisWorker.KEY_PROFILE to profile.name,
            ),
        ).apply {
            if (expedited) {
                setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            }
        }.build()

    private fun enqueueAnalysis(
        mediaStoreId: Long,
        waveformCurrent: Boolean,
        mixCurrent: Boolean,
    ) {
        val workManager = WorkManager.getInstance(context)
        if (!waveformCurrent) {
            val waveform = analysisRequest(mediaStoreId, AnalysisProfile.WAVEFORM_ONLY, expedited = true)
            val continuation =
                workManager.beginUniqueWork(
                    "track_analysis_$mediaStoreId",
                    ExistingWorkPolicy.KEEP,
                    waveform,
                )
            if (!mixCurrent) {
                continuation
                    .then(analysisRequest(mediaStoreId, AnalysisProfile.MIX_METADATA, expedited = false))
                    .enqueue()
            } else {
                continuation.enqueue()
            }
            return
        }

        if (!mixCurrent) {
            workManager.enqueueUniqueWork(
                "mix_analysis_$mediaStoreId",
                ExistingWorkPolicy.KEEP,
                analysisRequest(mediaStoreId, AnalysisProfile.MIX_METADATA, expedited = false),
            )
        }
    }

    override suspend fun requestOnsetDetection(song: Song) {
        // Immer ein frischer Lauf (der Nutzer stoesst A2 bewusst an),
        // aber dedupliziert, solange bereits einer laeuft.
        val request =
            OneTimeWorkRequestBuilder<TrackAnalysisWorker>()
                .setInputData(
                    workDataOf(
                        TrackAnalysisWorker.KEY_SONG_ID to song.mediaStoreId,
                        TrackAnalysisWorker.KEY_PROFILE to AnalysisProfile.FULL.name,
                    ),
                ).build()
        WorkManager
            .getInstance(context)
            .enqueueUniqueWork(
                "onset_detection_${song.mediaStoreId}",
                ExistingWorkPolicy.KEEP,
                request,
            )
    }
}

/**
 * Fuehrt einen Analysedurchgang fuer genau einen Song aus und schreibt
 * das Ergebnis in den Cache. Abhaengigkeiten kommen ueber einen
 * Hilt-EntryPoint, damit kein zusaetzliches hilt-work-Artefakt noetig ist.
 */
class TrackAnalysisWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Dependencies {
        fun trackAnalyzer(): TrackAnalyzer

        fun trackAnalysisDao(): TrackAnalysisDao

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
            is AppResult.Success -> handleSuccess(deps, entity, profile, result.value)
            is AppResult.Failure -> handleFailure(deps, songId, profile, result.error)
        }
    }

    private suspend fun handleSuccess(
        deps: Dependencies,
        song: SongEntity,
        profile: AnalysisProfile,
        analysis: TrackAnalysis,
    ): Result {
        val now = deps.clock().epochMillis()
        if (profile == AnalysisProfile.MIX_METADATA) {
            val updated =
                deps.trackAnalysisDao().updateMixMetadata(
                    songId = song.mediaStoreId,
                    bpm = analysis.bpm,
                    bpmConfidence = analysis.bpmConfidence,
                    camelotKey = analysis.camelotKey,
                    keyConfidence = analysis.keyConfidence,
                    integratedLufs = analysis.integratedLufs,
                    truePeakDb = analysis.truePeakDb,
                    mixAnalyzerVersion = WaveformCodec.MIX_ANALYZER_VERSION,
                    analyzedAtEpochMs = now,
                )
            return if (updated == 0) Result.retry() else Result.success()
        }

        val previous = deps.trackAnalysisDao().getBySongId(song.mediaStoreId)
        val includesMix = profile == AnalysisProfile.FULL
        deps.trackAnalysisDao().upsert(
            TrackAnalysisEntity(
                songId = song.mediaStoreId,
                waveformData = WaveformCodec.pack(analysis.waveformBuckets),
                bucketCount = analysis.waveformBuckets.size,
                analyzerVersion = WaveformCodec.ANALYZER_VERSION,
                mixAnalyzerVersion =
                    if (includesMix) WaveformCodec.MIX_ANALYZER_VERSION else previous?.mixAnalyzerVersion ?: 0,
                analyzedAtEpochMs = now,
                peakLinear = analysis.peakLinear,
                bpm = if (includesMix) analysis.bpm else previous?.bpm,
                camelotKey = if (includesMix) analysis.camelotKey else previous?.camelotKey,
                bpmConfidence = if (includesMix) analysis.bpmConfidence else previous?.bpmConfidence,
                keyConfidence = if (includesMix) analysis.keyConfidence else previous?.keyConfidence,
                integratedLufs = if (includesMix) analysis.integratedLufs else previous?.integratedLufs,
                truePeakDb = if (includesMix) analysis.truePeakDb else previous?.truePeakDb,
            ),
        )
        if (profile == AnalysisProfile.WAVEFORM_AND_ONSETS || profile == AnalysisProfile.FULL) {
            writeOnsetCandidates(deps, song, analysis.onsetCandidatesMs)
        }
        return Result.success()
    }

    private suspend fun handleFailure(
        deps: Dependencies,
        songId: Long,
        profile: AnalysisProfile,
        error: com.dropsync.core.common.AppError,
    ): Result {
        // Temporaere Fehler werden nicht gecacht; WorkManager versucht erneut.
        if (!error.isPermanentAnalysisFailure()) return Result.retry()

        if (profile == AnalysisProfile.MIX_METADATA) {
            // Die Hintergrundstufe darf eine sichtbare Waveform nie loeschen.
            deps.trackAnalysisDao().updateMixMetadata(
                songId = songId,
                bpm = null,
                bpmConfidence = null,
                camelotKey = null,
                keyConfidence = null,
                integratedLufs = null,
                truePeakDb = null,
                mixAnalyzerVersion = WaveformCodec.MIX_ANALYZER_VERSION,
                analyzedAtEpochMs = deps.clock().epochMillis(),
            )
            return Result.failure()
        }

        val previous = deps.trackAnalysisDao().getBySongId(songId)
        deps.trackAnalysisDao().upsert(
            TrackAnalysisEntity(
                songId = songId,
                waveformData = ByteArray(0),
                bucketCount = 0,
                analyzerVersion = WaveformCodec.ANALYZER_VERSION,
                mixAnalyzerVersion = previous?.mixAnalyzerVersion ?: 0,
                analyzedAtEpochMs = deps.clock().epochMillis(),
                bpm = previous?.bpm,
                bpmConfidence = previous?.bpmConfidence,
                camelotKey = previous?.camelotKey,
                keyConfidence = previous?.keyConfidence,
                integratedLufs = previous?.integratedLufs,
                truePeakDb = previous?.truePeakDb,
            ),
        )
        return Result.failure()
    }

    private fun com.dropsync.core.common.AppError.isPermanentAnalysisFailure(): Boolean =
        when (this) {
            // Medienquelle fehlt dauerhaft (geloescht, nicht lesbar):
            // der leere Cache verhindert endloses Nachladen.
            is com.dropsync.core.common.AppError.MediaUnavailable -> true

            // Alles andere (Unknown, DatabaseFailure, ...) ist temporaer:
            // WorkManager darf erneut versuchen.
            else -> false
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
