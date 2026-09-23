package com.dropsync.data.audio

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dropsync.domain.audio.AudioMath
import com.dropsync.domain.audio.OutputDeviceKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Analyse-Befund 4.9: die Pipeline-Vertraege ohne echten Audiothread.
 * Kernpunkte sind die Ducking-Verwaltung (Mutex-Serialisierung der
 * Rest-Duck-Rampen aus B-AUD-3, Clamp der Cue-Gains) und die
 * Format-/Info-Weitergabe. Der Rampen-Endwert wird am MasterDspProcessor
 * ueber `queueInput` beobachtet (gain * Amplitude), derselbe Nachweisweg
 * wie im `MasterDspProcessorTest`. Das Settings-Collect im `init` laeuft
 * auf dem Test-Dispatcher (Main-Ersatz); die echten Store-/Monitor-
 * Implementierungen stehen unter Robolectric bereit.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AudioPipelineTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun pipeline(): AudioPipeline {
        val context = ApplicationProvider.getApplicationContext<Context>()
        return AudioPipeline(
            settingsStore = DspSettingsStore(context),
            deviceMonitor = OutputDeviceMonitor(context),
            rampDispatcher = dispatcher,
        )
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

    private fun readFloatBuffer(buffer: ByteBuffer): FloatArray {
        val ordered = buffer.order(ByteOrder.LITTLE_ENDIAN)
        val result = FloatArray(ordered.remaining() / 4)
        for (i in result.indices) {
            result[i] = ordered.float
        }
        return result
    }

    @Test
    fun `ducking gain wird auf 0 bis 1 begrenzt`() {
        val pipeline = pipeline()
        pipeline.setDuckingGain(1.7)
        assertEquals(1.0, pipeline.duckingGain.value, 1e-9)
        pipeline.setDuckingGain(-0.5)
        assertEquals(0.0, pipeline.duckingGain.value, 1e-9)
        pipeline.setDuckingGain(0.35)
        assertEquals(0.35, pipeline.duckingGain.value, 1e-9)
    }

    @Test
    fun `audio processors sind genau die eigene kette`() {
        val pipeline = pipeline()
        val processors = pipeline.audioProcessors()
        assertEquals(1, processors.size)
        assertTrue(processors.single() is MasterDspProcessor)
    }

    @Test
    fun `replay gain schreibpfad akzeptiert wert und null ohne ausnahme`() {
        val pipeline = pipeline()
        pipeline.setReplayGainDb(-2.5)
        pipeline.setReplayGainDb(null)
    }

    @Test
    fun `source und output format erscheinen in der audio info`() = runTest(dispatcher) {
        val pipeline = pipeline()
        pipeline.onSourceFormatChanged(
            SourceFormatInfo(
                codecMimeType = "audio/flac",
                bitrateBps = 983_040,
                sampleRateHz = 96_000,
                channelCount = 2,
                bitDepth = 24,
            ),
        )
        pipeline.onAudioTrackInitialized(
            OutputFormatInfo(
                sampleRateHz = 192_000,
                encodingName = "PCM_FLOAT",
                isFloat = true,
            ),
        )
        val info =
            withTimeout(5_000) {
                pipeline.audioInfo.first { it != null }
            }
        assertNotNull(info)
        assertEquals("audio/flac", info!!.codecMimeType)
        assertEquals(96_000, info.sourceSampleRateHz)
        assertEquals(192_000, info.outputSampleRateHz)
        assertTrue(info.floatOutput)
        assertEquals(OutputDeviceKind.SPEAKER, info.outputDevice)
    }

    @Test
    fun `ohne source format bleibt die audio info null`() = runTest(dispatcher) {
        val pipeline = pipeline()
        pipeline.onAudioTrackInitialized(
            OutputFormatInfo(sampleRateHz = 48_000, encodingName = "PCM_16", isFloat = false),
        )
        assertEquals(null, pipeline.audioInfo.first())
    }

    @Test
    fun `playback released setzt die formats zurueck`() = runTest(dispatcher) {
        val pipeline = pipeline()
        pipeline.onSourceFormatChanged(
            SourceFormatInfo("audio/mpeg", 320_000, 44_100, 2, 16),
        )
        pipeline.onAudioTrackInitialized(
            OutputFormatInfo(44_100, "PCM_16", isFloat = false),
        )
        pipeline.onPlaybackReleased()
        assertEquals(null, pipeline.audioInfo.first())
    }

    @Test
    fun `konkurrierende rest duck rampen enden beim letzten zielwert`() = runTest(dispatcher) {
        val pipeline = pipeline()
        val processor = pipeline.audioProcessors().single() as MasterDspProcessor
        val format = AudioProcessor.AudioFormat(48_000, 1, C.ENCODING_PCM_FLOAT)
        processor.configure(format)
        processor.flush()

        // Zwei Rampen dicht hintereinander auf verschiedenen Coroutines:
        // die zweite (Mutex + cancelAndJoin) wartet, bis die erste
        // wirklich beendet ist. Der Endzustand ist der Zielwert des
        // zweiten Aufrufs (-3 dB -> linear), nicht ein Scheduling-Zufall.
        val rampA = async { pipeline.setRestDuckDb(-12.0) }
        val rampB = async { pipeline.setRestDuckDb(-3.0) }
        rampA.await()
        rampB.await()
        // Scheduler drainen: init-Collect plus beide Rampen (2 + 8 Schritte
        // mit je 20 ms delay); mit virtueller Zeit sofort durchlaufbar.
        repeat(200) { dispatcher.scheduler.advanceTimeBy(100) }
        dispatcher.scheduler.runCurrent()

        processor.queueInput(floatBuffer(0.8f))
        val out = readFloatBuffer(processor.output)
        val expected = (0.8f * AudioMath.dbToLinear(-3.0)).toFloat()
        assertEquals(expected, out[0], 1e-4f)
    }
}
