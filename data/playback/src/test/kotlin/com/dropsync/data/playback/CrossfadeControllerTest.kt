package com.dropsync.data.playback

import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dropsync.domain.audio.MixPreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

// Robolectric (sdk=34, siehe robolectric.properties) fuer die Media3-
// Data-Klassen; SDK 34 laeuft mit Java 17.
/** Gapless-/CUE-Regeln des Crossfade (Plan Phase 4, ADR-0007). */
@RunWith(AndroidJUnit4::class)
class CrossfadeControllerTest {
    @Test
    fun `verschiedene alben werden uebergeblendet`() {
        val current = item(mediaId = "1", album = "Album A")
        val next = item(mediaId = "2", album = "Album B")
        assertTrue(CrossfadeController.shouldCrossfade(current, next))
    }

    @Test
    fun `gleiches album bleibt gapless`() {
        val current = item(mediaId = "1", album = "Live in Tokyo")
        val next = item(mediaId = "2", album = "Live in Tokyo")
        assertFalse(CrossfadeController.shouldCrossfade(current, next))
    }

    @Test
    fun `ohne albuminfo wird uebergeblendet`() {
        val current = item(mediaId = "1", album = null)
        val next = item(mediaId = "2", album = "Album B")
        assertTrue(CrossfadeController.shouldCrossfade(current, next))
    }

    @Test
    fun `cue tracks werden nie uebergeblendet`() {
        val cue = item(mediaId = "${MediaItemFactory.CUE_MEDIA_ID_PREFIX}7:2", album = "Album A")
        val song = item(mediaId = "9", album = "Album B")
        assertFalse(CrossfadeController.shouldCrossfade(cue, song))
        assertFalse(CrossfadeController.shouldCrossfade(song, cue))
    }

    @Test
    fun `slam mikro rampe liefert zwischenwert am schnittpunkt`() {
        // Mix-Uebergaenge-Plan Phase 2: Der harte 0<->1-Sprung von SLAM
        // wird ueber das naechste 50-ms-Fenster gemittelt — am Schnittpunkt
        // entsteht ein Zwischenschritt statt eines Knacksers.
        val fadeMs = 6_000L
        // t knapp vor 0.5: naechster Schritt liegt hinter dem Schnitt.
        val t = 0.5 - 25.0 / fadeMs
        val gain = CrossfadeController.smoothedGain(MixPreset.SLAM, t, fadeMs, fadeOut = false)
        assertTrue("erwarte Zwischenwert, war $gain", gain > 0f && gain < 1f)
        // Weit vor bzw. nach dem Schnitt bleibt SLAM hart 0 bzw. 1.
        assertEquals(0f, CrossfadeController.smoothedGain(MixPreset.SLAM, 0.2, fadeMs, false), 0f)
        assertEquals(1f, CrossfadeController.smoothedGain(MixPreset.SLAM, 0.8, fadeMs, false), 0f)
    }

    @Test
    fun `glaettung veraendert stetige kurven praktisch nicht`() {
        val fadeMs = 6_000L
        for (t in listOf(0.0, 0.25, 0.5, 0.75)) {
            val smoothed =
                CrossfadeController.smoothedGain(MixPreset.FADE, t, fadeMs, fadeOut = false)
            assertEquals(MixPreset.FADE.fadeInGain(t).toFloat(), smoothed, 0.01f)
        }
    }

    private fun item(
        mediaId: String,
        album: String?,
    ): MediaItem =
        MediaItem
            .Builder()
            .setMediaId(mediaId)
            .setMediaMetadata(
                MediaMetadata
                    .Builder()
                    .setAlbumTitle(album)
                    .build(),
            ).build()
}
