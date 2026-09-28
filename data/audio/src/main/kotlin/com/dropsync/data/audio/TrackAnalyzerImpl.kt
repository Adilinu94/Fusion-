package com.dropsync.data.audio

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
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
import java.io.IOException
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
            } catch (unavailable: Exception) {
                // P4-Fix #30: printStackTrace schreibt unstrukturiert nach
                // stderr und geht in Release-Builds leicht verloren. Log.e
                // bewahrt Stacktrace, Tag und Android-Log-Level.
                //
                // 2026-09-27, Befund 4.10: die Ausnahme war ungefiltert
                // (`catch (Throwable)`) und wurde **immer** zu
                // `MediaUnavailable`. Damit war jede andere Stoerung —
                // ein OutOfMemoryError bei einem 20-Minuten-Jamboree auf
                // einem 2-GB-Geraet, eine SQLiteException aus dem
                // spaeteren Schreibvorgang, ein IllegalStateException
                // eines klemmenden MediaCodec — als *dauerhafter*
                // Medienfehler gecacht. Der Titel war fuer immer tot,
                // `isPermanentAnalysisFailure` konnte den temporaeren
                // Zweig nie erreichen.
                //
                // Jetzt: nur Fehler, die wirklich an der Datei liegen,
                // sind dauerhaft. Ressourcen- und Zufallsfehler gehen an
                // WorkManager mit Backoff zurueck.
                Log.e(LOG_TAG, "Track-Analyse fehlgeschlagen: ${song.displayName}", unavailable)
                val permanent =
                    unavailable is IOException ||
                        unavailable is IllegalArgumentException ||
                        unavailable.cause is IOException
                if (permanent) {
                    AppResult.failure(
                        AppError.MediaUnavailable(mediaStoreId = song.mediaStoreId),
                    )
                } else {
                    AppResult.failure(
                        AppError.Unknown("Analyse ${song.displayName}: ${unavailable.message}"),
                    )
                }
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

            // 2026-09-27, Befund 9.2: die zeitabhaengigen Akkumulatoren
            // wurden mit der **Container**-Rate gebaut, der Decoder liefert
            // aber moeglicherweise eine andere. `INFO_OUTPUT_FORMAT_CHANGED`
            // wurde gelesen und die Rate dann weggeworfen. Bei einem 48-kHz-
            // Decoder auf einem 44,1-kHz-Container sind die Fenster real
            // 6,6 % zu kurz — und damit **jede** Onset-Position, jedes BPM,
            // der Downbeat-Offset und jedes Marker-Snap systematisch
            // verschoben. Still, dauerhaft, falsch.
            //
            // Die Akkumulatoren entstehen deshalb **lazy**, beim ersten
            // Puffer mit bekannter Ausgabequote. `createStages` ist eine
            // Funktion statt eines Blocks, weil die Stufen nicht vor
            // `MediaCodec.start()` bekannt sind.
            val stages = LazyAnalysisStages()

            val (codec, codecName) = createDecoder(mime)
            val timing: AnalysisTiming
            try {
                codec.configure(format, null, null, 0)
                codec.start()
                timing =
                    drainDecoder(
                        extractor = extractor,
                        codec = codec,
                        waveform = waveform,
                        stages = stages,
                        includesOnsets = includesOnsets,
                        includesMix = includesMix,
                        containerRate = sampleRate,
                    )
            } finally {
                codec.release()
            }

            val finalizeStartNs = System.nanoTime()
            val tempoEstimate = stages.tempo?.finishEstimate()
            val keyEstimate = stages.chroma?.finishEstimate()
            // B4: braucht das FINALE BPM (erst hier bekannt).
            val downbeatEstimate = stages.downbeat?.finish(tempoEstimate?.bpm)
            val analysis =
                TrackAnalysis(
                    waveformBuckets = waveform?.finish().orEmpty(),
                    // Onset-Kandidaten nur im explizit angeforderten Fall (Phase 5);
                    // sonst leer, ohne die Energie ueberhaupt zu berechnen.
                    onsetCandidatesMs =
                        if (includesOnsets && stages.energy != null) {
                            OnsetDetection.detectOnsets(
                                energyWindows = stages.energy!!.finish(),
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
                    integratedLufs = stages.loudness?.integratedLufs(),
                    truePeakDb = stages.loudness?.let { truePeakDb(it.truePeakLinear()) },
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
                    "codec=$codecName " +
                    "samples=${timing.sampleCount} buffers=${timing.outputBuffers} " +
                    "dequeueWaitMs=$dequeueWaitMs accumulateMs=$accumulateMs " +
                    "finalizeMs=$finalizeMs " +
                    "overheadMs=${(totalMs - dequeueWaitMs - accumulateMs - finalizeMs).coerceAtLeast(0L)} " +
                    "totalMs=$totalMs profile=${profile.name} " +
                    "outputRateHz=${timing.outputRateHz} containerRateHz=$sampleRate",
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

    /**
     * 2026-09-27, Befund 17.5: `MediaCodec.createDecoderByType(mime)` war
     * eine Fettfrage. Die Auswahl ist implementierungsabhaengig, und das
     * Framework bevorzugt typischerweise den **Software**-Decoder, weil er
     * "billiger" erscheint. Software-MP3-Dekode fuer vier Minuten liegt
     * auf Mittelklasse-Hardware realistisch bei 2–5x Echtzeit — das ist
     * genau die unbekannte Groesse, an der der 1,5-s-Zielwert haengt.
     *
     * `MediaCodecList` erlaubt die Ausdrueckliche Wahl. Bevorzugt wird
     * ein **Hardware**-Decoder; gibt es keinen, wird auf den
     * Framework-Default zurueckgefallen (Software ist dann besser als
     * gar kein Decoder). Der gewaehlte Name landet im Timing-Log, damit
     * die Frage messbar statt vermutet ist.
     */
    private fun createDecoder(mime: String): Pair<MediaCodec, String> {
        val hardwareName =
            runCatching {
                MediaCodecList(MediaCodecList.REGULAR_CODECS)
                    .codecInfos
                    .firstOrNull { info ->
                        !info.isEncoder &&
                            info.supportedTypes.any { it.equals(mime, ignoreCase = true) } &&
                            !isSoftwareDecoder(info.name)
                    }?.name
            }.getOrNull()
        val hardwareCodec =
            if (hardwareName != null) {
                runCatching { MediaCodec.createByCodecName(hardwareName) }.getOrNull()
            } else {
                null
            }
        return if (hardwareCodec != null && hardwareName != null) {
            hardwareCodec to hardwareName
        } else {
            // Kein Hardware-Decoder fuer dieses Format. Software ist dann
            // besser als gar keiner — aber der Name wird geloggt, damit
            // die Performance-Messung die Frage beantworten kann.
            MediaCodec.createDecoderByType(mime) to "$mime (Software)"
        }
    }

    /**
     * Erkennt einen **Software**-Decoder anhand seines Namens.
     *
     * `MediaCodecInfo.isHardwareAccelerated` (API 29) und
     * `MediaCodec.codecName` (API 29) existieren in der Android-SDK, sind
     * aber ueber den in diesem Projekt genutzten `compileSdk` nicht
     * aufloesbar. Deshalb wird ausschliesslich der Codec-Listen-Name
     * ausgewertet — und das ist fuer die Frage ausreichend, weil die
     * Software-Codecs auf **allen** Herstellern gleichermassen
     * benannt sind:
     *
     * - `OMX.google.*`, `OMX.ffmpeg.*`, `c2.android.*` — Software
     * - `OMX.qcom.*`, `OMX.exynos.*`, `OMX.mtk.*`, `OMX.hisi.*` — Hardware
     *
     * Die Liste deckt damit Geraete von Samsung, Qualcomm, MediaTek,
     * HiSilicon und den reinen AOSP-Build ab. Es gibt keinen Fall
     * dazwischen, der beim Dekodieren von **Audio** auffaellt: die
     * Audio-Ausgabe eines Software-Decoders ist hostseitig, ein
     * Hardware-Audio-Decoder schreibt direkt in den DAC.
     */
    private fun isSoftwareDecoder(name: String): Boolean {
        val lower = name.lowercase()
        return lower.endsWith(".google.") ||
            lower.endsWith(".ffmpeg.") ||
            lower.contains(".android.") ||
            lower.contains("software")
    }

    /**
     * Dekodiert den ganzen Track und fuettert die Akkumulatoren.
     *
     * Die zeitabhaengigen Stufen werden **hier** erzeugt, beim ersten
     * Puffer mit bekannter Ausgabequote (Befund 9.2). `stages.ensure(...)`
     * ist idempotent: ab dem zweiten Puffer passiert nichts.
     */
    private suspend fun drainDecoder(
        extractor: MediaExtractor,
        codec: MediaCodec,
        waveform: WaveformAccumulator?,
        stages: LazyAnalysisStages,
        includesOnsets: Boolean,
        includesMix: Boolean,
        containerRate: Int,
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
        // Befund 9.2/17.5: die tatsaechliche Ausgabequote und der
        // Kanalname gehoeren in den Timing-Log, damit "Decoder laeuft in
        // Software" und "Decoder-Rate weicht vom Container ab" belegbar
        // sind statt vermutet.
        var outputRateHz = 0

        // 2026-09-27, Befund 4.11: der Decoder bekam **kein** Gesamt-Timeout.
        // `dequeueInputBuffer` liefert bei Timeout -1, der Zweig fiel still
        // durch, und `while (!outputDone)` pollte endlos weiter: 10-ms-
        // Takt, kein Ergebnis, kein Abbruch (der Aufrufer kann nicht
        // abbrechen, weil die Coroutine nie suspendiert), und der
        // `Semaphore(2)`-Slot bleibt fuer den Titel des Nutzers belegt.
        //
        // Die Frist ist doppelt begrenzt: absolut (ein 20-Minuten-Track
        // darf nicht ewig laufen) und relativ zur erwarteten Laufzeit. Die
        // relative Grenze haengt an der *dekodierten* Dauer, nicht an der
        // Dateigroesse, weil ein langsamer Codec auf einem schwachen
        // Geraet tatsaechlich laenger braucht.
        val startNs = System.nanoTime()
        val absoluteDeadlineNs = startNs + ABSOLUTE_TIMEOUT_MS * 1_000_000L
        var consecutiveTimeouts = 0

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

            if (System.nanoTime() > absoluteDeadlineNs) {
                throw IOException(
                    "Decoder nach $ABSOLUTE_TIMEOUT_MS ms ohne Fortschritt " +
                        "($outputBuffers Puffer, $sampleCount Samples)",
                )
            }

            if (!inputDone) {
                val inputIndex = codec.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
                if (inputIndex >= 0) {
                    consecutiveTimeouts = 0
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
                    // Befund 9.2: die Ausgabequote jetzt **verwenden**. Vorher
                    // wurde sie gelesen und weggeworfen, wodurch alle
                    // zeitabhaengigen Akkumulatoren auf der Container-Rate
                    // liefen und bei abweichender Decoder-Rate systematisch
                    // falsche Positionen lieferten.
                    val outputRate =
                        outputFormat
                            .getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    outputRateHz = outputRate
                    stages.ensure(
                        sampleRateHz = outputRate,
                        includesOnsets = includesOnsets,
                        includesMix = includesMix,
                    )
                    if (outputRate != containerRate) {
                        Log.i(
                            TIMING_LOG_TAG,
                            "Decoder-Rate $outputRate Hz weicht von der Container-Rate " +
                                "$containerRate Hz ab — Akkumulatoren auf die Ausgabe umgestellt",
                        )
                    }
                }

                MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    // 2026-09-27, Befund 4.11: `continue` ohne Auswertung
                    // war der Endlosschleifen-Pfad. Bei einem klemmenden
                    // Decoder kommt hier nichts mehr; nach
                    // [STALL_TIMEOUT_COUNT] solchen Takten ohne jedes
                    // Fortschrittssignal ist die Datei fuer diesen
                    // Decoder nicht lesbar.
                    consecutiveTimeouts++
                    if (consecutiveTimeouts > STALL_TIMEOUT_COUNT) {
                        throw IOException(
                            "Decoder lieferte $STALL_TIMEOUT_COUNT mal hintereinander " +
                                "keinen Puffer ($outputBuffers Puffer, $sampleCount Samples)",
                        )
                    }
                    continue
                }

                else -> {
                    if (outputIndex >= 0) {
                        // Fortschritt: der Stall-Zaehler darf nicht
                        // mitlaufen, waehrend der Codec arbeitet.
                        consecutiveTimeouts = 0
                        outputBuffers++
                        val outputBuffer = requireNotNull(codec.getOutputBuffer(outputIndex))
                        outputBuffer.position(bufferInfo.offset)
                        outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                        outputBuffer.order(ByteOrder.LITTLE_ENDIAN)
                        // 2026-09-27 (Detekt LongMethod): die PCM-Auswertung
                        // ist aus [drainDecoder] herausgezogen. Sie war der
                        // Grund, warum die Schleife ueber die Grenze kam —
                        // und sie ist in sich geschlossen (Float **oder**
                        // Short, sonst nichts Unterschiedliches).
                        val accumulateStartNs = System.nanoTime()
                        val frames = decodeAndFeed(outputBuffer, outputPcmFloat, outputChannels, waveform, stages)
                        accumulateNs += System.nanoTime() - accumulateStartNs
                        sampleCount += frames
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
            outputRateHz = outputRateHz,
        )
    }

    /**
     * Wertet einen dekodierten Ausgabepuffer aus und speist die
     * Akkumulatoren (2026-09-27, Detekt `LongMethod`).
     *
     * **Was herauskam:** Float- und Short-PCM unterscheiden sich nur in
     * zwei Dingen — wie der Wert gelesen wird (`.toDouble()` gegen
     * `/ 32_768.0`) und welcher Puffer-Typ (`asFloatBuffer()` gegen
     * `asShortBuffer()`). Der Rest ist identisch: Kanäle mitteln, an
     * `feed` geben, Frame zählen.
     *
     * **Warum das nicht in ein `when` ueber die beiden Typen ging:** `when`
     * mit `asFloatBuffer()` im Zweig liefert keinen gemeinsamen
     * Compiletime-Typ fuer den Zugriff, und ein
     * `sampleAt(index): Double`-Lambda haette die Bedingung pro Sample
     * erneut geprueft — bei 44.100 Hz x 2 Kanaelen x 4 Minuten sind das
     * 21 Millionen zusaetzliche Vergleiche pro Titel. Die Dopplung der
     * drei Zeilen ist billiger als der Test.
     *
     * @return Anzahl verarbeiteter Frames (Samples je Kanal).
     */
    private fun decodeAndFeed(
        outputBuffer: java.nio.ByteBuffer,
        outputPcmFloat: Boolean,
        outputChannels: Int,
        waveform: WaveformAccumulator?,
        stages: LazyAnalysisStages,
    ): Int {
        if (outputPcmFloat) {
            val floats = outputBuffer.asFloatBuffer()
            val frames = floats.remaining() / outputChannels
            repeat(frames) { frame ->
                var sum = 0.0
                repeat(outputChannels) { ch ->
                    sum += floats.get(frame * outputChannels + ch).toDouble()
                }
                feed(sum / outputChannels, waveform, stages)
            }
            return frames
        }
        val shorts = outputBuffer.asShortBuffer()
        val frames = shorts.remaining() / outputChannels
        repeat(frames) { frame ->
            var sum = 0.0
            repeat(outputChannels) { ch ->
                sum += shorts.get(frame * outputChannels + ch) / 32_768.0
            }
            feed(sum / outputChannels, waveform, stages)
        }
        return frames
    }

    private fun feed(
        monoSample: Double,
        waveform: WaveformAccumulator?,
        stages: LazyAnalysisStages,
    ) {
        waveform?.accept(monoSample)
        stages.energy?.accept(monoSample)
        stages.tempo?.accept(monoSample)
        stages.chroma?.accept(monoSample)
        stages.downbeat?.accept(monoSample)
        stages.loudness?.accept(monoSample)
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

        /**
         * Harte Obergrenze fuer einen Analyse-Durchlauf (Befund 4.11).
         *
         * 5 Minuten sind das 3- bis 5-Fache einer realistischen Laufzeit
         * (Zielwert 1,5 s pro Titel, Worst Case Software-Dekode auf einem
         * schwachen Geraet bei einem langen Jamcore). Der Wert gilt fuer
         * **einen** Titel; die Bibliothek besteht aus vielen kurzen Laeufen,
         * nicht aus einem langen.
         */
        const val ABSOLUTE_TIMEOUT_MS: Long = 300_000L

        /**
         * Anzahl aufeinanderfolgender `INFO_TRY_AGAIN_LATER` ohne
         * Fortschritt, ab der die Datei als nicht lesbar gilt.
         *
         * 3000 * 10 ms = 30 s ohne einen einzigen Puffer. Das ist
         * unplausibel fuer eine gesunde Datei, aber leicht erreichbar
         * fuer eine defekte — und 30 s haelt den `Semaphore(2)`-Slot nicht
         * minutenlang blockiert.
         */
        const val STALL_TIMEOUT_COUNT: Int = 3_000
    }
}

private data class AnalysisTiming(
    val dequeueWaitNs: Long,
    val accumulateNs: Long,
    val sampleCount: Long,
    val outputBuffers: Int,
    /** Tatsaechliche Decoder-Ausgabequote (Befund 9.2/17.5). */
    val outputRateHz: Int,
)

/**
 * Zeitabhaengige Akkumulatoren, erzeugt beim ersten Puffer mit bekannter
 * **Ausgabe**-Abtastrate (2026-09-27, Befund 9.2).
 *
 * Ausgangslage: alle Stufen wurden vor `MediaCodec.start()` mit der
 * Container-Rate gebaut. Der Decoder darf aber eine andere liefern — und
 * liefert sie haeufig. Bei 48 kHz Ausgabe auf einem 44,1-kHz-Container
 * waren die 25-ms-Fenster real 6,6 % zu kurz, und damit **jede**
 * Onset-Position, jedes BPM-Histogramm, der Downbeat-Offset und jedes
 * Marker-Snap systematisch verschoben. Still, dauerhaft, falsch, und
 * durch keinen Test abgedeckt (alle Tests nutzen dieselbe Rate fuer
 * Container und Ausgabe).
 *
 * [ensure] wird einmal je Analyse aufgerufen, beim
 * `INFO_OUTPUT_FORMAT_CHANGED`. Danach ist sie ein No-op — die Stufen
 * duerfen ihren Fensterzustand nicht verlieren, weil ein zweites
 * Format-Event mitten im Track kommt.
 */
internal class LazyAnalysisStages {
    var energy: EnergyAccumulator? = null
        private set
    var tempo: TempoAccumulator? = null
        private set
    var chroma: ChromaAccumulator? = null
        private set
    var downbeat: DownbeatAccumulator? = null
        private set
    var loudness: LoudnessAccumulator? = null
        private set

    private var created = false

    fun ensure(
        sampleRateHz: Int,
        includesOnsets: Boolean,
        includesMix: Boolean,
    ) {
        if (created) return
        // Eine Rate von 0 oder weniger enthaelt keine Information; die
        // Stufen bleiben dann null, statt mit einer Unsinnsrate zu laufen.
        if (sampleRateHz <= 0) return
        created = true
        // Kurzzeit-Energie nur, wenn Onsets wirklich gebraucht werden:
        // der Nur-Waveform-Pfad spart so die halbe Sample-Arbeit.
        if (includesOnsets) {
            energy = EnergyAccumulator(samplesPerWindow = energyWindowSamples(sampleRateHz))
        }
        if (includesMix) {
            tempo = TempoAccumulator(sampleRateHz = sampleRateHz)
            chroma = ChromaAccumulator(sampleRateHz = sampleRateHz)
            downbeat = DownbeatAccumulator(sampleRateHz = sampleRateHz)
            loudness = LoudnessAccumulator(sampleRateHz = sampleRateHz)
        }
    }

    /** Samples je 25-ms-Fenster bei [sampleRateHz]. */
    fun energyWindowSamples(sampleRateHz: Int): Int =
        (sampleRateHz * ENERGY_WINDOW_MS / 1_000L).toInt().coerceAtLeast(1)

    companion object {
        /** Kurzzeit-Energie-Fenster des Onset-Pfads (Phase 5: 20-50 ms). */
        const val ENERGY_WINDOW_MS: Long = 25L
    }
}
