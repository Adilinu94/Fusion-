package com.dropsync.data.playback

import android.content.Context
import android.os.Bundle
import androidx.media3.common.Player
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dropsync.core.common.AppResult
import com.dropsync.core.model.Song
import com.dropsync.core.testing.FakeLibraryBrowseRepository
import com.dropsync.domain.library.LibraryRepository
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Befund 8.2: Der Custom-Command-Dispatch des [PlaybackService] ist die
 * Vertragsflaeche zwischen App (Sender in `PlaybackRepositoryImpl`) und
 * Service (Empfaenger in `LibrarySessionCallback`). Genau hier lagen 4.1
 * (fehlende ARM/CANCEL_LANDING-Bewerbung) und 4.2 (Erfolgsmeldung trotz
 * Fehlschlag) — beide Tests greifen direkt an dieser Stelle an.
 *
 * Der Callback ist als eigenstaendige Klasse mit Funktionsparametern
 * gebaut, damit dieser Test ohne echten ExoPlayer auskommt; die Session
 * ist eine echte MediaSession ueber einen Dummy-Player (Robolectric,
 * sdk=34).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class PlaybackServiceCommandsTest {
    private val song =
        Song(
            mediaStoreId = 7L,
            contentUri = "content://media/external/audio/media/7",
            displayName = "neon-alley.mp3",
            relativePath = "Music/",
            durationMs = 180_000L,
            sizeBytes = 4_000_000L,
            dateModifiedSeconds = 1_700_000_000L,
            title = "Neon Alley",
            artist = "Nova Kane",
            album = null,
            isAvailable = true,
        )

    private class FakeLibraryRepository(
        private val knownSong: Song,
    ) : LibraryRepository {
        override suspend fun getSong(mediaStoreId: Long): AppResult<Song> =
            if (mediaStoreId == knownSong.mediaStoreId) {
                AppResult.Success(knownSong)
            } else {
                AppResult.Failure(
                    com.dropsync.core.common.AppError
                        .Unknown(debugMessage = null),
                )
            }

        override val songs = flowOf(emptyList<Song>())

        override val availableSongs = flowOf(emptyList<Song>())

        override suspend fun refreshLibrary(force: Boolean) =
            AppResult.Success(
                com.dropsync.domain.library
                    .LibraryScanResult(false, 0, 0, 0),
            )

        override suspend fun markUnavailable(mediaStoreId: Long) = AppResult.Success(Unit)

        override suspend fun importCueSheet(
            songId: Long,
            cueText: String,
        ) = AppResult.Success(0)

        override fun observeCueTracks(songId: Long) = flowOf(emptyList<com.dropsync.domain.library.CueVirtualTrack>())

        override suspend fun scanFolder(treeUri: String) =
            AppResult.Success(
                com.dropsync.domain.library
                    .FolderScanResult(0, 0, 0, 0),
            )

        override val scannedFiles =
            flowOf(emptyList<com.dropsync.domain.library.ScannedFile>())
    }

    private class FakePlayerStateStore : PlayerStateStore {
        override suspend fun read(): com.dropsync.domain.playback.PersistedPlayerState? = null

        override suspend fun write(state: com.dropsync.domain.playback.PersistedPlayerState) = Unit
    }

    private var session: MediaSession? = null
    private var player: Player? = null

    @After
    fun tearDown() {
        session?.release()
        session = null
        player?.release()
        player = null
    }

    private fun callback(
        onPlaySongAt: suspend (Long, Long) -> Boolean = { _, _ -> true },
        onArmLanding: suspend (Long, Long, Long, Long) -> Boolean = { _, _, _, _ -> true },
        onCancelLanding: suspend () -> Unit = {},
    ): PlaybackService.LibrarySessionCallback {
        val cb =
            PlaybackService.LibrarySessionCallback(
                scope = CoroutineScope(Dispatchers.Unconfined),
                stateStore = FakePlayerStateStore(),
                libraryRepository = FakeLibraryRepository(knownSong = song),
                browseRepository = FakeLibraryBrowseRepository(),
                labels =
                    PlaybackService.BrowseLabels(
                        root = "root",
                        songs = "Titel",
                        albums = "Alben",
                        artists = "Interpreten",
                        folders = "Ordner",
                    ),
                ownPackageName = "com.dropsync",
                onPlaySongAt = onPlaySongAt,
                onSetScrubbingMode = {},
                onArmLanding = onArmLanding,
                onCancelLanding = onCancelLanding,
            )
        // Echte Session ueber einen echten ExoPlayer (Robolectric): der
        // Session-Builder braucht einen gueltigen Player; `onCustomCommand`
        // liest ihn im Dispatch-Pfad nicht.
        val context = ApplicationProvider.getApplicationContext<Context>()
        val exoPlayer =
            androidx.media3.exoplayer.ExoPlayer
                .Builder(context)
                .build()
        player = exoPlayer
        session = MediaSession.Builder(context, exoPlayer).setCallback(cb).build()
        return cb
    }

    private fun controller(packageName: String): MediaSession.ControllerInfo =
        // Media3-Test-Fabrik: (packageName, uid, pid, libVersion,
        // interfaceVersion, trusted, connectionHints, packageNameVerified).
        MediaSession.ControllerInfo.createTestOnlyControllerInfo(
            packageName,
            // uid =
            10_123,
            // pid =
            1,
            // libVersion =
            1,
            // interfaceVersion =
            1,
            // trusted =
            false,
            // connectionHints =
            Bundle.EMPTY,
            // packageNameVerified =
            false,
        )

    private fun dispatch(
        cb: PlaybackService.LibrarySessionCallback,
        action: String,
        args: Bundle = Bundle.EMPTY,
    ): SessionResult =
        cb
            .onCustomCommand(
                session!!,
                controller("com.dropsync"),
                SessionCommand(action, Bundle.EMPTY),
                args,
            ).let { future -> future.get() }

    @Test
    fun `PLAY_SONG_AT meldet Erfolg nur nach echter Ausfuehrung`() =
        runTest {
            var executed = false
            val cb =
                callback(
                    onPlaySongAt = { _, _ ->
                        executed = true
                        true
                    },
                )
            val args =
                Bundle().apply {
                    putLong(PlaybackCommands.ARG_SONG_ID, 7L)
                    putLong(PlaybackCommands.ARG_START_POSITION_MS, 42_000L)
                }
            val result = dispatch(cb, PlaybackCommands.ACTION_PLAY_SONG_AT, args)
            assertEquals(SessionResult.RESULT_SUCCESS, result.resultCode)
            assertTrue(executed)
        }

    @Test
    fun `PLAY_SONG_AT mit fehlendem Song meldet ERROR_UNKNOWN statt Erfolg`() =
        runTest {
            val cb = callback(onPlaySongAt = { _, _ -> false })
            val args = Bundle().apply { putLong(PlaybackCommands.ARG_SONG_ID, 404L) }
            val result = dispatch(cb, PlaybackCommands.ACTION_PLAY_SONG_AT, args)
            assertEquals(SessionError.ERROR_UNKNOWN, result.resultCode)
        }

    @Test
    fun `PLAY_SONG_AT mit ungueltiger ID meldet BAD_VALUE`() =
        runTest {
            val cb = callback()
            val args = Bundle().apply { putLong(PlaybackCommands.ARG_SONG_ID, -1L) }
            val result = dispatch(cb, PlaybackCommands.ACTION_PLAY_SONG_AT, args)
            assertEquals(SessionError.ERROR_BAD_VALUE, result.resultCode)
        }

    @Test
    fun `Custom-Kommandos sind fremden Paketen verweigert`() =
        runTest {
            val cb = callback()
            val future =
                cb.onCustomCommand(
                    session!!,
                    controller("com.example.other"),
                    SessionCommand(PlaybackCommands.ACTION_PLAY_SONG_AT, Bundle.EMPTY),
                    Bundle().apply { putLong(PlaybackCommands.ARG_SONG_ID, 7L) },
                )
            assertEquals(SessionError.ERROR_PERMISSION_DENIED, future.get().resultCode)
        }

    @Test
    fun `ARM_LANDING reicht die Argumente durch und meldet Fehlschlag ehrlich`() =
        runTest {
            var received: List<Long>? = null
            val cb =
                callback(
                    onArmLanding = { songId, position, delay, fade ->
                        received = listOf(songId, position, delay, fade)
                        false
                    },
                )
            val args =
                Bundle().apply {
                    putLong(PlaybackCommands.ARG_SONG_ID, 7L)
                    putLong(PlaybackCommands.ARG_START_POSITION_MS, 1000L)
                    putLong(PlaybackCommands.ARG_DELAY_MS, 2000L)
                    putLong(PlaybackCommands.ARG_FADE_MS, 3000L)
                }
            val result = dispatch(cb, PlaybackCommands.ACTION_ARM_LANDING, args)
            assertEquals(listOf(7L, 1000L, 2000L, 3000L), received)
            assertEquals(SessionError.ERROR_UNKNOWN, result.resultCode)
        }

    @Test
    fun `CANCEL_LANDING antwortet Erfolg und ruft ab`() =
        runTest {
            var cancelled = false
            val cb = callback(onCancelLanding = { cancelled = true })
            val result = dispatch(cb, PlaybackCommands.ACTION_CANCEL_LANDING)
            assertEquals(SessionResult.RESULT_SUCCESS, result.resultCode)
            assertTrue(cancelled)
        }

    @Test
    fun `onConnect bewirbt alle vier Custom-Kommandos nur fuer die eigene App`() {
        val own = SessionConnectionPolicy.sessionCommands(isOwn = true)
        val other = SessionConnectionPolicy.sessionCommands(isOwn = false)
        for (
        action in
        listOf(
            PlaybackCommands.ACTION_PLAY_SONG_AT,
            PlaybackCommands.ACTION_SET_SCRUBBING_MODE,
            PlaybackCommands.ACTION_ARM_LANDING,
            PlaybackCommands.ACTION_CANCEL_LANDING,
        )
        ) {
            val command = SessionCommand(action, Bundle.EMPTY)
            assertTrue("$action fehlt beim eigenen Paket", own.contains(command))
            org.junit.Assert.assertFalse("$action leakt an Dritte", other.contains(command))
        }
    }
}
