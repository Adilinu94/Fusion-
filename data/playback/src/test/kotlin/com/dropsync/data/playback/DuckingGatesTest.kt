package com.dropsync.data.playback

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Cue- und Rest-Ducking-Gates ueber den schmalen [DuckingTarget]-Port:
 * Gain-Roundtrip, Rest-Ducking-Zielwert aus der Config und 0 dB im
 * inaktiven Zustand.
 */
class DuckingGatesTest {
    @Test
    fun `currentVolume liest den Ducking-Gain`() =
        runTest {
            val target = FakeDuckingTarget()
            target.duckingGain.value = 0.5
            assertEquals(0.5f, PlayerVolumeGateImpl(target).currentVolume(), 0f)
        }

    @Test
    fun `setVolume schreibt den Ducking-Gain`() =
        runTest {
            val target = FakeDuckingTarget()
            PlayerVolumeGateImpl(target).setVolume(0.25f)
            assertEquals(listOf(0.25), target.duckingGains)
        }

    @Test
    fun `Rest-Ducking aktiv nutzt den Config-Wert`() =
        runTest {
            val target = FakeDuckingTarget()
            target.configuredRestDuckDb = -9.0
            RestDuckingGateImpl(target).setActive(true)
            assertEquals(listOf(-9.0), target.restDuckDbs)
        }

    @Test
    fun `Rest-Ducking inaktiv setzt 0 dB`() =
        runTest {
            val target = FakeDuckingTarget()
            target.configuredRestDuckDb = -9.0
            RestDuckingGateImpl(target).setActive(false)
            assertEquals(listOf(0.0), target.restDuckDbs)
        }

    private class FakeDuckingTarget : DuckingTarget {
        override val duckingGain = MutableStateFlow(1.0)
        var configuredRestDuckDb = -6.0
        val duckingGains = mutableListOf<Double>()
        val restDuckDbs = mutableListOf<Double>()

        override fun setDuckingGain(gain: Double) {
            duckingGains += gain
            duckingGain.value = gain
        }

        override fun restDuckDb(): Double = configuredRestDuckDb

        override fun setRestDuckDb(db: Double) {
            restDuckDbs += db
        }
    }
}
