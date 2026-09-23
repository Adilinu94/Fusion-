package com.dropsync.feature.settings

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
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
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * D4 (Welle 2): Die Einstellungen zeigen ihre Sektionen — Pausen-Musik und
 * DropSync-Zuordnung oben, dann Design, Audio, Daten, Marker, Datenschutz
 * und der Entwicklerbereich. Die Zustaende kommen aus dem echten
 * `SettingsViewModel` mit Fakes (kein Mock der UI).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class SettingsSectionsTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `einstellungen zeigen alle sektionen`() {
        val vm = viewModel()
        compose.setContent {
            FlowRepTheme {
                SettingsScreen(contentPadding = PaddingValues(), viewModel = vm)
            }
        }

        // Die Sektionen stehen untereinander in der LazyColumn; die letzte
        // ist nur nach dem Scrollen komponiert.
        val list = compose.onNode(hasScrollToIndexAction())
        listOf(
            "Music during breaks",
            "DropSync",
            "Appearance",
            "Audio",
            "Data",
            "Song markers",
            "Privacy",
            "Developer",
        ).forEach { title ->
            list.performScrollToNode(hasText(title))
            compose.onNodeWithText(title).assertIsDisplayed()
        }
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
