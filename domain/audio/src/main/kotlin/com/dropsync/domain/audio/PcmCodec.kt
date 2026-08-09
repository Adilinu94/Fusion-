package com.dropsync.domain.audio

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

/** Lineare PCM-Kodierungen, die die DSP-Kette akzeptiert (ADR-0005). */
enum class PcmEncoding(
    val bytesPerSample: Int,
) {
    PCM_16(2),
    PCM_24(3),
    PCM_32(4),
    FLOAT(4),
}

/**
 * Konvertiert lineares PCM zwischen Bytepuffern und 64-Bit-Doubles
 * (ADR-0005). Alles Little-Endian, interleaved; Samples normiert auf
 * [-1.0, 1.0]. Reine JVM-Logik, deterministisch testbar.
 */
object PcmCodec {
    private const val SCALE_16 = 32768.0
    private const val SCALE_24 = 8388608.0
    private const val SCALE_32 = 2147483648.0

    /** Anzahl vollstaendiger Samples im lesbaren Bereich von [input]. */
    fun sampleCount(
        input: ByteBuffer,
        encoding: PcmEncoding,
    ): Int = input.remaining() / encoding.bytesPerSample

    /**
     * Liest alle vollstaendigen Samples aus [input] (Position wird
     * konsumiert) und schreibt sie normiert nach [target].
     */
    fun decode(
        input: ByteBuffer,
        encoding: PcmEncoding,
        target: DoubleArray,
    ): Int {
        val buffer = input.order(ByteOrder.LITTLE_ENDIAN)
        val count = minOf(sampleCount(buffer, encoding), target.size)
        when (encoding) {
            PcmEncoding.PCM_16 -> {
                for (i in 0 until count) {
                    target[i] = buffer.short.toDouble() / SCALE_16
                }
            }

            PcmEncoding.PCM_24 -> {
                for (i in 0 until count) {
                    val b0 = buffer.get().toInt() and 0xFF
                    val b1 = buffer.get().toInt() and 0xFF
                    val b2 = buffer.get().toInt() // Vorzeichenbyte.
                    val value = (b2 shl 16) or (b1 shl 8) or b0
                    target[i] = value.toDouble() / SCALE_24
                }
            }

            PcmEncoding.PCM_32 -> {
                for (i in 0 until count) {
                    target[i] = buffer.int.toDouble() / SCALE_32
                }
            }

            PcmEncoding.FLOAT -> {
                for (i in 0 until count) {
                    target[i] = buffer.float.toDouble()
                }
            }
        }
        return count
    }

    /** Schreibt [count] Samples als 32-Bit-Float-PCM nach [output]. */
    fun encodeFloat(
        samples: DoubleArray,
        count: Int,
        output: ByteBuffer,
    ) {
        val buffer = output.order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until count) {
            buffer.putFloat(AudioMath.clampSample(samples[i]).toFloat())
        }
    }

    /**
     * Schreibt [count] Samples als 16-Bit-PCM nach [output]; [ditherFn]
     * liefert das Ditherrauschen in LSB-Einheiten (0.0 = kein Dither).
     */
    fun encodePcm16(
        samples: DoubleArray,
        count: Int,
        output: ByteBuffer,
        ditherFn: () -> Double = { 0.0 },
    ) {
        val buffer = output.order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until count) {
            // Symmetrische Skalierung wie beim Dekodieren (1/32768);
            // +1.0 wird auf den Maximalwert 32767 begrenzt.
            val scaled = AudioMath.clampSample(samples[i]) * SCALE_16 + ditherFn()
            buffer.putShort(scaled.roundToInt().coerceIn(-32768, 32767).toShort())
        }
    }
}
