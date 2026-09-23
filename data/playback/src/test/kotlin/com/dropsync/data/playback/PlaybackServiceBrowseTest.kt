package com.dropsync.data.playback

import android.content.Context
import android.os.Bundle
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import androidx.media3.session.LibraryResult
import androidx.media3.session.SessionResult
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dropsync.core.model.Song
import com.dropsync.core.testing.FakeLibraryBrowseRepository
import com.dropsync.domain.library.Album
import com.dropsync.domain.library.Artist
import com.dropsync.domain.library.LibraryFolder
import com.dropsync.domain.playback.PersistedPlayerState
import com.dropsync.domain.playback.RepeatMode
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Browse- und Resumption-Pfade des [PlaybackService.LibrarySessionCallback]
 * (Ueberarbeitungsbericht Punkt "LibrarySessionCallback internal + Tests"):
 * die Dispatch-Vertraege (Custom-Commands, Berechtigungen) deckt
 * [PlaybackServiceCommandsTest] ab; hier geht es um die Media-Library-Sicht
 * (Root, Kategorien, Album/Artist/Folder-Unterknoten) und um
 * [androidx.media3.session.MediaSession.Callback.onPlaybackResumption]
 * (Wiederaufnahme nach Reboot/Systemneustart ueber den PlayerStateStore).
 *
 * Robolectric, sdk=34 — gleiche Infra wie der Dispatch-Test.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class PlaybackServiceBrowseTest {
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

    private class FakePlayerStateStore(
        private val state: PersistedPlayerState?,
    ) : PlayerStateStore {
        override suspend fun read(): PersistedPlayerState? = state

        override suspend fun write(state: PersistedPlayerState) = Unit
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
        browseRepository: FakeLibraryBrowseRepository =
            FakeLibraryBrowseRepository(),
        librarySongs: List<Song> = listOf(song),
        stateStore: PlayerStateStore = FakePlayerStateStore(null),
    ): PlaybackService.LibrarySessionCallback {
        val cb =
            PlaybackService.LibrarySessionCallback(
                scope = CoroutineScope(Dispatchers.Unconfined),
                stateStore = stateStore,
                libraryRepository = FakeLibraryRepository(librarySongs),
                browseRepository = browseRepository,
                labels =
                    PlaybackService.BrowseLabels(
                        root = "root",
                        songs = "Titel",
                        albums = "Alben",
                        artists = "Interpreten",
                        folders = "Ordner",
                    ),
                ownPackageName = "com.dropsync",
                onPlaySongAt = { _, _ -> true },
                onSetScrubbingMode = {},
                onArmLanding = { _, _, _, _ -> true },
                onCancelLanding = {},
            )
        val context = ApplicationProvider.getApplicationContext<Context>()
        val exoPlayer = androidx.media3.exoplayer.ExoPlayer.Builder(context).build()
        player = exoPlayer
        // Echte MediaLibrarySession (Robolectric): der Library-Callback-Typ
        // verlangt MediaLibrarySession, ein Stub-Objekt ist nicht moeglich.
        val librarySession = MediaLibrarySession.Builder(context, exoPlayer, cb).build()
        session = librarySession
        MediaLibrarySessionStub = librarySession
        return cb
    }

    private fun controller(): MediaSession.ControllerInfo =
        MediaSession.ControllerInfo.createTestOnlyControllerInfo(
            "com.dropsync",
            /* uid = */ 10_123,
            /* pid = */ 1,
            /* libVersion = */ 1,
            /* interfaceVersion = */ 1,
            /* trusted = */ false,
            /* connectionHints = */ Bundle.EMPTY,
            /* packageNameVerified = */ false,
        )

    private fun childrenOf(
        cb: PlaybackService.LibrarySessionCallback,
        parentId: String,
    ): List<androidx.media3.common.MediaItem> =
        cb.onGetChildren(
            MediaLibrarySessionStub,
            controller(),
            parentId,
            0,
            Int.MAX_VALUE,
            null,
        ).let { it.get() }.value.orEmpty()

    @Test
    fun `onGetLibraryRoot liefert das Wurzel-Item`() {
        val cb = callback()
        val result =
            cb.onGetLibraryRoot(
                MediaLibrarySessionStub,
                controller(),
                null,
            ).get()
        assertEquals(SessionResult.RESULT_SUCCESS, result.resultCode)
        val item = result.value
        assertEquals("root", item?.mediaId)
        assertEquals(true, item?.mediaMetadata?.isBrowsable)
        assertEquals(false, item?.mediaMetadata?.isPlayable)
    }

    @Test
    fun `Root-Kinder sind die vier Kategorien`() = runTest {
        val cb = callback()
        val children = childrenOf(cb, "root")
        assertEquals(listOf("cat_songs", "cat_albums", "cat_artists", "cat_folders"), children.map { it.mediaId })
        assertTrue(children.all { it.mediaMetadata.isBrowsable == true && it.mediaMetadata.isPlayable == false })
    }

    @Test
    fun `Kategorie Titel listet verfuegbare Songs als playable Items`() = runTest {
        val cb = callback()
        val children = childrenOf(cb, "cat_songs")
        assertEquals(1, children.size)
        assertEquals("7", children[0].mediaId)
        assertEquals(true, children[0].mediaMetadata.isPlayable)
        assertEquals(false, children[0].mediaMetadata.isBrowsable)
    }

    @Test
    fun `Kategorie Alben listet Alben und Unterordner mit Songs`() = runTest {
        val browse = FakeLibraryBrowseRepository()
        browse.albumsFlow.value = listOf(Album(title = "Night Drive", artist = null, trackCount = 2))
        browse.songsByAlbumMap["Night Drive"] = flowOf(listOf(song))
        val cb = callback(browseRepository = browse)

        val albums = childrenOf(cb, "cat_albums")
        assertEquals(listOf("album:Night Drive"), albums.map { it.mediaId })

        val songs = childrenOf(cb, "album:Night Drive")
        assertEquals(listOf("7"), songs.map { it.mediaId })
        assertEquals(true, songs[0].mediaMetadata.isPlayable)
    }

    @Test
    fun `Kategorie Interpreten listet Interpreten und Unterordner mit Songs`() = runTest {
        val browse = FakeLibraryBrowseRepository()
        browse.artistsFlow.value = listOf(Artist(name = "Nova Kane", trackCount = 1, albumCount = 1))
        browse.songsByArtistMap["Nova Kane"] = flowOf(listOf(song))
        val cb = callback(browseRepository = browse)

        val artists = childrenOf(cb, "cat_artists")
        assertEquals(listOf("artist:Nova Kane"), artists.map { it.mediaId })

        val songs = childrenOf(cb, "artist:Nova Kane")
        assertEquals(listOf("7"), songs.map { it.mediaId })
    }

    @Test
    fun `Kategorie Ordner listet Pfade und Unterordner mit Songs`() = runTest {
        val browse = FakeLibraryBrowseRepository()
        browse.foldersFlow.value = listOf(LibraryFolder(relativePath = "Music/", trackCount = 1))
        browse.songsByFolderMap["Music/"] = flowOf(listOf(song))
        val cb = callback(browseRepository = browse)

        val folders = childrenOf(cb, "cat_folders")
        assertEquals(listOf("folder:Music/"), folders.map { it.mediaId })

        val songs = childrenOf(cb, "folder:Music/")
        assertEquals(listOf("7"), songs.map { it.mediaId })
    }

    @Test
    fun `Unbekannte parentId liefert leere Liste`() = runTest {
        val cb = callback()
        assertTrue(childrenOf(cb, "gibberish").isEmpty())
    }

    @Test
    fun `onPlaybackResumption stellt Queue und Position wieder her`() = runTest {
        val state =
            PersistedPlayerState(
                queueSongIds = listOf(7L, 8L),
                currentSongId = 8L,
                positionMs = 42_000L,
                shuffleEnabled = false,
                repeatMode = RepeatMode.OFF,
            )
        val cb =
            callback(
                librarySongs = listOf(song, song.copy(mediaStoreId = 8L, title = "Second Track")),
                stateStore = FakePlayerStateStore(state),
            )
        val resumed =
            cb.onPlaybackResumption(
                MediaLibrarySessionStub,
                controller(),
            ).get()
        assertEquals(2, resumed.mediaItems.size)
        assertEquals("8", resumed.mediaItems[1].mediaId)
        assertEquals(1, resumed.startIndex)
        assertEquals(42_000L, resumed.startPositionMs)
    }

    @Test
    fun `onPlaybackResumption ohne gespeicherten Zustand wirft`() = runTest {
        val cb = callback()
        val error =
            runCatching {
                cb.onPlaybackResumption(
                    MediaLibrarySessionStub,
                    controller(),
                ).get()
            }.exceptionOrNull()
        assertTrue(error != null)
    }
}

/** Schmale Library-Repository-Fassade fuer den Browse-Test. */
private class FakeLibraryRepository(
    librarySongs: List<Song>,
) : com.dropsync.domain.library.LibraryRepository {
    private val librarySongs: List<Song> = librarySongs

    override suspend fun getSong(mediaStoreId: Long): com.dropsync.core.common.AppResult<Song> =
        librarySongs
            .firstOrNull { it.mediaStoreId == mediaStoreId }
            ?.let { com.dropsync.core.common.AppResult.Success(it) }
            ?: com.dropsync.core.common.AppResult.Failure(com.dropsync.core.common.AppError.Unknown(debugMessage = null))

    override val songs = flowOf(librarySongs)

    override val availableSongs = flowOf(librarySongs.filter { it.isAvailable })

    override suspend fun refreshLibrary(force: Boolean) =
        com.dropsync.core.common.AppResult.Success(
            com.dropsync.domain.library.LibraryScanResult(false, 0, 0, 0),
        )

    override suspend fun markUnavailable(mediaStoreId: Long) = com.dropsync.core.common.AppResult.Success(Unit)

    override suspend fun importCueSheet(
        songId: Long,
        cueText: String,
    ) = com.dropsync.core.common.AppResult.Success(0)

    override fun observeCueTracks(songId: Long) = flowOf(emptyList<com.dropsync.domain.library.CueVirtualTrack>())

    override suspend fun scanFolder(treeUri: String) =
        com.dropsync.core.common.AppResult.Success(com.dropsync.domain.library.FolderScanResult(0, 0, 0, 0))

    override val scannedFiles = flowOf(emptyList<com.dropsync.domain.library.ScannedFile>())
}

/** MediaLibrarySession-Stub fuer direkte Callback-Aufrufe (siehe oben). */
private lateinit var MediaLibrarySessionStub: MediaLibrarySession
