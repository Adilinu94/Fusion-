package com.dropsync.core.designsystem.component

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.atomic.AtomicInteger

/**
 * Cache- und Fehlerlogik des [CoverArtLoader] mit austauschbarer Quelle (kein echter Medienstack):
 * "kein Cover" ist endgueltig, ein Fehlschlag nur kurz gemerkt, Treffer werden gecacht, und die
 * parallelen Dekodierungen sind begrenzt.
 */
@RunWith(RobolectricTestRunner::class)
class CoverArtLoaderTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private var nowMs = 1_000L

    @Before
    fun setUp() {
        CoverArtLoader.resetForTest()
        CoverArtLoader.clockMs = { nowMs }
    }

    @After
    fun tearDown() = CoverArtLoader.resetForTest()

    private fun bitmap(): ImageBitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888).asImageBitmap()

    private class ScriptedSource(
        private val script: List<CoverResult>,
    ) : CoverSource {
        val calls = AtomicInteger(0)

        override fun read(
            context: Context,
            contentUri: String,
            maxDimPx: Int,
        ): CoverResult = script[minOf(calls.getAndIncrement(), script.lastIndex)]
    }

    @Test
    fun `Treffer wird gecacht`() =
        runBlocking {
            val source = ScriptedSource(listOf(CoverResult.Decoded(bitmap())))
            CoverArtLoader.source = source

            val first = CoverArtLoader.load(context, "content://a", 256)
            val second = CoverArtLoader.load(context, "content://a", 256)

            assertNotNull(first)
            assertSame(first, second)
            assertEquals(1, source.calls.get())
        }

    @Test
    fun `kein Cover ist endgueltig und wird nicht erneut gesucht`() =
        runBlocking {
            val source = ScriptedSource(listOf(CoverResult.Missing))
            CoverArtLoader.source = source

            assertNull(CoverArtLoader.load(context, "content://a", 256))
            nowMs += 10 * CoverArtLoader.FAILURE_RETRY_MS
            assertNull(CoverArtLoader.load(context, "content://a", 256))

            assertEquals(1, source.calls.get())
        }

    @Test
    fun `Fehlschlag wird kurz gemerkt und danach erneut versucht`() =
        runBlocking {
            val source = ScriptedSource(listOf(CoverResult.Failed, CoverResult.Decoded(bitmap())))
            CoverArtLoader.source = source

            assertNull(CoverArtLoader.load(context, "content://a", 256))
            nowMs += CoverArtLoader.FAILURE_RETRY_MS / 2
            assertNull("innerhalb des Fensters kein neuer Versuch", CoverArtLoader.load(context, "content://a", 256))
            assertEquals(1, source.calls.get())

            nowMs += CoverArtLoader.FAILURE_RETRY_MS
            assertNotNull(
                "nach dem Fenster klappt der zweite Versuch",
                CoverArtLoader.load(context, "content://a", 256),
            )
            assertEquals(2, source.calls.get())
        }

    @Test
    fun `groessere Anfrage laedt neu, wenn nur ein kleineres Bild im Cache liegt`() =
        runBlocking {
            val source = ScriptedSource(listOf(CoverResult.Decoded(bitmap())))
            CoverArtLoader.source = source

            CoverArtLoader.load(context, "content://a", 256)
            CoverArtLoader.load(context, "content://a", 1024)

            assertEquals(2, source.calls.get())
        }

    @Test
    fun `parallele Dekodierungen sind begrenzt`() =
        runBlocking {
            val active = AtomicInteger(0)
            val peak = AtomicInteger(0)
            CoverArtLoader.source =
                object : CoverSource {
                    override fun read(
                        context: Context,
                        contentUri: String,
                        maxDimPx: Int,
                    ): CoverResult {
                        peak.accumulateAndGet(active.incrementAndGet(), ::maxOf)
                        Thread.sleep(30)
                        active.decrementAndGet()
                        return CoverResult.Missing
                    }
                }

            (1..20).map { async(Dispatchers.IO) { CoverArtLoader.load(context, "content://song/$it", 256) } }.awaitAll()

            assertTrue(
                "hoechstens ${CoverArtLoader.MAX_PARALLEL_DECODES} gleichzeitig, war ${peak.get()}",
                peak.get() <= CoverArtLoader.MAX_PARALLEL_DECODES,
            )
        }

    @Test
    fun `erstes Decoded gewinnt und spaetere Schritte laufen nicht mehr`() {
        var secondRan = false
        val result =
            firstDecodedOrWorst(
                listOf({ CoverResult.Decoded(bitmap()) }, {
                    secondRan = true
                    CoverResult.Failed
                }),
            )

        assertTrue(result is CoverResult.Decoded)
        assertTrue("kurzgeschlossen", !secondRan)
    }

    @Test
    fun `Fehlschlag schlaegt Missing, nur Missing ergibt Missing`() {
        assertEquals(CoverResult.Failed, firstDecodedOrWorst(listOf({ CoverResult.Missing }, { CoverResult.Failed })))
        assertEquals(CoverResult.Failed, firstDecodedOrWorst(listOf({ CoverResult.Failed }, { CoverResult.Missing })))
        assertEquals(CoverResult.Missing, firstDecodedOrWorst(listOf({ CoverResult.Missing }, { CoverResult.Missing })))
        assertEquals(CoverResult.Missing, firstDecodedOrWorst(emptyList()))
    }
}
