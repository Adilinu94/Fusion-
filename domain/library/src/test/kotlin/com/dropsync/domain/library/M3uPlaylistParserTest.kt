package com.dropsync.domain.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tests fuer den M3U/M3U8-Parser (Plan Phase 3, reine JVM). */
class M3uPlaylistParserTest {
    @Test
    fun `parst extinf-metadaten und lokale sowie ferne eintraege`() {
        val text =
            """
            #EXTM3U
            #EXTINF:213,Artist - Local Song
            music/song1.mp3
            #EXTINF:-1,Web Radio
            https://example.com/stream.aac
            ../other/song2.flac
            """.trimIndent()
        val playlist = M3uPlaylistParser.parse(text)

        assertEquals(3, playlist.entries.size)

        val first = playlist.entries[0]
        assertEquals("music/song1.mp3", first.location)
        assertEquals("Artist - Local Song", first.title)
        assertEquals(213, first.durationSeconds)
        assertFalse(first.isRemote)

        val second = playlist.entries[1]
        assertEquals("https://example.com/stream.aac", second.location)
        assertEquals("Web Radio", second.title)
        assertEquals(-1, second.durationSeconds)
        assertTrue(second.isRemote)

        // Eintrag ohne vorangehendes EXTINF traegt keine Metadaten.
        val third = playlist.entries[2]
        assertEquals("../other/song2.flac", third.location)
        assertNull(third.title)
        assertNull(third.durationSeconds)
    }

    @Test
    fun `ueberspringt leerzeilen und unbekannte kommentare`() {
        val text = "\n#PLAYLIST:Test\n\nsong.mp3\n"
        val playlist = M3uPlaylistParser.parse(text)
        assertEquals(1, playlist.entries.size)
        assertEquals("song.mp3", playlist.entries.first().location)
    }

    @Test
    fun `loest relative pfade gegen das basisverzeichnis auf`() {
        assertEquals(
            "Music/Rock/song.mp3",
            M3uPlaylistParser.resolveLocal("Music/Rock", "song.mp3"),
        )
        assertEquals(
            "Music/song.mp3",
            M3uPlaylistParser.resolveLocal("Music/Rock", "../song.mp3"),
        )
        assertEquals(
            "Music/song.mp3",
            M3uPlaylistParser.resolveLocal("Music/Rock", "./.././song.mp3"),
        )
    }

    @Test
    fun `laesst absolute pfade und netzquellen unveraendert`() {
        assertEquals(
            "https://example.com/x.mp3",
            M3uPlaylistParser.resolveLocal("Music", "https://example.com/x.mp3"),
        )
        assertEquals("/storage/emulated/0/x.mp3", M3uPlaylistParser.resolveLocal("Music", "/storage/emulated/0/x.mp3"))
        assertTrue(M3uPlaylistParser.isRemote("http://a/b"))
        assertFalse(M3uPlaylistParser.isRemote("folder/file.mp3"))
    }
}
