package com.dropsync.data.playback

import androidx.media3.common.MediaItem
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dropsync.core.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

// Robolectric (sdk=34, siehe robolectric.properties): Uri.parse braucht
// die Sandbox, SDK 34 laeuft mit Java 17.
@RunWith(AndroidJUnit4::class)
class MediaItemFactoryTest {
    private val song =
        Song(
            mediaStoreId = 42,
            contentUri = "content://media/external/audio/media/42",
            displayName = "drop.mp3",
            relativePath = "Music/Training",
            durationMs = 215_000,
            sizeBytes = 8_421_137,
            dateModifiedSeconds = 1_700_000_000,
            title = "Drop Anthem",
            artist = "Local Artist",
            album = "Local Album",
            isAvailable = true,
        )

    @Test
    fun `fromSong setzt mediaId uri und lokale metadaten`() {
        // Schritt 5.4: mediaStoreId als mediaId, contentUri, volle Metadaten.
        val item = MediaItemFactory.fromSong(song)

        assertEquals("42", item.mediaId)
        assertEquals(song.contentUri, item.localConfiguration?.uri?.toString())
        assertEquals("Drop Anthem", item.mediaMetadata.title)
        assertEquals("Local Artist", item.mediaMetadata.artist)
        assertEquals("Local Album", item.mediaMetadata.albumTitle)
    }

    @Test
    fun `fromSong faellt ohne titel auf dateinamen zurueck`() {
        val item = MediaItemFactory.fromSong(song.copy(title = null))
        assertEquals("drop.mp3", item.mediaMetadata.title)
    }

    @Test
    fun `resolveForPlayback stellt die uri aus requestMetadata wieder her`() {
        // Simuliert ein Item, dem der MediaController die
        // localConfiguration entfernt hat (Media3-Verhalten).
        val original = MediaItemFactory.fromSong(song)
        val stripped =
            MediaItem
                .Builder()
                .setMediaId(original.mediaId)
                .setRequestMetadata(original.requestMetadata)
                .setMediaMetadata(original.mediaMetadata)
                .build()
        assertNull(stripped.localConfiguration)

        val resolved = MediaItemFactory.resolveForPlayback(stripped)

        assertEquals(song.contentUri, resolved.localConfiguration?.uri?.toString())
        assertEquals("42", resolved.mediaId)
    }

    @Test
    fun `resolveForPlayback laesst vollstaendige items unveraendert`() {
        val original = MediaItemFactory.fromSong(song)
        assertEquals(original, MediaItemFactory.resolveForPlayback(original))
    }
}
