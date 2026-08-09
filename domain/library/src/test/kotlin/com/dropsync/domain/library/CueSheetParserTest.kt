package com.dropsync.domain.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tests fuer den CUE-Parser (Plan Phase 3, reine JVM). */
class CueSheetParserTest {
    private val singleFile =
        """
        REM GENRE Rock
        PERFORMER "Album Artist"
        TITLE "Great Album"
        FILE "album.flac" WAVE
          TRACK 01 AUDIO
            TITLE "First Song"
            PERFORMER "Artist One"
            INDEX 01 00:00:00
          TRACK 02 AUDIO
            TITLE "Second Song"
            INDEX 00 03:58:00
            INDEX 01 04:00:00
          TRACK 03 AUDIO
            TITLE "Third Song"
            INDEX 01 07:30:37
        """.trimIndent()

    @Test
    fun `parst album und tracks mit start und endzeiten`() {
        val result = CueSheetParser.parse(singleFile)
        assertTrue(result is ParsedCueSheet.Success)
        val sheet = (result as ParsedCueSheet.Success).sheet

        assertEquals("Great Album", sheet.albumTitle)
        assertEquals("Album Artist", sheet.albumPerformer)
        assertEquals(3, sheet.tracks.size)

        val first = sheet.tracks[0]
        assertEquals(1, first.number)
        assertEquals("First Song", first.title)
        assertEquals("Artist One", first.performer)
        assertEquals(0L, first.startMs)
        // Endzeit = Start des zweiten Tracks (INDEX 01, nicht der Pregap).
        assertEquals(4L * 60 * 1000, first.endMs)

        val third = sheet.tracks[2]
        // 07:30:37 -> (450 s)*1000 + 37*1000/75 = 450000 + 493 = 450493 ms.
        assertEquals(450_493L, third.startMs)
        // Letzter Track derselben Datei: keine Endzeit (bis Dateiende).
        assertNull(third.endMs)
    }

    @Test
    fun `mehrdatei-sheet setzt endzeit nur innerhalb derselben datei`() {
        val text =
            """
            FILE "a.flac" WAVE
              TRACK 01 AUDIO
                INDEX 01 00:00:00
            FILE "b.flac" WAVE
              TRACK 02 AUDIO
                INDEX 01 00:00:00
            """.trimIndent()
        val result = CueSheetParser.parse(text)
        assertTrue(result is ParsedCueSheet.Success)
        val tracks = (result as ParsedCueSheet.Success).sheet.tracks
        assertEquals("a.flac", tracks[0].file)
        assertEquals("b.flac", tracks[1].file)
        // Verschiedene Dateien -> Track 1 endet ohne Bezug auf Track 2.
        assertNull(tracks[0].endMs)
    }

    @Test
    fun `leeres oder tracklose sheet ist malformed`() {
        assertTrue(CueSheetParser.parse("") is ParsedCueSheet.Malformed)
        assertTrue(
            CueSheetParser.parse("FILE \"x.flac\" WAVE") is ParsedCueSheet.Malformed,
        )
    }

    @Test
    fun `track ohne index 01 ist malformed`() {
        val text =
            """
            FILE "a.flac" WAVE
              TRACK 01 AUDIO
                TITLE "No Index"
            """.trimIndent()
        assertTrue(CueSheetParser.parse(text) is ParsedCueSheet.Malformed)
    }
}
