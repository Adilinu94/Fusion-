package com.dropsync.data.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import com.dropsync.domain.audio.DitherMode
import com.dropsync.domain.audio.DspConfig
import com.dropsync.domain.audio.EqBand
import com.dropsync.domain.audio.EqMode
import com.dropsync.domain.audio.EqSettings
import com.dropsync.domain.audio.ResamplerQuality
import com.dropsync.domain.audio.ResamplerSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.sin

/** Gesamtkette in einem Prozessor (Plan Phase 2, ADR-0005). */
class MasterDspProcessorTest {
    private fun processor(config: DspConfig = DspConfig()): MasterDspProcessor {
        val processor = MasterDspProcessor()
        processor.submitConfig(config)
        return processor
    }

    private fun pcm16Buffer(vararg values: Short): ByteBuffer {
        val buffer =
            ByteBuffer
                .allocateDirect(values.size * 2)
                .order(ByteOrder.LITTLE_ENDIAN)
        values.forEach(buffer::putShort)
        buffer.flip()
        return buffer
    }

    private fun floatBuffer(vararg values: Float): ByteBuffer {
        val buffer =
            ByteBuffer
                .allocateDirect(values.size * 4)
                .order(ByteOrder.LITTLE_ENDIAN)
        values.forEach(buffer::putFloat)
        buffer.flip()
        return buffer
    }

    private fun readShorts(buffer: ByteBuffer): ShortArray {
        val ordered = buffer.order(ByteOrder.LITTLE_ENDIAN)
        val result = ShortArray(ordered.remaining() / 2)
        for (i in result.indices) {
            result[i] = ordered.short
        }
        return result
    }

    private fun readFloats(buffer: ByteBuffer): FloatArray {
        val ordered = buffer.order(ByteOrder.LITTLE_ENDIAN)
        val result = FloatArray(ordered.remaining() / 4)
        for (i in result.indices) {
            result[i] = ordered.float
        }
        return result
    }

    @Test
    fun `pcm16 quellen bleiben 16 bit und hires wird float`() {
        val processor = processor()
        val out16 =
            processor.configure(AudioProcessor.AudioFormat(44_100, 2, C.ENCODING_PCM_16BIT))
        assertEquals(C.ENCODING_PCM_16BIT, out16.encoding)

        val processorHiRes = processor()
        val outFloat =
            processorHiRes.configure(AudioProcessor.AudioFormat(96_000, 2, C.ENCODING_PCM_24BIT))
        assertEquals(C.ENCODING_PCM_FLOAT, outFloat.encoding)
        assertEquals(96_000, outFloat.sampleRate)
    }

    @Test
    fun `neutrale kette laesst pcm16 bitgenau durch`() {
        // Dither OFF: identische Werte in und out (Roundtrip ohne Verlust).
        val processor = processor(DspConfig(ditherMode = DitherMode.OFF))
        processor.configure(AudioProcessor.AudioFormat(48_000, 2, C.ENCODING_PCM_16BIT))
        processor.flush()

        processor.queueInput(pcm16Buffer(1000, -1000, 32767, -32768))
        val output = readShorts(processor.output)
        assertEquals(1000, output[0].toInt())
        assertEquals(-1000, output[1].toInt())
        assertEquals(32767, output[2].toInt())
        assertEquals(-32768, output[3].toInt())
    }

    @Test
    fun `preamp plus 6 db verdoppelt float samples`() {
        val processor =
            processor(DspConfig(preampDb = 6.0206, limiterEnabled = false))
        processor.configure(AudioProcessor.AudioFormat(48_000, 1, C.ENCODING_PCM_FLOAT))
        processor.flush()

        processor.queueInput(floatBuffer(0.2f, -0.3f))
        val output = readFloats(processor.output)
        assertEquals(0.4f, output[0], 1e-4f)
        assertEquals(-0.6f, output[1], 1e-4f)
    }

    @Test
    fun `dvc reduziert die lautstaerke verlustfrei im floatpfad`() {
        val processor =
            processor(
                DspConfig(dvcEnabled = true, dvcVolume = 0.5, limiterEnabled = false),
            )
        processor.configure(AudioProcessor.AudioFormat(48_000, 1, C.ENCODING_PCM_FLOAT))
        processor.flush()

        processor.queueInput(floatBuffer(0.8f))
        assertEquals(0.4f, readFloats(processor.output)[0], 1e-6f)
    }

    @Test
    fun `cue ducking wirkt am preamp knoten auch bei deaktivierter kette`() {
        // Plan Phase 1.5: Ducking ist vom DSP-Schalter unabhaengig und
        // muss nach der Ansage exakt auf den Basiswert zurueckkehren.
        val processor = processor(DspConfig(enabled = false))
        processor.configure(AudioProcessor.AudioFormat(48_000, 1, C.ENCODING_PCM_FLOAT))
        processor.flush()

        processor.setDuckingGain(0.5)
        processor.queueInput(floatBuffer(0.8f))
        assertEquals(0.4f, readFloats(processor.output)[0], 1e-6f)

        processor.setDuckingGain(1.0)
        processor.queueInput(floatBuffer(0.8f))
        assertEquals(0.8f, readFloats(processor.output)[0], 1e-6f)
    }

    @Test
    fun `cue ducking und dvc bleiben multiplikativ und kollidieren nicht`() {
        // Plan Phase 1.5: Ducking (Preamp-Knoten) x DVC (Kettenende).
        val processor =
            processor(
                DspConfig(dvcEnabled = true, dvcVolume = 0.5, limiterEnabled = false),
            )
        processor.configure(AudioProcessor.AudioFormat(48_000, 1, C.ENCODING_PCM_FLOAT))
        processor.flush()

        processor.setDuckingGain(0.5)
        processor.queueInput(floatBuffer(0.8f))
        assertEquals(0.2f, readFloats(processor.output)[0], 1e-6f)
    }

    @Test
    fun `eq band senkt einen sinus im band messbar ab`() {
        val config =
            DspConfig(
                eq =
                    EqSettings(
                        enabled = true,
                        mode = EqMode.PARAMETRIC,
                        bands = listOf(EqBand(frequencyHz = 1_000.0, gainDb = -12.0, q = 1.0)),
                    ),
                limiterEnabled = false,
                ditherMode = DitherMode.OFF,
            )
        val processor = processor(config)
        processor.configure(AudioProcessor.AudioFormat(48_000, 1, C.ENCODING_PCM_FLOAT))
        processor.flush()

        val frames = 9_600
        val input = FloatArray(frames) { (0.5 * sin(2.0 * Math.PI * 1_000.0 * it / 48_000.0)).toFloat() }
        val buffer = ByteBuffer.allocateDirect(frames * 4).order(ByteOrder.LITTLE_ENDIAN)
        input.forEach(buffer::putFloat)
        buffer.flip()
        processor.queueInput(buffer)
        val output = readFloats(processor.output)

        var peak = 0f
        for (i in output.size / 2 until output.size) {
            peak = maxOf(peak, abs(output[i]))
        }
        // -12 dB auf 0.5 -> ~0.125.
        assertEquals(0.125f, peak, 0.02f)
    }

    @Test
    fun `resampler liefert die konfigurierte zielrate`() {
        val config =
            DspConfig(
                resampler = ResamplerSettings(targetRateHz = 96_000, quality = ResamplerQuality.SINC),
            )
        val processor = processor(config)
        val outputFormat =
            processor.configure(AudioProcessor.AudioFormat(48_000, 2, C.ENCODING_PCM_FLOAT))
        assertEquals(96_000, outputFormat.sampleRate)
        processor.flush()

        val frames = 480
        val buffer = ByteBuffer.allocateDirect(frames * 2 * 4).order(ByteOrder.LITTLE_ENDIAN)
        repeat(frames * 2) { buffer.putFloat(0.25f) }
        buffer.flip()
        processor.queueInput(buffer)
        val output = readFloats(processor.output)
        // Etwa doppelt so viele Frames (minus Filterlatenz am Blockanfang).
        assertTrue("Zu wenig Ausgabeframes: ${output.size / 2}", output.size / 2 > 800)
    }

    @Test
    fun `tpdf dither variiert die quantisierung eines leisen signals`() {
        val processor = processor(DspConfig(ditherMode = DitherMode.TPDF))
        processor.configure(AudioProcessor.AudioFormat(48_000, 1, C.ENCODING_PCM_16BIT))
        processor.flush()

        // Konstantes Signal exakt zwischen zwei Quantisierungsstufen.
        val frames = 2_000
        val buffer = ByteBuffer.allocateDirect(frames * 2).order(ByteOrder.LITTLE_ENDIAN)
        repeat(frames) { buffer.putShort(100) }
        buffer.flip()
        // Preamp +0,01 dB verschiebt die Werte minimal von der Stufe weg.
        processor.submitConfig(DspConfig(preampDb = 0.05, ditherMode = DitherMode.TPDF, limiterEnabled = false))
        processor.queueInput(buffer)
        val output = readShorts(processor.output)
        val distinct = output.toSet()
        assertTrue("Dither muss mehrere Stufen erzeugen, war $distinct", distinct.size > 1)
    }
}
