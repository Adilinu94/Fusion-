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
import com.dropsync.domain.audio.ReverbSettings
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
            processor(DspConfig(enabled = true, preampDb = 6.0206, limiterEnabled = false))
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
                DspConfig(enabled = true, dvcEnabled = true, dvcVolume = 0.5, limiterEnabled = false),
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
                DspConfig(enabled = true, dvcEnabled = true, dvcVolume = 0.5, limiterEnabled = false),
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
                enabled = true,
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
        processor.submitConfig(
            DspConfig(enabled = true, preampDb = 0.05, ditherMode = DitherMode.TPDF, limiterEnabled = false),
        )
        processor.queueInput(buffer)
        val output = readShorts(processor.output)
        val distinct = output.toSet()
        assertTrue("Dither muss mehrere Stufen erzeugen, war $distinct", distinct.size > 1)
    }

    /**
     * B-AUD-1: `queueInput` laeuft auf dem Audiothread des
     * `DefaultAudioSink`. Jede Allokation dort riskiert einen GC-Stall, also
     * einen hoerbaren Underrun. Die drei Faelle unten pruefen genau die
     * Ausloeser, die vorher `rebuildStages()` gerufen haben: Preset-Wechsel
     * mit anderer Bandanzahl, Dither-Umschaltung und Seek/Titelwechsel.
     *
     * Der Nachweis laeuft ueber Referenzidentitaet der Stufenobjekte, nicht
     * ueber Speichermessung: bleibt dieselbe Instanz aktiv, hat niemand
     * allokiert.
     */
    @Test
    fun `preset wechsel mit anderer bandanzahl allokiert keine filter`() {
        val processor = processor(DspConfig(eq = EqSettings(enabled = true, bands = EqSettings.graphicBands(10))))
        processor.configure(AudioProcessor.AudioFormat(48_000, 2, C.ENCODING_PCM_FLOAT))
        processor.flush()
        processor.queueInput(floatBuffer(0.1f, 0.1f))
        val before = processor.stageIdentitiesForTest()

        // 10 -> 31 Baender: vorher der Hauptausloeser fuer rebuildStages().
        processor.submitConfig(
            DspConfig(eq = EqSettings(enabled = true, bands = EqSettings.graphicBands(31))),
        )
        processor.queueInput(floatBuffer(0.1f, 0.1f))

        assertEquals(
            "Bandwechsel darf keine Stufe neu allokieren",
            before,
            processor.stageIdentitiesForTest(),
        )
        // Die neue Bandanzahl muss trotzdem wirksam sein.
        assertEquals(31, processor.activeBandCountForTest())
    }

    @Test
    fun `dither umschaltung allokiert keinen neuen generator`() {
        val processor = processor(DspConfig(ditherMode = DitherMode.TPDF))
        processor.configure(AudioProcessor.AudioFormat(48_000, 1, C.ENCODING_PCM_16BIT))
        processor.flush()
        processor.queueInput(pcm16Buffer(100, 100))
        val before = processor.stageIdentitiesForTest()

        processor.submitConfig(DspConfig(ditherMode = DitherMode.SHAPED))
        processor.queueInput(pcm16Buffer(100, 100))

        assertEquals(
            "Dither-Wechsel darf keinen neuen Generator allokieren",
            before,
            processor.stageIdentitiesForTest(),
        )
        assertEquals(DitherMode.SHAPED, processor.ditherModeForTest())
    }

    @Test
    fun `flush setzt zustand zurueck ohne neu zu allokieren`() {
        val config =
            DspConfig(
                enabled = true,
                reverb = ReverbSettings(enabled = true, wet = 0.5),
                resampler = ResamplerSettings(targetRateHz = 96_000, quality = ResamplerQuality.SINC),
            )
        val processor = processor(config)
        processor.configure(AudioProcessor.AudioFormat(48_000, 2, C.ENCODING_PCM_FLOAT))
        processor.flush()
        // Mehr Frames als der laengste Freeverb-Kammfilter (1617 Taps auf
        // 44,1 kHz, skaliert also ~1760 bei 48 kHz). Mit einem kurzen Block
        // laeuft der Leseindex nie ueber die geschriebenen Stellen und der
        // Test wuerde einen fehlenden Reset nicht bemerken.
        val frames = 4_000
        val loud = ByteBuffer.allocateDirect(frames * 2 * 4).order(ByteOrder.LITTLE_ENDIAN)
        repeat(frames * 2) { loud.putFloat(0.5f) }
        loud.flip()
        processor.queueInput(loud)
        val before = processor.stageIdentitiesForTest()

        // Seek/Titelwechsel: vorher rebuildStages() bei JEDEM Aufruf.
        processor.flush()

        assertEquals(
            "flush darf keine Stufe neu allokieren",
            before,
            processor.stageIdentitiesForTest(),
        )

        // Und der Nachhall des alten Materials darf nicht nachklingen:
        // Stille rein, Stille raus.
        val silence = ByteBuffer.allocateDirect(frames * 2 * 4).order(ByteOrder.LITTLE_ENDIAN)
        repeat(frames * 2) { silence.putFloat(0.0f) }
        silence.flip()
        processor.queueInput(silence)
        val tail = readFloats(processor.output)
        val peak = tail.maxOfOrNull { abs(it) } ?: 0f
        assertTrue("Nach flush darf kein Reverb-Rest klingen, Spitze war $peak", peak < 1e-6f)
    }

    /**
     * 2026-09-27, Befund 4.4: ReplayGain war ungeklemmt.
     *
     * Ausgangslage: `db = -18 - lufs`, die Analyse setzt ihr absolutes Gate
     * bei −70 LUFS. Also `db = +52 dB` und `gain = 10^(52/20) ≈ 398`. Der
     * Soft-Limiter muss das abfangen, was den **ganzen** Track in die
     * Saettigung faehrt. Der Test fahren den Extremwert nach und prueft,
     * dass die Kette den Track nicht zerstoert.
     */
    @Test
    fun `replaygain wird auf plus 12 db begrenzt`() {
        val processor = processor(DspConfig(replayGainEnabled = true))
        processor.configure(
            AudioProcessor.AudioFormat(48_000, 2, C.ENCODING_PCM_FLOAT),
        )
        // Der Wert, den ein Track am Gate-Rand liefert.
        processor.setReplayGainDb(52.0)

        val input = ByteBuffer.allocateDirect(2 * 4 * 64).order(ByteOrder.LITTLE_ENDIAN)
        repeat(2 * 64) { input.putFloat(0.5f) }
        input.flip()
        processor.queueInput(input)

        val out = readFloats(processor.output)
        assertTrue("Ausgabe muss endlich sein", out.all { it.isFinite() })
        val peak = out.maxOfOrNull { abs(it) } ?: 0f
        // 0,5 * 10^(12/20) = 0,996. Ohne Begrenzung waere es 0,5 * 398 = 199.
        assertTrue("Gain wurde nicht begrenzt, Spitze war $peak", peak <= 1.0f)
    }

    /**
     * True-Peak-Reserve (Befund 4.4): der Gain wird so begrenzt, dass
     * `gain * truePeak <= -1 dBFS`. Ohne das hebt die Normalisierung einen
     * bereits lauten Track ueber 0 dBFS.
     */
    @Test
    fun `true peak begrenzt den replaygain auf das deckel`() {
        val processor = processor(DspConfig(replayGainEnabled = true))
        processor.configure(
            AudioProcessor.AudioFormat(48_000, 2, C.ENCODING_PCM_FLOAT),
        )
        processor.setReplayGainDb(12.0)
        // Track, dessen Spitze 0,5 bereits 0 dBFS entspricht: nach +12 dB
        // waeren es 0 dBFS * 4 = +12 dBFS. Die Reserve muss auf −1 dBFS
        // zuruecknehmen, also auf den Faktor 0,891/0,5 ≈ 1,78 (+5 dB).
        processor.setTruePeak(0.5)

        val input = ByteBuffer.allocateDirect(2 * 4 * 64).order(ByteOrder.LITTLE_ENDIAN)
        repeat(2 * 64) { input.putFloat(0.5f) }
        input.flip()
        processor.queueInput(input)

        val out = readFloats(processor.output)
        val peak = out.maxOfOrNull { abs(it) } ?: 0f
        val ceiling = Math.pow(10.0, (-1.0 / 20.0)).toFloat()
        assertTrue(
            "Spitze $peak muss unter dem Deckel $ceiling bleiben",
            peak <= ceiling + 0.01f,
        )
    }

    /**
     * 2026-09-27, Befund 4.3/4.4: ein `NaN` im Gain-Cache darf nicht das
     * gesamte Sample-Array vergiften. `Double.pow(NaN)` ist `NaN`.
     */
    @Test
    fun `replaygain mit NaN im cache bleibt still statt endlos NaN`() {
        val processor = processor(DspConfig(replayGainEnabled = true))
        processor.configure(
            AudioProcessor.AudioFormat(48_000, 2, C.ENCODING_PCM_FLOAT),
        )
        processor.setReplayGainDb(Double.NaN)

        val input = ByteBuffer.allocateDirect(2 * 4 * 32).order(ByteOrder.LITTLE_ENDIAN)
        repeat(2 * 32) { input.putFloat(0.5f) }
        input.flip()
        processor.queueInput(input)

        val out = readFloats(processor.output)
        assertTrue("NaN im Gain darf nicht durchschlagen", out.all { it.isFinite() })
    }

    /**
     * 2026-09-27, Befund 4.3: ein `NaN` im dekodierten PCM darf die Kette
     * nicht fuer den Rest des Blocks und des Titels zerstoeren. Ueber
     * Float-Eingang reproduzierbar, weil `PcmCodec.decode` fuer Float
     * keinen Clamp anwendet.
     */
    @Test
    fun `defekter float sample wird ersetzt statt ueber die kette zu wandern`() {
        val processor =
            processor(
                DspConfig(
                    replayGainEnabled = true,
                    eq =
                        EqSettings(
                            enabled = true,
                            mode = EqMode.PARAMETRIC,
                            bands = listOf(EqBand(1_000.0, 6.0, 1.0)),
                        ),
                ),
            )
        processor.configure(
            AudioProcessor.AudioFormat(48_000, 2, C.ENCODING_PCM_FLOAT),
        )
        processor.setReplayGainDb(3.0)

        val input = ByteBuffer.allocateDirect(2 * 4 * 32).order(ByteOrder.LITTLE_ENDIAN)
        repeat(2 * 32) { index -> input.putFloat(if (index == 5) Float.NaN else 0.4f) }
        input.flip()
        processor.queueInput(input)

        val out = readFloats(processor.output)
        assertEquals(2 * 32, out.size)
        assertTrue("NaN wanderte durch die Kette", out.all { it.isFinite() })
        // Und der Ersatz wurde auch gezaehlt, damit der Datenverlust
        // sichtbar bleibt statt still zu passieren.
        assertEquals(1L, processor.nonFiniteSamplesForTest())
    }
}
