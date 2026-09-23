package com.dropsync.feature.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dropsync.core.designsystem.theme.FlowRepTheme
import com.dropsync.core.testing.FakeLibraryBrowseRepository
import com.dropsync.core.testing.FakeMarkerRepository
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Paket 5 (Befund 7.1.5): Die Bibliotheks-Startseite (Musik-Tab) ist der
 * fuenfte grosse Screen im Screenshot-Gate — die DropSync-Karten zeigen den
 * leeren Erststart-Zustand (keine Playlists, keine Titel). Das ViewModel
 * laeuft wie in [LibraryViewModelTest] mit Fakes.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = RobolectricDeviceQualifiers.Pixel6)
class LibraryScreenshotsTest {
    @get:Rule
    val compose = createComposeRule()

    private val dispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun bibliothekHell() {
        compose.setContent {
            FlowRepTheme(darkTheme = false) {
                Column(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.background),
                ) {
                    LibraryContent(
                        viewModel = viewModel(),
                        contentPadding = PaddingValues(),
                        scanFailed = false,
                        onOpenNowPlaying = {},
                    )
                }
            }
        }
        compose.onRoot().captureRoboImage()
    }

    private fun viewModel() =
        LibraryViewModel(
            libraryRepository = FakeLibraryRepository(),
            browseRepository = FakeLibraryBrowseRepository(),
            playbackRepository = FakePlaybackRepository(),
            viewPreferences = FakeViewPreferences(),
            trackAnalysisRepository = FakeTrackAnalysisRepository(),
            folderFilter = FakeMusicFolderFilter(),
            markerRepository = FakeMarkerRepository(),
        )
}
