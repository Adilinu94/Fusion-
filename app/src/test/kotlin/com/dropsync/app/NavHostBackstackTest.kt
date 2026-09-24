package com.dropsync.app

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Paket 5 (Befund 8.5): Backstack-Vertraege der Shell als echter
 * NavHost-Graph auf Robolectric. Die Screens sind bewusst Stubs — geprueft
 * wird die Navigation (Top-Level-Umschalten mit State-Restore, Unterseiten
 * mit korrektem Pop, Kalibrierungs-Argumente), nicht die Screen-Inhalte.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class NavHostBackstackTest {
    @get:Rule
    val compose = createComposeRule()

    /** Aufgerufene Stub-Screens (Reihenfolge = Navigationshistorie). */
    private val visited = mutableListOf<String>()

    @Composable
    private fun Stub(route: String) {
        visited.add(route)
        Text(route)
    }

    /**
     * Spiegelbild des echten Graphen aus [DropSyncApp] mit denselben
     * Routenkonstanten und denselben Optionen (launchSingleTop,
     * navigateTopLevel-Verhalten); Start ist der Music-Tab.
     */
    @Composable
    private fun TestGraph(navController: NavHostController) {
        NavHost(
            navController = navController,
            startDestination = TopLevelDestination.MUSIC.route,
            enterTransition = { EnterTransition.None },
            exitTransition = { ExitTransition.None },
            popEnterTransition = { EnterTransition.None },
            popExitTransition = { ExitTransition.None },
        ) {
            composable(TopLevelDestination.MUSIC.route) { Stub("music") }
            composable(TopLevelDestination.TRAIN.route) { Stub("train") }
            composable(TopLevelDestination.HISTORY.route) { Stub("history") }
            composable(TopLevelDestination.SETTINGS.route) { Stub("settings") }
            composable(ROUTE_ALL_SETS) { Stub("allSets") }
            composable(ROUTE_EXERCISE_LIBRARY) { Stub("exerciseLibrary") }
            composable(ROUTE_AUDIO_SETTINGS) { Stub("audioSettings") }
            composable(ROUTE_TIMER) { Stub("timer") }
            composable(ROUTE_ONBOARDING) { Stub("onboarding") }
            composable(ROUTE_NOW_PLAYING) { Stub("nowPlaying") }
            composable(
                route = ROUTE_CALIBRATION,
                arguments =
                    listOf(
                        navArgument(ARG_EXERCISE_ID) { type = NavType.LongType },
                        navArgument(ARG_DEVICE_ID) { type = NavType.StringType },
                    ),
            ) { Stub("calibration") }
        }
    }

    @Test
    fun `unterseite poppt zurueck auf den aufrufenden Tab`() {
        val navHolder =
            java.util.concurrent.atomic
                .AtomicReference<NavHostController>()
        compose.setContent {
            val navController = rememberNavController()
            navHolder.set(navController)
            TestGraph(navController)
        }
        compose.waitForIdle()
        compose.runOnIdle {
            requireNotNull(navHolder.get()).navigate(ROUTE_ALL_SETS) { launchSingleTop = true }
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(ROUTE_ALL_SETS, requireNotNull(navHolder.get()).currentBackStackEntry?.destination?.route)
            requireNotNull(navHolder.get()).popBackStack()
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(
                TopLevelDestination.MUSIC.route,
                requireNotNull(navHolder.get()).currentBackStackEntry?.destination?.route,
            )
        }
    }

    @Test
    fun `kalibrierungsargumente kommen am ziel an`() {
        val navHolder =
            java.util.concurrent.atomic
                .AtomicReference<NavHostController>()
        compose.setContent {
            val navController = rememberNavController()
            navHolder.set(navController)
            TestGraph(navController)
        }
        compose.waitForIdle()
        compose.runOnIdle {
            requireNotNull(navHolder.get()).navigate(calibrationRoute(exerciseId = 42L, deviceId = "polar-h10"))
        }
        compose.waitForIdle()
        compose.runOnIdle {
            val entry = requireNotNull(navHolder.get()).currentBackStackEntry
            assertEquals(ROUTE_CALIBRATION, entry?.destination?.route)
            assertEquals(42L, entry?.arguments?.getLong(ARG_EXERCISE_ID))
            assertEquals("polar-h10", entry?.arguments?.getString(ARG_DEVICE_ID))
        }
    }

    @Test
    fun `now-playing ist single-top und kein top-level-ziel`() {
        val navHolder =
            java.util.concurrent.atomic
                .AtomicReference<NavHostController>()
        compose.setContent {
            val navController = rememberNavController()
            navHolder.set(navController)
            TestGraph(navController)
        }
        compose.waitForIdle()
        compose.runOnIdle {
            requireNotNull(navHolder.get()).openNowPlaying()
            requireNotNull(navHolder.get()).openNowPlaying()
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(ROUTE_NOW_PLAYING, requireNotNull(navHolder.get()).currentBackStackEntry?.destination?.route)
            // Genau EIN Now-Playing-Eintrag im Backstack (launchSingleTop).
            val count =
                requireNotNull(navHolder.get())
                    .currentBackStack.value
                    .count { it.destination.route == ROUTE_NOW_PLAYING }
            assertEquals(1, count)
            assertTrue(ROUTE_NOW_PLAYING !in TopLevelDestination.entries.map { it.route })
        }
    }

    @Test
    fun `navigateTopLevel schliesst unterseiten und restauriert den tab`() {
        val navHolder =
            java.util.concurrent.atomic
                .AtomicReference<NavHostController>()
        compose.setContent {
            val navController = rememberNavController()
            navHolder.set(navController)
            TestGraph(navController)
        }
        compose.waitForIdle()
        compose.runOnIdle {
            requireNotNull(navHolder.get()).navigate(ROUTE_TIMER) { launchSingleTop = true }
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(ROUTE_TIMER, requireNotNull(navHolder.get()).currentBackStackEntry?.destination?.route)
            // Tab-Wechsel aus einer Unterseite: popUpTo Start raeumt die
            // Unterseite weg, der Ziel-Tab liegt oben.
            requireNotNull(navHolder.get()).navigateTopLevel(TopLevelDestination.SETTINGS.route)
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(
                TopLevelDestination.SETTINGS.route,
                requireNotNull(navHolder.get()).currentBackStackEntry?.destination?.route,
            )
            // Timer ist weg (popUpTo Start), Settings liegt auf dem Start-Tab.
            val routes =
                requireNotNull(navHolder.get()).currentBackStack.value.map { it.destination.route }
            assertTrue(ROUTE_TIMER !in routes)
            assertTrue(routes.contains(TopLevelDestination.MUSIC.route))
        }
    }
}
