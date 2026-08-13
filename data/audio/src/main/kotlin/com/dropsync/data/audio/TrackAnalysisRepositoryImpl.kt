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
                ?.takeIf { it.analyzerVersion == WaveformCodec.ANALYZER_VERSION }
                ?.let {
                    // bucket_count = 0 ist der persistierte Fehlerfall
                    // (Format ohne Plattformdecoder): leere Buckets melden,
                    // damit die UI auf die Zeitleiste zurueckfaellt.
                    TrackAnalysis(
                        waveformBuckets = WaveformCodec.unpack(it.waveformData),
                        onsetCandidatesMs = emptyList(),
                        peakLinear = it.peakLinear,
                    )
                }
        }

    override suspend fun requestAnalysis(song: Song) {
        val cached = trackAnalysisDao.getBySongId(song.mediaStoreId)
        if (cached != null && cached.analyzerVersion == WaveformCodec.ANALYZER_VERSION) return
        enqueueAnalysis(song.mediaStoreId)
    }

    override suspend fun requestAnalysisForNewSongs(songs: List<Song>) {
        if (songs.isEmpty()) return
        // Eine Abfrage fuer alle Songs statt N Einzel-Queries (Import-
        // Pfad, Poweramp-Scanner-Muster: Batches bei Tausenden Titeln).
        val cachedIds =
            trackAnalysisDao
                .getBySongIds(songs.map { it.mediaStoreId })
                .filter { it.analyzerVersion == WaveformCodec.ANALYZER_VERSION }
                .mapTo(mutableSetOf()) { it.songId }
        songs.forEach { song ->
            if (song.mediaStoreId !in cachedIds) {
                enqueueAnalysis(song.mediaStoreId)
            }
        }
    }

    private fun enqueueAnalysis(mediaStoreId: Long) {
        val request =
            OneTimeWorkRequestBuilder<TrackAnalysisWorker>()
                .setInputData(workDataOf(TrackAnalysisWorker.KEY_SONG_ID to mediaStoreId))
                // Beschleunigt: die Wellenform soll moeglichst zeitnah zum
                // Titelwechsel bereitstehen, nicht erst nach WorkManager-
                // Standardlatenz. Faellt auf normale Ausfuehrung zurueck, wenn
                // kein Expedited-Kontingent frei ist (kein harter Zwang).
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()
        WorkManager
            .getInstance(context)
            .enqueueUniqueWork(
                "track_analysis_$mediaStoreId",
                ExistingWorkPolicy.KEEP,
                request,
            )
    }

    override suspend fun requestOnsetDetection(song: Song) {
        // Immer ein frischer Lauf (der Nutzer stoesst A2 bewusst an),
        // aber dedupliziert, solange bereits einer laeuft.
        val request =
            OneTimeWorkRequestBuilder<TrackAnalysisWorker>()
                .setInputData(
                    workDataOf(
                        TrackAnalysisWorker.KEY_SONG_ID to song.mediaStoreId,
                        TrackAnalysisWorker.KEY_DETECT_ONSETS to true,
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
        val detectOnsets = inputData.getBoolean(KEY_DETECT_ONSETS, false)
        val deps = EntryPointAccessors.fromApplication(applicationContext, Dependencies::class.java)
        val entity = deps.songDao().getById(songId) ?: return Result.failure()

        return when (val result = deps.trackAnalyzer().analyze(entity.toSong(), detectOnsets)) {
            is AppResult.Success -> {
                val buckets = result.value.waveformBuckets
                deps.trackAnalysisDao().upsert(
                    TrackAnalysisEntity(
                        songId = songId,
                        waveformData = WaveformCodec.pack(buckets),
                        bucketCount = buckets.size,
                        analyzerVersion = WaveformCodec.ANALYZER_VERSION,
                        analyzedAtEpochMs = deps.clock().epochMillis(),
                        peakLinear = result.value.peakLinear,
                    ),
                )
                if (detectOnsets) {
                    writeOnsetCandidates(deps, entity, result.value.onsetCandidatesMs)
                }
                Result.success()
            }

            is AppResult.Failure -> {
                // Fehlerfall persistieren (bucket_count = 0): ein Format ohne
                // Plattformdecoder scheitert auch beim naechsten Versuch; die
                // UI faellt dauerhaft auf die Zeitleiste zurueck statt endlos
                // zu laden oder neu anzustossen.
                deps.trackAnalysisDao().upsert(
                    TrackAnalysisEntity(
                        songId = songId,
                        waveformData = ByteArray(0),
                        bucketCount = 0,
                        analyzerVersion = WaveformCodec.ANALYZER_VERSION,
                        analyzedAtEpochMs = deps.clock().epochMillis(),
                    ),
                )
                Result.failure()
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
        const val KEY_DETECT_ONSETS = "detect_onsets"

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
