package com.dropsync.data.playback

import com.dropsync.domain.playback.PersistedQueueEntry
import com.dropsync.domain.playback.RepeatMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlayerStateStoreTest {
    @Test
    fun `queue roundtrip bleibt verlustfrei`() {
        val entries =
            listOf(
                PersistedQueueEntry(mediaId = "42", songId = 42L),
                PersistedQueueEntry(mediaId = "7", songId = 7L),
                PersistedQueueEntry(mediaId = "1000000", songId = 1_000_000L),
            )
        val encoded = DataStorePlayerStateStore.encodeQueue(entries)
        assertEquals(entries, DataStorePlayerStateStore.decodeQueue(encoded))
    }

    /**
     * 2026-09-27, Befund 4.1: CUE-Tracks haben die mediaId
     * `cue:<songId>:<trackNr>`. `toLongOrNull()` liefert dafuer null, und
     * ueber `mapNotNull` fielen sie aus der Persistenz heraus — die Queue
     * war nach Prozess-Tod unvollstaendig. Dieser Test faellt genau das ab.
     */
    @Test
    fun `cue tracks ueberleben den persistenz-roundtrip`() {
        val entries =
            listOf(
                PersistedQueueEntry(mediaId = "cue:42:1", songId = 42L),
                PersistedQueueEntry(mediaId = "7", songId = 7L),
                PersistedQueueEntry(mediaId = "cue:42:2", songId = 42L),
            )
        val encoded = DataStorePlayerStateStore.encodeQueue(entries)

        assertEquals(entries, DataStorePlayerStateStore.decodeQueue(encoded))
        // Ohne den Fix waeren nach dem Decode nur ein Eintrag uebrig.
        assertEquals(3, DataStorePlayerStateStore.decodeQueue(encoded).size)
    }

    @Test
    fun `songIdOf loest die datei hinter einem cue track auf`() {
        assertEquals(42L, DataStorePlayerStateStore.songIdOf("42"))
        assertEquals(42L, DataStorePlayerStateStore.songIdOf("cue:42:1"))
        assertEquals(42L, DataStorePlayerStateStore.songIdOf("cue:42:17"))
        assertNull(DataStorePlayerStateStore.songIdOf("cue:abc:1"))
        assertNull(DataStorePlayerStateStore.songIdOf("sonderformat"))
    }

    @Test
    fun `cue track nummer wird aus der mediaId gelesen`() {
        assertEquals(1, MediaItemFactory.cueTrackNumberOf("cue:42:1"))
        assertEquals(17, MediaItemFactory.cueTrackNumberOf("cue:42:17"))
        assertNull(MediaItemFactory.cueTrackNumberOf("42"))
        assertNull(MediaItemFactory.cueTrackNumberOf("cue:42:abc"))
    }

    @Test
    fun `leere queue ergibt leere liste`() {
        assertEquals(
            emptyList<PersistedQueueEntry>(),
            DataStorePlayerStateStore.decodeQueue(""),
        )
    }

    @Test
    fun `kaputte eintraege werden ignoriert statt zu crashen`() {
        assertEquals(
            listOf(
                PersistedQueueEntry(mediaId = "1", songId = 1L),
                PersistedQueueEntry(mediaId = "3", songId = 3L),
            ),
            DataStorePlayerStateStore.decodeQueue("1,abc,3"),
        )
    }

    /**
     * Der Legacy-Codec speicherte kommagetrennte Song-IDs. Ein Zustand aus
     * der alten Fassung muss nach dem Update lesbar bleiben, sonst verliert
     * der Nutzer beim ersten Start seine Queue.
     */
    @Test
    fun `legacy-format mit song-ids bleibt lesbar`() {
        val decoded = DataStorePlayerStateStore.decodeQueue("1,2,3")
        assertEquals(
            listOf(
                PersistedQueueEntry(mediaId = "1", songId = 1L),
                PersistedQueueEntry(mediaId = "2", songId = 2L),
                PersistedQueueEntry(mediaId = "3", songId = 3L),
            ),
            decoded,
        )
    }

    @Test
    fun `unbekannter repeat modus faellt auf OFF zurueck`() {
        assertEquals(
            RepeatMode.OFF,
            DataStorePlayerStateStore.decodeRepeatMode("KAPUTT"),
        )
        assertEquals(
            RepeatMode.ONE,
            DataStorePlayerStateStore.decodeRepeatMode("ONE"),
        )
        assertEquals(
            RepeatMode.OFF,
            DataStorePlayerStateStore.decodeRepeatMode(null),
        )
    }
}
