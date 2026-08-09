package com.dropsync.domain.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class AudioMathTest {
    @Test
    fun `db und linear sind zueinander invers`() {
        assertEquals(1.0, AudioMath.dbToLinear(0.0), 1e-12)
        assertEquals(2.0, AudioMath.dbToLinear(6.0206), 1e-4)
        assertEquals(0.5, AudioMath.dbToLinear(-6.0206), 1e-4)
        assertEquals(-6.0206, AudioMath.linearToDb(0.5), 1e-4)
        // 0 und negative Pegel fallen definiert auf MIN_DB.
        assertEquals(AudioMath.MIN_DB, AudioMath.linearToDb(0.0), 0.0)
        assertEquals(AudioMath.MIN_DB, AudioMath.linearToDb(-1.0), 0.0)
    }

    @Test
    fun `softclip ist unter der schwelle transparent`() {
        assertEquals(0.5, AudioMath.softClip(0.5), 0.0)
        assertEquals(-0.89, AudioMath.softClip(-0.89), 0.0)
    }

    @Test
    fun `softclip ist monoton und bleibt unter 1`() {
        var previous = 0.0
        var level = 0.0
        while (level <= 4.0) {
            val clipped = AudioMath.softClip(level)
            assertTrue("nicht monoton bei $level", clipped >= previous)
            assertTrue("ueber 1.0 bei $level", clipped <= 1.0)
            previous = clipped
            level += 0.01
        }
        // Symmetrie.
        assertEquals(-AudioMath.softClip(1.7), AudioMath.softClip(-1.7), 1e-12)
    }
}

class PcmCodecTest {
    private fun buffer(bytes: Int): ByteBuffer = ByteBuffer.allocate(bytes).order(ByteOrder.LITTLE_ENDIAN)

    @Test
    fun `pcm16 roundtrip ist verlustfrei innerhalb eines lsb`() {
        val input = buffer(8)
        input.putShort(0)
        input.putShort(16384) // 0.5
        input.putShort(-32768) // -1.0
        input.putShort(32767) // ~1.0
        input.flip()

        val samples = DoubleArray(4)
        assertEquals(4, PcmCodec.decode(input, PcmEncoding.PCM_16, samples))
        assertEquals(0.0, samples[0], 0.0)
        assertEquals(0.5, samples[1], 1e-9)
        assertEquals(-1.0, samples[2], 0.0)

        val out = buffer(8)
        PcmCodec.encodePcm16(samples, 4, out)
        out.flip()
        assertEquals(0, out.short.toInt())
        assertEquals(16384, out.short.toInt())
        assertEquals(-32768, out.short.toInt())
        assertEquals(32767, out.short.toInt())
    }

    @Test
    fun `pcm24 dekodiert vorzeichen korrekt`() {
        val input = buffer(6)
        // +8388607 (Maximalwert) als 3 Bytes Little-Endian.
        input.put(0xFF.toByte()).put(0xFF.toByte()).put(0x7F.toByte())
        // -8388608 (Minimalwert).
        input.put(0x00.toByte()).put(0x00.toByte()).put(0x80.toByte())
        input.flip()

        val samples = DoubleArray(2)
        assertEquals(2, PcmCodec.decode(input, PcmEncoding.PCM_24, samples))
        assertEquals(8388607.0 / 8388608.0, samples[0], 1e-12)
        assertEquals(-1.0, samples[1], 0.0)
    }

    @Test
    fun `pcm32 und float dekodieren normiert`() {
        val input32 = buffer(8)
        input32.putInt(Int.MIN_VALUE)
        input32.putInt(Int.MAX_VALUE / 2)
        input32.flip()
        val samples32 = DoubleArray(2)
        PcmCodec.decode(input32, PcmEncoding.PCM_32, samples32)
        assertEquals(-1.0, samples32[0], 0.0)
        assertEquals(0.5, samples32[1], 1e-6)

        val inputF = buffer(8)
        inputF.putFloat(0.25f)
        inputF.putFloat(-0.75f)
        inputF.flip()
        val samplesF = DoubleArray(2)
        PcmCodec.decode(inputF, PcmEncoding.FLOAT, samplesF)
        assertEquals(0.25, samplesF[0], 1e-9)
        assertEquals(-0.75, samplesF[1], 1e-9)
    }

    @Test
    fun `encode float clampt uebersteuerte samples`() {
        val out = buffer(8)
        PcmCodec.encodeFloat(doubleArrayOf(2.0, -3.0), 2, out)
        out.flip()
        assertEquals(1.0f, out.float, 0.0f)
        assertEquals(-1.0f, out.float, 0.0f)
    }

    @Test
    fun `unvollstaendige samples am pufferende werden ignoriert`() {
        val input = buffer(5) // 2 volle 16-Bit-Samples + 1 Restbyte.
        input.putShort(100)
        input.putShort(200)
        input.put(1)
        input.flip()
        val samples = DoubleArray(8)
        assertEquals(2, PcmCodec.decode(input, PcmEncoding.PCM_16, samples))
    }
}

class DspConfigTest {
    @Test
    fun `sanitized begrenzt preamp auf plus minus 12 db`() {
        assertEquals(12.0, DspConfig.sanitized(DspConfig(preampDb = 40.0)).preampDb, 0.0)
        assertEquals(-12.0, DspConfig.sanitized(DspConfig(preampDb = -40.0)).preampDb, 0.0)
        assertEquals(3.5, DspConfig.sanitized(DspConfig(preampDb = 3.5)).preampDb, 0.0)
    }
}
