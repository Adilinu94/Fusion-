package com.dropsync.domain.workout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Der Musikbezug des Satzes (2026-09-27, Befund 11.3/13.4).
 *
 * **Warum ein eigener Test und kein Zusatz im Repository-Test:** die
 * Regeln hier sind klein, aber sie sind die **Bedingungen**, unter denen
 * die Historie ueberhaupt etwas aussagt. Ein Fehler faellt nicht als
 * "falsche Zahl" auf, sondern als "die Historie sagt Dinge, die nicht
 * passiert sind" — das sieht man erst spaet.
 */
class MusicContextTest {
    /**
     * Ohne `songId` gibt es keinen Musikbezug — auch nicht mit Marker.
     * Das verhindert, dass ein Marker ohne zugehoerigen Song in der
     * Historie landet, wo die Frage "welche Musik lief?" keine Antwort
     * bekaeme.
     */
    @Test
    fun `ohne song id ist der bezug leer`() {
        val context = MusicContext.of(songId = null, positionMs = 1_000L, activeMarkerId = 88L)
        assertEquals(MusicContext.NONE, context)
    }

    /** Der Normalfall: alle drei Werte kommen durch. */
    @Test
    fun `vollstaendiger bezug bleibt unveraendert`() {
        val context = MusicContext.of(songId = 4711L, positionMs = 42_000L, activeMarkerId = 88L)
        assertEquals(4711L, context.songId)
        assertEquals(42_000L, context.playbackPositionMs)
        assertEquals(88L, context.markerId)
    }

    /**
     * Eine **negative** Position ist ein Rechenfehler (ein
     * MediaPlayer-Sprung vor dem Anfang). Sie wird auf 0 geklemmt, nicht
     * gespeichert: eine negative Position in der Historie liest sich wie
     * "0,004 Sekunden vor dem Titelanfang" und ist damit wertlos.
     */
    @Test
    fun `negative position wird auf null geklemmt`() {
        assertEquals(0L, MusicContext.of(4711L, -1L, 88L).playbackPositionMs)
    }

    /**
     * Eine **negative** Position auf einem Title ohne Marker: der
     * Klemmfall tritt auch ohne `activeMarkerId` auf, weil die
     * Klemmung am Positionswert haengt, nicht am Marker.
     */
    @Test
    fun `negative position ohne marker wird ebenfalls geklemmt`() {
        val context = MusicContext.of(songId = 4711L, positionMs = -500L, activeMarkerId = null)
        assertEquals(0L, context.playbackPositionMs)
        assertNull(context.markerId)
    }

    /** Ohne Position bleibt sie `null` — nicht 0. Der Unterschied zaehlt. */
    @Test
    fun `fehlende position bleibt null statt null zu werden`() {
        assertNull(MusicContext.of(4711L, null, 88L).playbackPositionMs)
    }

    /** `NONE` ist der Default und traegt nichts. */
    @Test
    fun `none traegt keinen bezug`() {
        assertNull(MusicContext.NONE.songId)
        assertNull(MusicContext.NONE.playbackPositionMs)
        assertNull(MusicContext.NONE.markerId)
    }
}

/**
 * [FlatSet.hasMusicContext] — die Anzeige-Eigenschaft, die der
 * Satz-Log benutzt, um den Musikbezug zu zeigen oder nicht.
 */
class FlatSetMusicContextTest {
    private fun set(
        songId: Long?,
        positionMs: Long? = null,
        markerId: Long? = null,
    ) = FlatSet(
        id = 1L,
        exerciseId = 2L,
        weightMilliKg = 60_000,
        reps = 8,
        loggedAtEpochMs = 1_700_000_000_000,
        songId = songId,
        playbackPositionMs = positionMs,
        markerId = markerId,
    )

    @Test
    fun `satz mit song hat musikbezug`() {
        assertTrue(set(songId = 4711L).hasMusicContext)
    }

    /**
     * Ein **Marker ohne** Song zaehlt nicht als Musikbezug. Die
     * Information waere nicht lesbar — zu welchem Titel gehoert der
     * Drop?
     */
    @Test
    fun `marker ohne song ist kein musikbezug`() {
        assertFalse(set(songId = null, markerId = 88L).hasMusicContext)
    }

    @Test
    fun `satz ohne song hat keinen musikbezug`() {
        assertFalse(set(songId = null).hasMusicContext)
    }

    /**
     * Das Volumen bleibt unabhaengig vom Musikbezug. Das ist der Punkt
     * der Aussage im KDoc: der Musikbezug ist eine **Information**, kein
     * Kriterium. Ein Satz mit Musik zaehlt genauso wie einer ohne.
     */
    @Test
    fun `volumen haengt nicht am musikbezug`() {
        assertEquals(
            set(songId = 4711L, positionMs = 1_000L, markerId = 88L).volumeKg,
            set(songId = null).volumeKg,
            1e-9,
        )
    }
}
