package com.dropsync.data.audio

import android.media.MediaFormat
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.dropsync.core.common.AppResult
import com.dropsync.core.model.Song
import com.dropsync.core.testing.TestDispatcherProvider
import com.dropsync.domain.audio.AnalysisProfile
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowMediaCodec
import org.robolectric.shadows.ShadowMediaExtractor
import org.robolectric.shadows.util.DataSource

/**
 * Verifikation der Abbruchkooperation im Decoder-Loop (Umbauplan
 * Grundregeln, Befund B-AUD-6). `TrackAnalyzerImpl.drainDecoder()` hat
 * ausser `ensureActive()` keinen Suspension-Punkt: ohne die Pruefung laeuft
 * ein abgebrochener Lauf bis zum Trackende weiter und haelt dabei CPU und
 * eine MediaCodec-Instanz. Genau darauf baut das Cancel-und-Ueberholen des
 * Prioritaetspfads (ADR-0015) auf.
 *
 * Der Abbruch wird aus dem Codec-Callback heraus ausgeloest, nicht per
 * Wartezeit: so ist der Test deterministisch und einthreadig, und die
 * Anzahl verarbeiteter Buffer ist exakt pruefbar statt nur "kleiner als".
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class TrackAnalyzerCancellationTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    /** Zaehlt die Aufrufe des Fake-Decoders, also die Schleifendurchlaeufe. */
    private var processedBuffers = 0

    /** Wird aus dem Codec-Callback heraus abgebrochen, sobald gesetzt. */
    private var runningJob: Job? = null

    /** Nach welchem Buffer der Callback den Lauf abbricht; 0 = nie. */
    private var cancelAfterBuffer = 0

    @Before
    fun registerFakeCodec() {
        processedBuffers = 0
        runningJob = null
        cancelAfterBuffer = 0

        // Der Fake-Decoder kopiert PCM unveraendert durch. Die Groessen
        // bestimmen, wie viele Schleifendurchlaeufe der Track ergibt.
        val config =
            ShadowMediaCodec.CodecConfig(BUFFER_BYTES, BUFFER_BYTES) { input, output ->
                processedBuffers++
                if (cancelAfterBuffer > 0 && processedBuffers == cancelAfterBuffer) {
                    // Abbruch von "aussen" simuliert: der Nutzer wischt zum
                    // naechsten Titel, cancelOvertakenRunsLocked() kuendigt.
                    runningJob?.cancel()
                }
                val copyBytes = minOf(input.remaining(), output.remaining())
                repeat(copyBytes) { output.put(input.get()) }
            }
        ShadowMediaCodec.addDecoder(MIME_TYPE, config)

        ShadowMediaExtractor.addTrack(
            DataSource.toDataSource(context, Uri.parse(CONTENT_URI)),
            trackFormat(),
            ByteArray(PCM_BYTES),
        )
    }

    @After
    fun clearFakeCodec() {
        ShadowMediaCodec.clearCodecs()
        ShadowMediaExtractor.reset()
        DataSource.reset()
    }

    @Test
    fun `vollstaendiger lauf verarbeitet alle buffer`() =
        runTest {
            val analyzer = analyzer(testScheduler)

            val result = analyzer.analyze(song(), AnalysisProfile.WAVEFORM_ONLY)

            assertTrue("Analyse sollte erfolgreich sein", result is AppResult.Success)
            // Referenzwert fuer den Abbruchtest: ohne Abbruch laeuft die
            // Schleife wirklich ueber viele Buffer. Ohne diese Zahl waere die
            // Aussage des naechsten Tests wertlos.
            assertTrue(
                "Fixture muss deutlich mehr Buffer liefern als der Abbruchpunkt, war $processedBuffers",
                processedBuffers > CANCEL_AFTER * 4,
            )
        }

    @Test
    fun `abbruch stoppt den decoder-loop vor dem trackende`() =
        runTest {
            cancelAfterBuffer = CANCEL_AFTER
            val analyzer = analyzer(testScheduler)
            var reachedEnd = false

            val job =
                launch {
                    analyzer.analyze(song(), AnalysisProfile.WAVEFORM_ONLY)
                    reachedEnd = true
                }
            runningJob = job
            advanceUntilIdle()

            assertTrue("Der Lauf muss als abgebrochen enden", job.isCancelled)
            assertFalse("analyze() darf nach Abbruch nicht normal zurueckkehren", reachedEnd)
            // Der ensureActive()-Check sitzt am Schleifenkopf: nach dem
            // Abbruch im Buffer CANCEL_AFTER laeuft der aktuelle Durchlauf zu
            // Ende, danach wirft der Kopf. Es darf also KEIN weiterer Buffer
            // dekodiert werden.
            assertEquals(
                "Decoder muss unmittelbar nach dem Abbruch stehen",
                CANCEL_AFTER,
                processedBuffers,
            )
        }

    private fun analyzer(scheduler: TestCoroutineScheduler) =
        TrackAnalyzerImpl(
            context = context,
            // StandardTestDispatcher am Scheduler des Tests, nicht Unconfined:
            // der Lauf darf erst starten, wenn runningJob gesetzt ist. Ein
            // eigener Scheduler wuerde "Detected use of different schedulers"
            // ausloesen.
            dispatchers = TestDispatcherProvider(StandardTestDispatcher(scheduler)),
        )

    private fun trackFormat(): MediaFormat =
        MediaFormat.createAudioFormat(MIME_TYPE, SAMPLE_RATE_HZ, CHANNEL_COUNT).apply {
            setLong(MediaFormat.KEY_DURATION, DURATION_MS * 1_000L)
        }

    private fun song(): Song =
        Song(
            mediaStoreId = 4711L,
            contentUri = CONTENT_URI,
            displayName = "abbruch.mp3",
            relativePath = "Music/",
            durationMs = DURATION_MS,
            sizeBytes = PCM_BYTES.toLong(),
            dateModifiedSeconds = 0L,
            title = "Abbruch",
            artist = null,
            album = null,
            isAvailable = true,
        )

    private companion object {
        const val MIME_TYPE = "audio/mpeg"
        const val CONTENT_URI = "content://media/external/audio/media/4711"
        const val SAMPLE_RATE_HZ = 44_100
        const val CHANNEL_COUNT = 1
        const val DURATION_MS = 2_000L

        /** 16-bit Mono ueber die volle Dauer. */
        const val PCM_BYTES = (SAMPLE_RATE_HZ * DURATION_MS / 1_000L * 2L).toInt()
        const val BUFFER_BYTES = 4_096
        const val CANCEL_AFTER = 5
    }
}
