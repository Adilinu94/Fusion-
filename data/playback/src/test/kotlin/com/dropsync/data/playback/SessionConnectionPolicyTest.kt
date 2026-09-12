package com.dropsync.data.playback

import android.os.Bundle
import androidx.media3.common.Player
import androidx.media3.session.SessionCommand
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Tests fuer [SessionConnectionPolicy] (Ausbauplan A2): Die Staffelung
 * eigene App / System / Dritte laeuft unter Robolectric (sdk=34, siehe
 * robolectric.properties), weil SessionCommand/Bundle Android-Typen sind.
 */
@RunWith(AndroidJUnit4::class)
class SessionConnectionPolicyTest {
    private fun hasCustomAction(
        commands: androidx.media3.session.SessionCommands,
        action: String,
    ): Boolean = commands.contains(SessionCommand(action, Bundle.EMPTY))

    @Test
    fun `eigenes Paket erhaelt Custom-Kommandos`() {
        assertTrue(SessionConnectionPolicy.isOwnPackage("com.dropsync", "com.dropsync"))
        val commands = SessionConnectionPolicy.sessionCommands(isOwn = true)
        assertTrue(hasCustomAction(commands, PlaybackCommands.ACTION_PLAY_SONG_AT))
        assertTrue(hasCustomAction(commands, PlaybackCommands.ACTION_SET_SCRUBBING_MODE))
    }

    @Test
    fun `fremdes Paket erhaelt keine Custom-Kommandos, aber Browse`() {
        assertFalse(SessionConnectionPolicy.isOwnPackage("com.example.auto", "com.dropsync"))
        val commands = SessionConnectionPolicy.sessionCommands(isOwn = false)
        assertFalse(hasCustomAction(commands, PlaybackCommands.ACTION_PLAY_SONG_AT))
        assertFalse(hasCustomAction(commands, PlaybackCommands.ACTION_SET_SCRUBBING_MODE))
    }

    @Test
    fun `System-UIDs liegen unter FIRST_APPLICATION_UID`() {
        assertTrue(SessionConnectionPolicy.isSystemUid(1000))
        assertTrue(SessionConnectionPolicy.isSystemUid(9999))
        assertFalse(SessionConnectionPolicy.isSystemUid(10_000))
        assertFalse(SessionConnectionPolicy.isSystemUid(10_123))
    }

    @Test
    fun `Dritte behalten Transport, verlieren Queue- und Tempo-Eingriffe`() {
        val commands = SessionConnectionPolicy.playerCommands(isOwn = false, isSystem = false)
        assertTrue(commands.contains(Player.COMMAND_PLAY_PAUSE))
        assertTrue(commands.contains(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM))
        assertTrue(commands.contains(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM))
        assertFalse(commands.contains(Player.COMMAND_SET_MEDIA_ITEM))
        assertFalse(commands.contains(Player.COMMAND_CHANGE_MEDIA_ITEMS))
        assertFalse(commands.contains(Player.COMMAND_SET_PLAYLIST_METADATA))
        assertFalse(commands.contains(Player.COMMAND_SET_SPEED_AND_PITCH))
        assertFalse(commands.contains(Player.COMMAND_SET_TRACK_SELECTION_PARAMETERS))
    }

    @Test
    fun `eigene App und System behalten volle Player-Kommandos`() {
        val own = SessionConnectionPolicy.playerCommands(isOwn = true, isSystem = false)
        assertEquals(
            androidx.media3.session.MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS,
            own,
        )
        val system = SessionConnectionPolicy.playerCommands(isOwn = false, isSystem = true)
        assertEquals(
            androidx.media3.session.MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS,
            system,
        )
    }
}
