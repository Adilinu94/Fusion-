package com.dropsync.data.sensor

import com.dropsync.domain.sensor.SensorSample
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * RC-10: Der zentrale Sample-Fan-out verteilt an ALLE Verbraucher
 * (kein Work-Stealing wie bei einem Channel), verdrangt bei Ueberlast das
 * aelteste Sample (DROP_OLDEST) und zaehlt jede Verdrangung — statt still
 * zu verwerfen.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SensorSampleFanoutTest {
    private fun sample(i: Int) =
        SensorSample(
            timestampMs = i * 20L,
            ax = 0.0,
            ay = 0.0,
            az = 9.8,
            gx = i.toDouble(),
            gy = 0.0,
            gz = 0.0,
        )

    @Test
    fun `zwei verbraucher sehen dieselbe sample-folge`() =
        runTest {
            val fanout = SensorSampleFanout(scope = backgroundScope, capacity = 8)
            val first = mutableListOf<SensorSample>()
            val second = mutableListOf<SensorSample>()
            backgroundScope.launch { fanout.samples.collect { first += it } }
            backgroundScope.launch { fanout.samples.collect { second += it } }
            runCurrent()
            fanout.emit(sample(1))
            fanout.emit(sample(2))
            runCurrent()
            assertEquals(listOf(20L, 40L), first.map { it.timestampMs })
            assertEquals(listOf(20L, 40L), second.map { it.timestampMs })
            assertEquals(0L, fanout.droppedSamples)
        }

    @Test
    fun `ohne abonnenten wird nichts gepuffert und nichts gezaehlt`() =
        runTest {
            val fanout = SensorSampleFanout(scope = backgroundScope, capacity = 2)
            repeat(10) { fanout.emit(sample(it)) }
            runCurrent()
            assertEquals(0L, fanout.droppedSamples)
        }

    @Test
    fun `voller puffer verdrangt das aelteste sample und zaehlt es`() =
        runTest {
            val fanout = SensorSampleFanout(scope = backgroundScope, capacity = 2)
            val received = mutableListOf<SensorSample>()
            backgroundScope.launch {
                fanout.samples.collect {
                    received += it
                    // Langsamer Verbraucher: erzwingt Rueckstau im Fan-out.
                    delay(1_000)
                }
            }
            runCurrent()
            // Erstes Sample geht durch, der Verbraucher haengt danach 1 s.
            fanout.emit(sample(1))
            runCurrent()
            // Waehrend der Verbraucher haengt: der Ringpuffer (2) fuellt
            // sich, das dritte Sample verdrangt das aelteste (s2).
            (2..4).forEach { fanout.emit(sample(it)) }
            runCurrent()
            assertEquals(1L, fanout.droppedSamples)
            // Nach dem Aufholen fehlt genau das verdrangte Sample (s2);
            // die neueren Samples (s3, s4) kommen an.
            advanceTimeBy(1_000)
            runCurrent()
            advanceTimeBy(1_000)
            runCurrent()
            val timestamps = received.map { it.timestampMs }
            assertTrue(timestamps.contains(20L))
            assertTrue(timestamps.none { it == 40L })
            assertTrue(timestamps.contains(60L))
            assertTrue(timestamps.contains(80L))
        }

    @Test
    fun `reset leert puffer und zaehler`() =
        runTest {
            val fanout = SensorSampleFanout(scope = backgroundScope, capacity = 1)
            val received = mutableListOf<SensorSample>()
            backgroundScope.launch {
                fanout.samples.collect {
                    received += it
                    delay(1_000)
                }
            }
            runCurrent()
            fanout.emit(sample(1))
            runCurrent()
            (2..5).forEach { fanout.emit(sample(it)) }
            runCurrent()
            assertTrue(fanout.droppedSamples > 0)
            fanout.reset()
            assertEquals(0L, fanout.droppedSamples)
        }
}
