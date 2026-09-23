package com.dropsync.feature.progress

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dropsync.core.designsystem.theme.FlowRepTheme
import com.dropsync.domain.workout.FlatSet
import com.dropsync.domain.workout.PrRecord
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureRoboImage
import java.util.Calendar
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Paket 5 (Befund 7.1.5): Das Bento-Dashboard (Verlauf-Tab) ist der zweite
 * grosse Screen im Screenshot-Gate. Der Zustand ist deterministisch
 * aufgebaut (feste Woche, feste Saetze) — jede unbeabsichtigte
 * Verschiebung (Ring, Chart, Feed) faellt im CI-Gate.
 *
 * Referenzen liegen unter `src/test/screenshots/` und werden per
 * `recordRoborazziDebug` erneuert (nur bei beabsichtigtem Redesign).
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = RobolectricDeviceQualifiers.Pixel6)
class ProgressDashboardScreenshotsTest {
    @get:Rule
    val compose = createComposeRule()

    /** Feste Bezugszeit (Mittwoch, 12:00) — kein Datumsexport in die PNG. */
    private fun fixedNow(): Calendar =
        Calendar.getInstance().apply {
            set(2026, Calendar.SEPTEMBER, 16, 12, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }

    private fun setAt(
        dayOffsetFromMonday: Int,
        weightMilliKg: Long,
        reps: Int,
    ): FlatSet {
        val monday =
            fixedNow().apply {
                firstDayOfWeek = Calendar.MONDAY
                set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
                set(Calendar.HOUR_OF_DAY, 10)
                set(Calendar.MINUTE, 0)
            }
        monday.add(Calendar.DAY_OF_YEAR, dayOffsetFromMonday)
        return FlatSet(
            id = dayOffsetFromMonday.toLong(),
            exerciseId = 1L,
            weightMilliKg = weightMilliKg,
            reps = reps,
            loggedAtEpochMs = monday.timeInMillis,
        )
    }

    private fun dashboardState(): ProgressDashboardUiState {
        val now = fixedNow()
        val sets =
            listOf(
                setAt(0, 80_000, 8),
                setAt(0, 82_500, 8),
                setAt(1, 80_000, 10),
                setAt(3, 85_000, 6),
                setAt(7, 77_500, 10),
                setAt(7, 80_000, 8),
                setAt(14, 75_000, 10),
            )
        val records =
            listOf(
                PrRecord(
                    exerciseId = 1L,
                    type = com.dropsync.core.model.PrType.HIGHEST_LOAD,
                    achievedSessionId = null,
                    achievedClusterId = null,
                    valueLong = 102_000,
                    valueUnit = com.dropsync.core.model.PrValueUnit.MILLI_KG,
                    comparableLoadMilliKg = 102_000,
                    achievedAtEpochMs = setAt(0, 0, 0).loggedAtEpochMs,
                ),
            )
        return ProgressDashboardUiState(
            progress = ProgressUiState.from(sets, now),
            feed = ProgressFeedUiState.from(sets, mapOf(1L to "Bankdruecken"), now, "Uebung", records),
            goals = ProgressGoalsUiState.Empty,
        )
    }

    @Test
    fun dashboardHell() {
        compose.setContent {
            FlowRepTheme(darkTheme = false) {
                Column(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.background),
                ) {
                    ProgressDashboardContent(
                        state = dashboardState(),
                        contentPadding = PaddingValues(0.dp),
                        onOpenTraining = {},
                        onOpenAllSets = {},
                        onOpenExerciseLibrary = {},
                    )
                }
            }
        }
        compose.onRoot().captureRoboImage()
    }

    @Test
    fun dashboardDunkel() {
        compose.setContent {
            FlowRepTheme(darkTheme = true) {
                Column(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.background),
                ) {
                    ProgressDashboardContent(
                        state = dashboardState(),
                        contentPadding = PaddingValues(0.dp),
                        onOpenTraining = {},
                        onOpenAllSets = {},
                        onOpenExerciseLibrary = {},
                    )
                }
            }
        }
        compose.onRoot().captureRoboImage()
    }
}
