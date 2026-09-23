package com.dropsync.feature.progress

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dropsync.core.designsystem.theme.FlowRepTheme
import com.dropsync.core.testing.FakeFlatSetRepository
import com.dropsync.core.testing.FakeTargetRepository
import com.dropsync.core.testing.FakeWorkoutGoalRepository
import com.dropsync.core.testing.FakeWorkoutRepository
import kotlinx.coroutines.flow.flow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * D4 (Welle 2): Leer- und Fehlerzustand des Verlauf-Dashboards — der
 * Leerzustand ist Onboarding mit genau einer Lime-Flaeche (R7), der
 * Ladefehler nennt den Grund und laedt per Retry neu (C6/U-7).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ProgressEmptyErrorTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `leerer verlauf ist onboarding mit training-knopf`() {
        var opened = 0
        compose.setContent {
            FlowRepTheme {
                ProgressDashboardContent(
                    state = ProgressDashboardUiState(),
                    contentPadding = PaddingValues(),
                    onOpenTraining = { opened++ },
                    onOpenAllSets = {},
                    onOpenExerciseLibrary = {},
                )
            }
        }

        compose.onNodeWithText("No training logged yet").assertIsDisplayed()
        compose
            .onNodeWithText(
                "After your first set, FlowRep shows your volume, records and exercise history here.",
            ).assertIsDisplayed()
        compose.onNodeWithText("Open training").assertIsDisplayed().performClick()
        assertEquals(1, opened)
    }

    @Test
    fun `ladefehler wird sichtbar und retry laedt neu`() {
        var fail = true
        val flatSets = FakeFlatSetRepository()
        flatSets.allSetsFlow =
            flow {
                if (fail) throw IllegalStateException("db kaputt")
                emit(emptyList())
            }
        val vm =
            ProgressViewModel(
                flatSetRepository = flatSets,
                workoutRepository = FakeWorkoutRepository(),
                workoutGoalRepository = FakeWorkoutGoalRepository(),
                targetRepository = FakeTargetRepository(),
                appContext = ApplicationProvider.getApplicationContext(),
            )
        compose.setContent {
            FlowRepTheme {
                ProgressDashboardScreen(
                    contentPadding = PaddingValues(),
                    onOpenTraining = {},
                    onOpenAllSets = {},
                    onOpenExerciseLibrary = {},
                    viewModel = vm,
                )
            }
        }

        awaitText("Could not load progress")
        // Der Retry baut den Flow neu auf; danach ist der Leerzustand da.
        fail = false
        compose.onNodeWithText("Retry").performClick()
        awaitText("No training logged yet")
    }

    private fun awaitText(text: String) {
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
    }
}
