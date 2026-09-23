package com.dropsync.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dropsync.core.designsystem.theme.FlowRepTheme
import com.dropsync.core.testing.FakeFlatSetRepository
import com.dropsync.core.testing.FakeLibraryBrowseRepository
import com.dropsync.core.testing.FakeRestTimerPreferencesRepository
import com.dropsync.core.testing.FakeSensorProvider
import com.dropsync.core.testing.FakeSetDiagnosticsLog
import com.dropsync.core.testing.FakeWorkoutRepository
import com.dropsync.core.testing.TestDispatcherProvider
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Paket 5 (Befund 7.1.5): Die Einstellungen sind der dritte grosse Screen
 * im Screenshot-Gate (hell). Das ViewModel laeuft wie in
 * [SettingsSectionsTest] mit Fakes; Referenzen unter `src/test/screenshots/`.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = RobolectricDeviceQualifiers.Pixel6)
class SettingsScreenshotsTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun settingsHell() {
        compose.setContent {
            FlowRepTheme(darkTheme = false) {
                Column(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.background),
                ) {
                    SettingsScreen(
                        contentPadding = PaddingValues(),
                        viewModel = viewModel(),
                    )
                }
            }
        }
        compose.onRoot().captureRoboImage()
    }

    private fun viewModel() =
        SettingsViewModel(
            context = ApplicationProvider.getApplicationContext(),
            markerRepository = FakeMarkerRepository(),
            libraryRepository = FakeLibraryRepository(),
            browseRepository = FakeLibraryBrowseRepository(),
            restMusicSettings = FakeRestMusicSettingsRepository(),
            themeSettings = FakeThemeSettingsRepository(),
            accentColorSettings = FakeAccentColorRepository(),
            restTimerPreferences = FakeRestTimerPreferencesRepository(),
            libraryViewPreferences = FakeLibraryViewPreferencesRepository(),
            audioEngine = FakeAudioEngineRepository(),
            flatSetRepository = FakeFlatSetRepository(),
            workoutRepository = FakeWorkoutRepository(),
            workoutGoalRepository = RecordingWorkoutGoalRepository(),
            heartRateSource = FakeHeartRateSourceForSettings(),
            debugSettings = FakeDebugSettingsRepository(),
            sensorProvider = FakeSensorProvider(),
            setDiagnosticsLog = FakeSetDiagnosticsLog(),
            dispatchers = TestDispatcherProvider(),
        )
}
