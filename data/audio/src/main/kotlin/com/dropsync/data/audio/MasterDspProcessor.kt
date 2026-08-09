package com.dropsync.data.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import com.dropsync.domain.audio.AudioMath
import com.dropsync.domain.audio.BiquadCoefficients
import com.dropsync.domain.audio.BiquadFilter
import com.dropsync.domain.audio.BiquadType
import com.dropsync.domain.audio.DitherGenerator
import com.dropsync.domain.audio.DitherMode
import com.dropsync.domain.audio.DspConfig
import com.dropsync.domain.audio.Freeverb
import com.dropsync.domain.audio.PcmCodec
import com.dropsync.domain.audio.PcmEncoding
import com.dropsync.domain.audio.StereoMatrix
import com.dropsync.domain.audio.StreamingResampler
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.roundToInt

/**
 * Gesamte DSP-Kette als ein AudioProcessor (ADR-0005): eine einzige
 * PCM->Double->PCM-Konvertierung, alle Stufen in 64-Bit-Double.
 *
 * Reihenfolge: Preamp -> EQ -> Bass/Hoehen -> Stereo -> Reverb ->
 * Resampler -> Limiter -> DVC -> Quantisierung.
 *
 * - 16-Bit-Quellen verlassen die Kette als 16-Bit-PCM mit eigenem
 *   Dither; der Sink haengt dann nur einen No-op-Konverter an.
 * - Hi-Res-Quellen (24/32/Float) verlassen die Kette als 32-Bit-Float
 *   und nehmen den Float-Pfad des Sinks (Plan Phase 1/2).
 * - Konfiguration kommt lockfrei per [submitConfig] und wird am
 *   Blockanfang im Audiothread uebernommen; Gain-/Filteraenderungen
 *   wirken sofort. Nur eine neue Resampler-Zielrate braucht eine neue
 *   Konfiguration des Sinks und greift beim naechsten Titel.
 */
class MasterDspProcessor : BaseAudioProcessor() {
    private val pendingConfig = AtomicReference<DspConfig?>(null)

    private var config: DspConfig = DspConfig()

    // Transientes Cue-Ducking (Plan Phase 1.5): eigener Gain am
    // Preamp-Knoten, unabhaengig von config.enabled, damit Ducking und
    // DVC nie kollidieren; 1.0 = kein Ducking, nur Absenkung erlaubt.
    @Volatile
    private var duckingGain: Double = 1.0

    private var inputEncoding: PcmEncoding = PcmEncoding.PCM_16
    private var sampleRateHz = 0
    private var channelCount = 0
    private var outputRateHz = 0
    private var outputIs16Bit = false

    // Zustandsbehaftete Stufen; Aufbau in onConfigure/applyConfig.
    private var eqFilters: Array<Array<BiquadFilter>> = emptyArray()
    private var bassFilters: Array<BiquadFilter> = emptyArray()
    private var trebleFilters: Array<BiquadFilter> = emptyArray()
    private var reverb: Freeverb? = null
    private var resampler: StreamingResampler? = null
    private var dither: DitherGenerator = DitherGenerator(DitherMode.TPDF)

    private var samples = DoubleArray(0)
    private var resampled = DoubleArray(0)

    /** Letzter Ketteneingang (fuer Audioinfo/Diagnose). */
    val currentInputEncoding: PcmEncoding get() = inputEncoding

    /** Neue Konfiguration; Uebernahme am naechsten Blockanfang. */
    fun submitConfig(newConfig: DspConfig) {
        pendingConfig.set(DspConfig.sanitized(newConfig))
    }

    /** Cue-Ducking-Gain (0..1) am Preamp-Knoten; wirkt sofort. */
    fun setDuckingGain(gain: Double) {
        duckingGain = gain.coerceIn(0.0, 1.0)
    }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        inputEncoding =
            when (inputAudioFormat.encoding) {
                C.ENCODING_PCM_16BIT -> PcmEncoding.PCM_16
                C.ENCODING_PCM_24BIT -> PcmEncoding.PCM_24
                C.ENCODING_PCM_32BIT -> PcmEncoding.PCM_32
                C.ENCODING_PCM_FLOAT -> PcmEncoding.FLOAT
                else -> throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
            }
        pendingConfig.getAndSet(null)?.let { config = it }
        sampleRateHz = inputAudioFormat.sampleRate
        channelCount = inputAudioFormat.channelCount
        outputRateHz = config.resampler.targetRateHz ?: sampleRateHz
        // 16-Bit-Quellen ohne klangliche Bearbeitung bleiben 16 Bit;
        // die eigene Quantisierung uebernimmt dann das Dithering.
        outputIs16Bit = inputEncoding == PcmEncoding.PCM_16
        rebuildStages()
        return AudioProcessor.AudioFormat(
            outputRateHz,
            channelCount,
            if (outputIs16Bit) C.ENCODING_PCM_16BIT else C.ENCODING_PCM_FLOAT,
        )
    }

    override fun onFlush() {
        // Seek/Titelwechsel: Filterzustaende und Resampler-Historie leeren.
        if (sampleRateHz > 0) {
            rebuildStages()
        }
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        applyPendingConfig()
        val available = PcmCodec.sampleCount(inputBuffer, inputEncoding)
        if (available == 0) return
        if (samples.size < available) {
            samples = DoubleArray(available)
        }
        val count = PcmCodec.decode(inputBuffer, inputEncoding, samples)

        // Preamp-Knoten: Cue-Ducking zuerst, damit es mit Preamp/DVC
        // multiplikativ bleibt und nie mit der Nutzerlautstaerke kollidiert.
        val ducking = duckingGain
        if (ducking != 1.0) {
            for (i in 0 until count) {
                samples[i] *= ducking
            }
        }

        val active = config.enabled
        if (active) {
            processTonal(samples, count)
        }

        var outSamples = samples
        var outCount = count
        resampler?.let { activeResampler ->
            val frames = count / channelCount
            val needed = (activeResampler.maxOutputFrames(frames) + 8) * channelCount
            if (resampled.size < needed) {
                resampled = DoubleArray(needed)
            }
            val outFrames = activeResampler.process(samples, frames, resampled)
            outSamples = resampled
            outCount = outFrames * channelCount
        }
        if (outCount == 0) return

        if (active) {
            // Der Limiter greift nur, wenn die Kette ueberhaupt anheben
            // kann; sonst bliebe Full-Scale-Material nicht bitgenau.
            val limit = config.limiterEnabled && chainCanBoost()
            val dvcGain = if (config.dvcEnabled) config.dvcVolume else 1.0
            if (limit || dvcGain != 1.0) {
                for (i in 0 until outCount) {
                    var sample = outSamples[i]
                    if (limit) sample = AudioMath.softClip(sample)
                    outSamples[i] = sample * dvcGain
                }
            }
        }

        if (outputIs16Bit) {
            val output = replaceOutputBuffer(outCount * 2)
            writePcm16WithDither(outSamples, outCount, output)
            output.flip()
        } else {
            val output = replaceOutputBuffer(outCount * Float.SIZE_BYTES)
            PcmCodec.encodeFloat(outSamples, outCount, output)
            output.flip()
        }
    }

    /** true, wenn mindestens eine Stufe den Pegel anheben kann. */
    private fun chainCanBoost(): Boolean =
        config.preampDb > 0.0 ||
            (config.eq.enabled && config.eq.bands.any { it.gainDb > 0.0 }) ||
            config.bassGainDb > 0.0 ||
            config.trebleGainDb > 0.0 ||
            config.stereoWidthPercent > StereoMatrix.NEUTRAL_WIDTH_PERCENT ||
            config.reverb.enabled

    /** Preamp, EQ, Bass/Hoehen, Stereo, Reverb (vor dem Resampler). */
    private fun processTonal(
        data: DoubleArray,
        count: Int,
    ) {
        val gain = AudioMath.dbToLinear(config.preampDb)
        if (gain != 1.0) {
            for (i in 0 until count) {
                data[i] *= gain
            }
        }
        if (config.eq.enabled) {
            for (band in eqFilters) {
                for (channel in 0 until channelCount) {
                    band[channel].processInterleaved(data, count, channel, channelCount)
                }
            }
        }
        if (config.bassGainDb != 0.0) {
            for (channel in 0 until channelCount) {
                bassFilters[channel].processInterleaved(data, count, channel, channelCount)
            }
        }
        if (config.trebleGainDb != 0.0) {
            for (channel in 0 until channelCount) {
                trebleFilters[channel].processInterleaved(data, count, channel, channelCount)
            }
        }
        StereoMatrix.process(data, count, channelCount, config.stereoWidthPercent)
        if (config.reverb.enabled) {
            reverb?.process(data, count, channelCount)
        }
    }

    /** Quantisierung auf 16 Bit mit TPDF-/Shaped-Dither (Plan Phase 2). */
    private fun writePcm16WithDither(
        data: DoubleArray,
        count: Int,
        output: ByteBuffer,
    ) {
        val buffer = output.order(ByteOrder.LITTLE_ENDIAN)
        val mode = if (config.enabled) config.ditherMode else DitherMode.OFF
        for (i in 0 until count) {
            val scaled = AudioMath.clampSample(data[i]) * 32768.0
            val noisy = if (mode == DitherMode.OFF) scaled else scaled + dither.next()
            val quantized = noisy.roundToInt().coerceIn(-32768, 32767)
            dither.feedback(quantized - noisy)
            buffer.putShort(quantized.toShort())
        }
    }

    private fun applyPendingConfig() {
        val next = pendingConfig.getAndSet(null) ?: return
        val eqChanged =
            next.eq.bands.size != config.eq.bands.size ||
                next.ditherMode != config.ditherMode
        config = next
        if (sampleRateHz == 0) return
        if (eqChanged) {
            rebuildStages()
        } else {
            updateCoefficients()
        }
    }

    private fun rebuildStages() {
        eqFilters =
            Array(config.eq.bands.size) {
                Array(channelCount) { BiquadFilter(BiquadCoefficients.IDENTITY) }
            }
        bassFilters = Array(channelCount) { BiquadFilter(BiquadCoefficients.IDENTITY) }
        trebleFilters = Array(channelCount) { BiquadFilter(BiquadCoefficients.IDENTITY) }
        updateCoefficients()
        reverb =
            Freeverb(sampleRateHz, channelCount).also {
                it.updateSettings(
                    Freeverb.Settings(
                        roomSize = config.reverb.roomSize,
                        damping = config.reverb.damping,
                        wet = config.reverb.wet,
                    ),
                )
            }
        resampler =
            if (outputRateHz != sampleRateHz && sampleRateHz > 0) {
                StreamingResampler(sampleRateHz, outputRateHz, channelCount, config.resampler.quality)
            } else {
                null
            }
        dither = DitherGenerator(config.ditherMode)
    }

    private fun updateCoefficients() {
        val rate = sampleRateHz.toDouble()
        config.eq.bands.forEachIndexed { index, band ->
            if (index >= eqFilters.size) return@forEachIndexed
            val coefficients =
                BiquadCoefficients.of(band.type, band.frequencyHz, rate, band.gainDb, band.q)
            for (channel in 0 until channelCount) {
                eqFilters[index][channel].coefficients = coefficients
            }
        }
        val bass =
            BiquadCoefficients.of(BiquadType.LOW_SHELF, BASS_SHELF_HZ, rate, config.bassGainDb, 0.707)
        val treble =
            BiquadCoefficients.of(
                BiquadType.HIGH_SHELF,
                TREBLE_SHELF_HZ,
                rate,
                config.trebleGainDb,
                0.707,
            )
        for (channel in 0 until channelCount) {
            bassFilters[channel].coefficients = bass
            trebleFilters[channel].coefficients = treble
        }
        reverb?.updateSettings(
            Freeverb.Settings(
                roomSize = config.reverb.roomSize,
                damping = config.reverb.damping,
                wet = config.reverb.wet,
            ),
        )
    }

    companion object {
        /** Eckfrequenzen der Klangregler (Plan Phase 2). */
        const val BASS_SHELF_HZ: Double = 100.0
        const val TREBLE_SHELF_HZ: Double = 8_000.0
    }
}
