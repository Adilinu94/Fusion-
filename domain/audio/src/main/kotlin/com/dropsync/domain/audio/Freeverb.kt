package com.dropsync.domain.audio

/**
 * Freeverb-Nachhall (Public-Domain-Algorithmus von Jezar at Dreampoint):
 * 8 Kammfilter mit Daempfung plus 4 Allpaesse je Kanal. Die klassischen
 * Verzoegerungen sind fuer 44,1 kHz abgestimmt und werden auf die
 * tatsaechliche Abtastrate skaliert (Plan Phase 2).
 */
class Freeverb(
    sampleRateHz: Int,
    channelCount: Int,
) {
    /** Parameter; Werte 0..1, [wet] 0 = trocken. */
    data class Settings(
        val roomSize: Double = 0.5,
        val damping: Double = 0.5,
        val wet: Double = 0.33,
    ) {
        companion object {
            fun sanitized(settings: Settings): Settings =
                Settings(
                    roomSize = settings.roomSize.coerceIn(0.0, 1.0),
                    damping = settings.damping.coerceIn(0.0, 1.0),
                    wet = settings.wet.coerceIn(0.0, 1.0),
                )
        }
    }

    private class Comb(
        size: Int,
    ) {
        val buffer = DoubleArray(size)
        var index = 0
        var filterStore = 0.0

        fun process(
            input: Double,
            feedback: Double,
            damp: Double,
        ): Double {
            val output = buffer[index]
            filterStore = output * (1.0 - damp) + filterStore * damp
            buffer[index] = input + filterStore * feedback
            index = (index + 1) % buffer.size
            return output
        }
    }

    private class Allpass(
        size: Int,
    ) {
        val buffer = DoubleArray(size)
        var index = 0

        fun process(input: Double): Double {
            val buffered = buffer[index]
            val output = -input + buffered
            buffer[index] = input + buffered * FEEDBACK
            index = (index + 1) % buffer.size
            return output
        }

        companion object {
            const val FEEDBACK = 0.5
        }
    }

    private val channels = channelCount.coerceIn(1, 2)

    private val combs: Array<Array<Comb>>
    private val allpasses: Array<Array<Allpass>>

    @Volatile
    private var settings = Settings()

    init {
        val scale = sampleRateHz / 44_100.0

        fun scaled(
            base: Int,
            channel: Int,
        ): Int = ((base + channel * STEREO_SPREAD) * scale).toInt().coerceAtLeast(1)
        combs =
            Array(channels) { channel ->
                COMB_TUNINGS.map { Comb(scaled(it, channel)) }.toTypedArray()
            }
        allpasses =
            Array(channels) { channel ->
                ALLPASS_TUNINGS.map { Allpass(scaled(it, channel)) }.toTypedArray()
            }
    }

    fun updateSettings(newSettings: Settings) {
        settings = Settings.sanitized(newSettings)
    }

    /** Verarbeitet interleaved Samples in-place. */
    fun process(
        samples: DoubleArray,
        count: Int,
        channelCount: Int,
    ) {
        val active = settings
        if (active.wet <= 0.0) return
        val feedback = 0.7 + active.roomSize * 0.28
        val damp = active.damping * 0.4
        val wet = active.wet
        val dry = 1.0 - wet
        val frameChannels = channelCount.coerceAtLeast(1)
        var i = 0
        while (i < count) {
            for (channel in 0 until frameChannels) {
                if (i + channel >= count) break
                val lane = if (channel < channels) channel else channels - 1
                val input = samples[i + channel]
                var reverbOut = 0.0
                for (comb in combs[lane]) {
                    reverbOut += comb.process(input * FIXED_GAIN, feedback, damp)
                }
                for (allpass in allpasses[lane]) {
                    reverbOut = allpass.process(reverbOut)
                }
                samples[i + channel] = input * dry + reverbOut * wet
            }
            i += frameChannels
        }
    }

    companion object {
        private const val FIXED_GAIN = 0.015
        private const val STEREO_SPREAD = 23
        private val COMB_TUNINGS = intArrayOf(1116, 1188, 1277, 1356, 1422, 1491, 1557, 1617)
        private val ALLPASS_TUNINGS = intArrayOf(556, 441, 341, 225)
    }
}
