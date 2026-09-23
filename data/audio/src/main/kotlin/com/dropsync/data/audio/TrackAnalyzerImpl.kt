package com.dropsync.data.audio

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Log
import com.dropsync.core.common.AppError
import com.dropsync.core.common.AppResult
import com.dropsync.core.common.DispatcherProvider
import com.dropsync.core.model.Song
import com.dropsync.domain.audio.AnalysisProfile
import com.dropsync.domain.audio.ChromaAccumulator
import com.dropsync.domain.audio.DownbeatAccumulator
import com.dropsync.domain.audio.EnergyAccumulator
import com.dropsync.domain.audio.LoudnessAccumulator
import com.dropsync.domain.audio.OnsetDetection
import com.dropsync.domain.audio.TempoAccumulator
import com.dropsync.domain.audio.TrackAnalysis
import com.dropsync.domain.audio.TrackAnalyzer
import com.dropsync.domain.audio.WaveformAccumulator
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.nio.ByteOrder
import kotlin.coroutines.coroutineContext

/**
 * Dekodiert den ganzen Track einmal zu PCM (Mono-Downmix) und leitet in
 * einem Durchgang Waveform-Buckets und Kurzzeit-Energie ab
 * (Marker/Waveform-Plan Phase 2). Decoder-Weg: MediaExtractor/MediaCodec
 * direkt (ADR-0011) — Formate ohne Plattformdecoder schlagen fehl, die
 * Anzeige faellt dann laut Plan Phase 3 auf die Fortschrittsleiste zurueck.
 */
class TrackAnalyzerImpl(
    private val context: Context,
    private val dispatchers: DispatcherProvider,
) : TrackAnalyzer {
    override suspend fun analyze(
        song: Song,
        profile: AnalysisProfile,
    ): AppResult<TrackAnalysis> =
        withContext(dispatchers.default) {
            try {
                AppResult.success(decodeAndAccumulate(song, profile))
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                // Umbauplan Phase 10.4: Abbruch ist KEIN Analysefehler und
                // darf nie als Cache-Eintrag enden - weiterwerfen.
                throw cancelled
            } catch (failure: Throwable) {
                // P4-Fix #30: printStackTrace schreibt unstrukturiert nach
                // stderr und geht in Release-Builds leicht verloren. Log.e
                // bewahrt Stacktrace, Tag und Android-Log-Level.
                Log.e(LOG_TAG, "Track-Analyse fehlgeschlagen: ${song.displayName}", failure)
                AppResult
                    .failure(
                        AppError.MediaUnavailable(mediaStoreId = song.mediaStoreId),
                    )
            }
        }

    private suspend fun decodeAndAccumulate(
        song: Song,
        profile: AnalysisProfile,
    ): TrackAnalysis {
        val totalStartNs = System.nanoTime()
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, Uri.parse(song.contentUri), null)
            val trackIndex = firstAudioTrack(extractor)
            require(trackIndex >= 0) { "keine Audiospur in ${song.displayName}" }
            extractor.selectTrack(trackIndex)
            val format = extractor.getTrackFormat(trackIndex)
            val mime = requireNotNull(format.getString(MediaFormat.KEY_MIME))
            val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val durationUs =
                if (format.containsKey(MediaFormat.KEY_DURATION)) {
                    format.getLong(MediaFormat.KEY_DURATION)
                } else {
                    song.durationMs * 1_000L
                }
            val totalSamples = durationUs * sampleRate / 1_000_000L

            val includesWaveform = profile != AnalysisProfile.MIX_METADATA
            val includesMix = profile == AnalysisProfile.MIX_METADATA || profile == AnalysisProfile.FULL
            val includesOnsets =
                profile == AnalysisProfile.WAVEFORM_AND_ONSETS || profile == AnalysisProfile.FULL
            val waveform =
                if (includesWaveform) WaveformAccumulator(totalSamples, BUCKET_COUNT) else null
            // Kurzzeit-Energie nur, wenn Onsets wirklich gebraucht werden:
            // der Nur-Waveform-Pfad spart so die halbe Sample-Arbeit.
            val energy =
                if (includesOnsets) {
                    EnergyAccumulator(samplesPerWindow = sampleRate * ENERGY_WINDOW_MS / 1_000)
                } else {
                    null
                }
            // Mix-Metadaten (Phase 1) laufen additiv im selben Durchgang.
            val tempo = if (includesMix) TempoAccumulator(sampleRateHz = sampleRate) else null
            val chroma = if (includesMix) ChromaAccumulator(sampleRateHz = sampleRate) else null
            // B4: Raster-Offset fuer das Marker-Snap. Puffert die
            // Low-Band-Huellkurve und wertet sie in finalize mit dem
            // finalen BPM aus (das steht erst am Ende fest).
            val downbeat = if (includesMix) DownbeatAccumulator(sampleRateHz = sampleRate) else null
            // Lautheit/True-Peak laufen additiv (Offtrack Phase 8); die
            // Werte werden persistiert, aber erst nach Opt-in angewendet.
            val loudness = if (includesMix) LoudnessAccumulator(sampleRateHz = sampleRate) else null

            val codec = MediaCodec.createDecoderByType(mime)
            val timing: AnalysisTiming
            try {
                codec.configure(format, null, null, 0)
                codec.start()
                timing = drainDecoder(extractor, codec, waveform, energy, tempo, chroma, downbeat, loudness)
            } finally {
                codec.release()
            }

            val finalizeStartNs = System.nanoTime()
            val tempoEstimate = tempo?.finishEstimate()
            val keyEstimate = chroma?.finishEstimate()
            // B4: braucht das FINALE BPM (erst hier bekannt).
            val downbeatEstimate = downbeat?.finish(tempoEstimate?.bpm)
            val analysis =
                TrackAnalysis(
                    waveformBuckets = waveform?.finish().orEmpty(),
                    // Onset-Kandidaten nur im explizit angeforderten Fall (Phase 5);
                    // sonst leer, ohne die Energie ueberhaupt zu berechnen.
                    onsetCandidatesMs =
                        if (includesOnsets && energy != null) {
                            OnsetDetection.detectOnsets(
                                energyWindows = energy.finish(),
                                windowDurationMs = ENERGY_WINDOW_MS.toLong(),
                            )
                        } else {
                            emptyList()
                        },
                    // Track-Peak fuer die visuelle Lautheits-Normalisierung (Phase 8).
                    peakLinear = waveform?.peak() ?: 0.0,
                    bpm = tempoEstimate?.bpm,
                    camelotKey = keyEstimate?.camelotKey,
                    bpmConfidence = tempoEstimate?.confidence,
                    keyConfidence = keyEstimate?.confidence,
                    integratedLufs = loudness?.integratedLufs(),
                    truePeakDb = loudness?.let { truePeakDb(it.truePeakLinear()) },
                    downbeatOffsetMs = downbeatEstimate?.offsetMs,
                    downbeatConfidence = downbeatEstimate?.confidence,
                )
            val finalizeMs = (System.nanoTime() - finalizeStartNs) / NS_PER_MS
            val totalMs = (System.nanoTime() - totalStartNs) / NS_PER_MS
            val dequeueWaitMs = timing.dequeueWaitNs / NS_PER_MS
            val accumulateMs = timing.accumulateNs / NS_PER_MS
            Log.i(
                TIMING_LOG_TAG,
                "songId=${song.mediaStoreId} durationMs=${song.durationMs} " +
                    "samples=${timing.sampleCount} buffers=${timing.outputBuffers} " +
                    "dequeueWaitMs=$dequeueWaitMs accumulateMs=$accumulateMs " +
                    "finalizeMs=$finalizeMs " +
                    "overheadMs=${(totalMs - dequeueWaitMs - accumulateMs - finalizeMs).coerceAtLeast(0L)} " +
                    "totalMs=$totalMs profile=${profile.name}",
            )
            return analysis
        } finally {
            extractor.release()
        }
    }

    private fun firstAudioTrack(extractor: MediaExtractor): Int {
        for (i in 0 until extractor.trackCount) {
            val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME).orEmpty()
            if (mime.startsWith("audio/")) return i
        }
        return -1
    }

    private suspend fun drainDecoder(
        extractor: MediaExtractor,
        codec: MediaCodec,
        waveform: WaveformAccumulator?,
        energy: EnergyAccumulator?,
        tempo: TempoAccumulator?,
        chroma: ChromaAccumulator?,
        downbeat: DownbeatAccumulator?,
        loudness: LoudnessAccumulator?,
    ): AnalysisTiming {
        val bufferInfo = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false
        var outputChannels = 2
        var outputPcmFloat = false
        var dequeueWaitNs = 0L
        var accumulateNs = 0L
        var sampleCount = 0L
        var outputBuffers = 0

        while (!outputDone) {
            // Abbruchkooperation je Schleifendurchlauf (Umbauplan
            // Grundregeln): Kotlin-Coroutinen brechen kooperativ ab, und
            // diese Schleife hat sonst KEINEN Suspension-Punkt - ein
            // abgebrochener Lauf wuerde bis zum Trackende weiterlaufen und
            // dabei CPU und eine MediaCodec-Instanz halten. Genau darauf
            // baut aber das Cancel-und-Ueberholen des Prioritaetspfads
            // (ADR-0015): ohne diese Zeile blockieren zwei Zombie-Laeufe
            // die Semaphore(2)-Lane fuer den Titel, den der Nutzer ansieht.
            //
            // Die Pruefung sitzt am Schleifenkopf, nicht nach
            // releaseOutputBuffer: der INFO_TRY_AGAIN_LATER-Zweig unten
            // springt per continue zurueck und wuerde eine Pruefung am
            // Schleifenende ueberspringen - ein wartender Decoder waere
            // dann weiterhin nicht abbrechbar.
            coroutineContext.ensureActive()

            if (!inputDone) {
                val inputIndex = codec.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
                if (inputIndex >= 0) {
                    val inputBuffer = requireNotNull(codec.getInputBuffer(inputIndex))
                    val sampleSize = extractor.readSampleData(inputBuffer, 0)
                    if (sampleSize < 0) {
                        codec.queueInputBuffer(inputIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        codec.queueInputBuffer(inputIndex, 0, sampleSize, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }

            val dequeueStartNs = System.nanoTime()
            val outputIndex = codec.dequeueOutputBuffer(bufferInfo, DEQUEUE_TIMEOUT_US)
            dequeueWaitNs += System.nanoTime() - dequeueStartNs
            when (outputIndex) {
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val outputFormat = codec.outputFormat
                    outputChannels = outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    outputPcmFloat =
                        outputFormat.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                        outputFormat.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                }

                MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    continue
                }

                else -> {
                    if (outputIndex >= 0) {
                        outputBuffers++
                        val outputBuffer = requireNotNull(codec.getOutputBuffer(outputIndex))
                        outputBuffer.position(bufferInfo.offset)
                        outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                        outputBuffer.order(ByteOrder.LITTLE_ENDIAN)
                        if (outputPcmFloat) {
                            val floats = outputBuffer.asFloatBuffer()
                            val frames = floats.remaining() / outputChannels
                            val accumulateStartNs = System.nanoTime()
                            repeat(frames) { frame ->
                                var sum = 0.0
                                repeat(outputChannels) { ch ->
                                    sum += floats.get(frame * outputChannels + ch).toDouble()
                                }
                                feed(sum / outputChannels, waveform, energy, tempo, chroma, downbeat, loudness)
                            }
                            accumulateNs += System.nanoTime() - accumulateStartNs
                            sampleCount += frames
                        } else {
                            val shorts = outputBuffer.asShortBuffer()
                            val frames = shorts.remaining() / outputChannels
                            val accumulateStartNs = System.nanoTime()
                            repeat(frames) { frame ->
                                var sum = 0.0
                                repeat(outputChannels) { ch ->
                                    sum += shorts.get(frame * outputChannels + ch) / 32_768.0
                                }
                                feed(sum / outputChannels, waveform, energy, tempo, chroma, downbeat, loudness)
                            }
                            accumulateNs += System.nanoTime() - accumulateStartNs
                            sampleCount += frames
                        }
                        codec.releaseOutputBuffer(outputIndex, false)
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            outputDone = true
                        }
                    }
                }
            }
        }
        return AnalysisTiming(
            dequeueWaitNs = dequeueWaitNs,
            accumulateNs = accumulateNs,
            sampleCount = sampleCount,
            outputBuffers = outputBuffers,
        )
    }

    private fun feed(
        monoSample: Double,
        waveform: WaveformAccumulator?,
        energy: EnergyAccumulator?,
        tempo: TempoAccumulator?,
        chroma: ChromaAccumulator?,
        downbeat: DownbeatAccumulator?,
        loudness: LoudnessAccumulator?,
    ) {
        waveform?.accept(monoSample)
        energy?.accept(monoSample)
        tempo?.accept(monoSample)
        chroma?.accept(monoSample)
        downbeat?.accept(monoSample)
        loudness?.accept(monoSample)
    }

    private fun truePeakDb(peakLinear: Double): Float? {
        if (peakLinear <= 0.0) return null
        return (20.0 * kotlin.math.log10(peakLinear)).toFloat()
    }

    companion object {
        private const val LOG_TAG = "TrackAnalyzer"
        private const val TIMING_LOG_TAG = "TrackAnalysisTiming"
        private const val NS_PER_MS = 1_000_000L

        /**
         * Buckets ueber den ganzen Track (Plan Phase 2). Bewusst grober als
         * frueher (500): 256 reicht fuer die Optik voellig, halbiert die
         * gespeicherte Datenmenge und beschleunigt Analyse wie Zeichnen.
         */
        const val BUCKET_COUNT: Int = 256

        /** Kurzzeit-Energie-Fenster (Plan Phase 5: 20-50 ms). */
        const val ENERGY_WINDOW_MS: Int = 25

        private const val DEQUEUE_TIMEOUT_US = 10_000L
    }
}

private data class AnalysisTiming(
    val dequeueWaitNs: Long,
    val accumulateNs: Long,
    val sampleCount: Long,
    val outputBuffers: Int,
)
