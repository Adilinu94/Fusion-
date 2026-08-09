package com.dropsync.domain.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tests fuer den Audioformat-Katalog (Plan Phase 3, reine JVM). */
class AudioFileFormatTest {
    @Test
    fun `erkennt formate aus dateinamen unabhaengig von gross-kleinschreibung`() {
        assertEquals(AudioFileFormat.FLAC, AudioFileFormat.fromFileName("Song.FLAC"))
        assertEquals(AudioFileFormat.APE, AudioFileFormat.fromFileName("album.ape"))
        assertEquals(AudioFileFormat.DSF, AudioFileFormat.fromFileName("track.dsf"))
        assertEquals(AudioFileFormat.M4A, AudioFileFormat.fromExtension("m4a"))
    }

    @Test
    fun `unbekannte oder endungslose namen liefern null`() {
        assertNull(AudioFileFormat.fromFileName("liesmich"))
        assertNull(AudioFileFormat.fromFileName("archiv.zip"))
        assertNull(AudioFileFormat.fromFileName("punktende."))
    }

    @Test
    fun `folderscan-endungen sind genau die nicht indexierten formate`() {
        val scan = AudioFileFormat.folderScanExtensions
        // MediaStore verpasst diese: der SAF-Ordnerscan muss sie finden.
        assertTrue(scan.containsAll(listOf("ape", "tak", "tta", "dsf", "dff", "wma", "wv")))
        // Von MediaStore indexierte Formate gehoeren nicht dazu.
        assertFalse(scan.contains("mp3"))
        assertFalse(scan.contains("flac"))
    }

    @Test
    fun `ffmpeg-pflicht deckt sich mit den nicht indexierten formaten`() {
        // In diesem Katalog braucht genau das, was MediaStore nicht kennt,
        // die FFmpeg-Extension.
        for (format in AudioFileFormat.entries) {
            assertEquals(
                "Format $format: FFmpeg-Pflicht != nicht-indexiert",
                !format.indexedByMediaStore,
                format.requiresFfmpegExtension,
            )
        }
    }

    @Test
    fun `erkennt cue- und playlist-dateien`() {
        assertTrue(AudioFileFormat.isCueFile("Album.CUE"))
        assertFalse(AudioFileFormat.isCueFile("Album.flac"))
        assertTrue(AudioFileFormat.isPlaylistFile("list.m3u"))
        assertTrue(AudioFileFormat.isPlaylistFile("list.m3u8"))
        assertFalse(AudioFileFormat.isPlaylistFile("list.txt"))
    }
}
