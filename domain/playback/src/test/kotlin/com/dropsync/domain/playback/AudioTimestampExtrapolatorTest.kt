package com.dropsync.domain.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeTimestampReader(
    private var valid: Boolean = true,
    private var timestamp: AudioTimestamp? = AudioTimestamp(systemTimeNs = 0, framePosition = 0),
    private val headPosition: Long = 0,
) : AudioTimestampReader {
    fun setValid(v: Boolean) {
        valid = v
    }

    fun setTimestamp(t: AudioTimestamp?) {
        timestamp = t
    }

    override fun isTimestampValid(): Boolean = valid

    override fun readTimestamp(): AudioTimestamp? = timestamp

    override fun playbackHeadPosition(): Long = headPosition
}

/**
 * Tests fuer [AudioTimestampExtrapolator] (Testinfrastruktur-Umbauplan 5c):
 * Extrapolation nur ueber Deltas zweier synthetischer
 * `(systemTimeNs, framePosition)`-Paare, nie gegen eine absolute Latenz.
 */
class AudioTimestampExtrapolatorTest {
    private val sampleRate = 48_000

    @Test
    fun `extrapoliert aus validem timestamp nach vorne`() {
        val reader =
            FakeTimestampReader(
                timestamp = AudioTimestamp(systemTimeNs = 1_000_000_000, framePosition = 0),
            )
        var now = 1_000_000_000L
        val extrapolator = AudioTimestampExtrapolator(reader, sampleRate, nowNs = { now })

        // Delta: 1 Sekunde nach dem Timestamp -> 48_000 Frames weiter.
        now = 2_000_000_000L
        assertEquals(48_000L, extrapolator.audibleFramePosition())

        // Delta: weitere 0.5 s -> 72_000 Frames.
        now = 2_500_000_000L
        assertEquals(72_000L, extrapolator.audibleFramePosition())
    }

    @Test
    fun `warmup phase faellt auf playhead zurueck`() {
        val reader = FakeTimestampReader(valid = false, headPosition = 123)
        val extrapolator = AudioTimestampExtrapolator(reader, sampleRate, nowNs = { 1_000_000_000L })
        assertEquals(123L, extrapolator.audibleFramePosition())
    }

    @Test
    fun `null timestamp faellt auf playhead zurueck`() {
        val reader = FakeTimestampReader(valid = true, timestamp = null, headPosition = 77)
        val extrapolator = AudioTimestampExtrapolator(reader, sampleRate, nowNs = { 1_000_000_000L })
        assertEquals(77L, extrapolator.audibleFramePosition())
    }

    @Test
    fun `rueckwaerts-uhr extrapoliert nicht`() {
        val reader =
            FakeTimestampReader(
                timestamp = AudioTimestamp(systemTimeNs = 2_000_000_000, framePosition = 100),
                headPosition = 100,
            )
        // nowNs liegt VOR dem Timestamp: kein extrapolieren, Fallback.
        val extrapolator = AudioTimestampExtrapolator(reader, sampleRate, nowNs = { 1_000_000_000L })
        assertEquals(100L, extrapolator.audibleFramePosition())
    }

    @Test
    fun `bufferanteil wird abgezogen und nie negativ`() {
        // Latenz-Zusammensetzung: AudioTrack-Buffer muss rausgerechnet werden.
        val reader =
            FakeTimestampReader(
                timestamp = AudioTimestamp(systemTimeNs = 1_000_000_000, framePosition = 100),
            )
        var now = 1_000_000_000L
        val extrapolator =
            AudioTimestampExtrapolator(
                reader,
                sampleRate,
                nowNs = { now },
                bufferSizeFrames = 100,
            )

        // Ohne vergangene Zeit: framePosition - buffer = 0 (nicht negativ).
        assertEquals(0L, extrapolator.audibleFramePosition())

        // Mit 1 s vergangener Zeit: 100 + 48_000 - 100.
        now = 2_000_000_000L
        assertEquals(48_000L, extrapolator.audibleFramePosition())
    }

    @Test
    fun `ungueltige sample rate wird abgelehnt`() {
        val reader = FakeTimestampReader()
        try {
            AudioTimestampExtrapolator(reader, 0, nowNs = { 0L })
            throw AssertionError("require hätte werfen muessen")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("sampleRateHz"))
        }
    }
}
