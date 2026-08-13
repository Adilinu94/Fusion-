package com.dropsync.data.audio

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import com.dropsync.core.common.AppError
import com.dropsync.core.common.AppResult
import com.dropsync.core.common.DispatcherProvider
import com.dropsync.core.model.Song
import com.dropsync.domain.audio.EnergyAccumulator
import com.dropsync.domain.audio.OnsetDetection
import com.dropsync.domain.audio.TrackAnalysis
import com.dropsync.domain.audio.TrackAnalyzer
import com.dropsync.domain.audio.WaveformAccumulator
import kotlinx.coroutines.withContext
import java.nio.ByteOrder

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
        detectOnsets: Boolean,
    ): AppResult<TrackAnalysis> =
        withContext(dispatchers.default) {
            runCatching { decodeAndAccumulate(song, detectOnsets) }
                .fold(
                    onSuccess = { AppResult.success(it) },
                    onFailure = { failure ->
                        AppResult
                            .failure(
                                AppError.MediaUnavailable(mediaStoreId = song.mediaStoreId),
                            ).also { failure.printStackTrace() }
                    },
                )
        }

    private fun decodeAndAccumulate(
        song: Song,
        detectOnsets: Boolean,
    ): TrackAnalysis {
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

            val waveform = WaveformAccumulator(totalSamples, BUCKET_COUNT)
            // Kurzzeit-Energie nur, wenn Onsets wirklich gebraucht werden:
            // der Nur-Waveform-Pfad spart so die halbe Sample-Arbeit.
            val energy =
                if (detectOnsets) {
                    EnergyAccumulator(samplesPerWindow = sampleRate * ENERGY_WINDOW_MS / 1_000)
                } else {
                    null
                }

            val codec = MediaCodec.createDecoderByType(mime)
            try {
                codec.configure(format, null, null, 0)
                codec.start()
                drainDecoder(extractor, codec, waveform, energy)
            } finally {
                codec.release()
            }

            return TrackAnalysis(
                waveformBuckets = waveform.finish(),
                // Onset-Kandidaten nur im explizit angeforderten Fall (Phase 5);
                // sonst leer, ohne die Energie ueberhaupt zu berechnen.
                onsetCandidatesMs =
                    if (detectOnsets && energy != null) {
                        OnsetDetection.detectOnsets(
                            energyWindows = energy.finish(),
                            windowDurationMs = ENERGY_WINDOW_MS.toLong(),
                        )
                    } else {
                        emptyList()
                    },
                // Track-Peak fuer die visuelle Lautheits-Normalisierung (Phase 8).
                peakLinear = waveform.peak(),
            )
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

    private fun drainDecoder(
        extractor: MediaExtractor,
        codec: MediaCodec,
        waveform: WaveformAccumulator,
        energy: EnergyAccumulator?,
    ) {
        val bufferInfo = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false
        var outputChannels = 2
        var outputPcmFloat = false

        while (!outputDone) {
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

            when (val outputIndex = codec.dequeueOutputBuffer(bufferInfo, DEQUEUE_TIMEOUT_US)) {
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val outputFormat = codec.outputFormat
                    outputChannels = outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    outputPcmFloat =
                        outputFormat.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                        outputFormat.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                }

                MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    Unit
                }

                else -> {
                    if (outputIndex >= 0) {
                        val outputBuffer = requireNotNull(codec.getOutputBuffer(outputIndex))
                        outputBuffer.position(bufferInfo.offset)
                        outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                        outputBuffer.order(ByteOrder.LITTLE_ENDIAN)
                        if (outputPcmFloat) {
                            val floats = outputBuffer.asFloatBuffer()
                            val frames = floats.remaining() / outputChannels
                            repeat(frames) { frame ->
                                var sum = 0.0
                                repeat(outputChannels) { ch ->
                                    sum += floats.get(frame * outputChannels + ch).toDouble()
                                }
                                feed(sum / outputChannels, waveform, energy)
                            }
                        } else {
                            val shorts = outputBuffer.asShortBuffer()
                            val frames = shorts.remaining() / outputChannels
                            repeat(frames) { frame ->
                                var sum = 0.0
                                repeat(outputChannels) { ch ->
                                    sum += shorts.get(frame * outputChannels + ch) / 32_768.0
                                }
                                feed(sum / outputChannels, waveform, energy)
                            }
                        }
                        codec.releaseOutputBuffer(outputIndex, false)
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            outputDone = true
                        }
                    }
                }
            }
        }
    }

    private fun feed(
        monoSample: Double,
        waveform: WaveformAccumulator,
        energy: EnergyAccumulator?,
    ) {
        waveform.accept(monoSample)
        energy?.accept(monoSample)
    }

    companion object {
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
