package com.dropsync.data.playback

import com.dropsync.domain.playback.RepeatMode
import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerStateStoreTest {
    @Test
    fun `queue roundtrip bleibt verlustfrei`() {
        val ids = listOf(42L, 7L, 1_000_000L)
        val encoded = DataStorePlayerStateStore.encodeQueue(ids)
        assertEquals(ids, DataStorePlayerStateStore.decodeQueue(encoded))
    }

    @Test
    fun `leere queue ergibt leere liste`() {
        assertEquals(
            emptyList<Long>(),
            DataStorePlayerStateStore.decodeQueue(""),
        )
    }

    @Test
    fun `kaputte eintraege werden ignoriert statt zu crashen`() {
        assertEquals(
            listOf(1L, 3L),
            DataStorePlayerStateStore.decodeQueue("1,abc,3"),
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
