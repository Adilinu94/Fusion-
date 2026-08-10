package com.dropsync.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.dropsync.core.designsystem.icon.BrandIcons
import com.dropsync.feature.audio.AudioSettingsScreen
import com.dropsync.feature.library.LibraryScreen
import com.dropsync.feature.player.MiniPlayer
import com.dropsync.feature.player.NowPlayingScreen
import com.dropsync.feature.settings.SettingsScreen
import com.dropsync.feature.workout.CalibrationWizardScreen
import com.dropsync.feature.workout.TrainScreen

/**
 * Hauptnavigation mit vier Zielen (Fusion-Design 2026-08-07):
 * Train (Start), Music, Verlauf, Einstellungen. Kompakt: Bottom Navigation;
 * ab Medium: Navigation Rail per Window Size Classes.
 */
enum class TopLevelDestination(
    val route: String,
    val iconRes: Int,
    val labelRes: Int,
) {
    TRAIN("train", BrandIcons.NavTrain, R.string.nav_train),
    MUSIC("music", BrandIcons.NavMusic, R.string.nav_music),
    HISTORY("history", BrandIcons.NavHistory, R.string.nav_history),
    SETTINGS("settings", BrandIcons.NavSettings, R.string.nav_settings),
}

/**
 * Unterseite der Einstellungen (kein viertes Hauptziel): Audio/DSP-Regler.
 * Erreichbar ueber den Audio-Einstieg in [SettingsScreen].
 */
private const val ROUTE_AUDIO_SETTINGS = "audio_settings"

/**
 * Now-Playing-Screen (Marker/Waveform-Plan Phase 1), erreichbar per Tap
 * auf den Mini-Player; kein viertes Hauptziel.
 */
private const val ROUTE_NOW_PLAYING = "now_playing"

/** Guided-Calibration-Wizard (Phase 4 Schritt 3), aus dem Train-Tab. */
private const val ROUTE_CALIBRATION = "calibration/{exerciseId}/{deviceId}"
private const val ARG_EXERCISE_ID = "exerciseId"
private const val ARG_DEVICE_ID = "deviceId"

@Composable
fun DropSyncApp(windowSizeClass: WindowSizeClass) {
    val navController = rememberNavController()
    val useRail = windowSizeClass.widthSizeClass != WindowWidthSizeClass.Compact

    if (useRail) {
        Row(modifier = Modifier.fillMaxSize()) {
            DropSyncNavigationRail(navController)
            DropSyncContent(navController, showBottomBar = false)
        }
    } else {
        DropSyncContent(navController, showBottomBar = true)
    }
}

@Composable
private fun DropSyncContent(
    navController: NavHostController,
    showBottomBar: Boolean,
) {
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        bottomBar = {
            Column {
                // Der aktive Mini-Player bleibt in der Shell sichtbar (12.2).
                MiniPlayer(
                    onOpenNowPlaying = { navController.openNowPlaying() },
                )
                if (showBottomBar) {
                    DropSyncNavigationBar(navController)
                }
            }
        },
    ) { innerPadding ->
        DropSyncNavHost(navController, innerPadding)
    }
}

@Composable
private fun DropSyncNavHost(
    navController: NavHostController,
    contentPadding: PaddingValues,
) {
    NavHost(
        navController = navController,
        startDestination = TopLevelDestination.TRAIN.route,
    ) {
        composable(TopLevelDestination.TRAIN.route) {
            // FlowRep Train-Tab: flaches Satz-Log (Phase 2).
            TrainScreen(
                contentPadding = contentPadding,
                onOpenCalibration = { exerciseId, deviceId ->
                    navController.navigate("calibration/$exerciseId/$deviceId")
                },
            )
        }
        composable(TopLevelDestination.MUSIC.route) {
            LibraryScreen(
                contentPadding = contentPadding,
                // Tap auf einen Titel oeffnet direkt den Now-Playing-Screen
                // (wie Poweramp), zusaetzlich zum Mini-Player-Tap.
                onOpenNowPlaying = { navController.openNowPlaying() },
            )
        }
        composable(TopLevelDestination.HISTORY.route) {
            // Verlauf: flache Liste Sätze zeitlich, Gruppe pro Tag,
            // Volumen Linie über Zeit, PR Abzeichen (Fusion-Design).
            // Vorläufig leerer Platzhalter bis Phase 9 (Verlauf Politur).
            Column(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(top = contentPadding.calculateTopPadding()),
            ) {
                Text(
                    text = "Verlauf",
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.padding(16.dp),
                )
                Text(
                    text = "Noch keine Sätze aufgezeichnet.",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
        }
        composable(TopLevelDestination.SETTINGS.route) {
            SettingsScreen(
                contentPadding = contentPadding,
                onOpenAudioSettings = { navController.navigate(ROUTE_AUDIO_SETTINGS) },
            )
        }
        composable(ROUTE_AUDIO_SETTINGS) {
            AudioSettingsScreen(
                contentPadding = contentPadding,
                onBack = { navController.popBackStack() },
            )
        }
        composable(ROUTE_NOW_PLAYING) {
            NowPlayingScreen(
                contentPadding = contentPadding,
                onBack = { navController.popBackStack() },
            )
        }
        composable(
            route = ROUTE_CALIBRATION,
            arguments =
                listOf(
                    navArgument(ARG_EXERCISE_ID) { type = NavType.LongType },
                    navArgument(ARG_DEVICE_ID) { type = NavType.StringType },
                ),
        ) { backStackEntry ->
            CalibrationWizardScreen(
                exerciseId = backStackEntry.arguments?.getLong(ARG_EXERCISE_ID) ?: return@composable,
                deviceId = backStackEntry.arguments?.getString(ARG_DEVICE_ID) ?: return@composable,
                contentPadding = contentPadding,
                onFinished = { navController.popBackStack() },
            )
        }
    }
}

@Composable
private fun DropSyncNavigationBar(navController: NavHostController) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    NavigationBar {
        TopLevelDestination.entries.forEach { destination ->
            val label = stringResource(destination.labelRes)
            NavigationBarItem(
                selected = currentRoute == destination.route,
                onClick = { navController.navigateTopLevel(destination.route) },
                icon = { Icon(painterResource(destination.iconRes), contentDescription = null) },
                label = { Text(label) },
                colors =
                    NavigationBarItemDefaults.colors(
                        // Lime-Pill als Active-State (Design.txt: Lime nur
                        // fuer die aktive/primaere Stelle).
                        indicatorColor = MaterialTheme.colorScheme.primary,
                        selectedIconColor = MaterialTheme.colorScheme.onPrimary,
                    ),
            )
        }
    }
}

@Composable
private fun DropSyncNavigationRail(navController: NavHostController) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    NavigationRail {
        TopLevelDestination.entries.forEach { destination ->
            val label = stringResource(destination.labelRes)
            NavigationRailItem(
                selected = currentRoute == destination.route,
                onClick = { navController.navigateTopLevel(destination.route) },
                icon = { Icon(painterResource(destination.iconRes), contentDescription = null) },
                label = { Text(label) },
                colors =
                    NavigationRailItemDefaults.colors(
                        indicatorColor = MaterialTheme.colorScheme.primary,
                        selectedIconColor = MaterialTheme.colorScheme.onPrimary,
                    ),
            )
        }
    }
}

/** Standard-Navigationsmuster: ein Backstack-Eintrag je Top-Level-Ziel. */
private fun NavHostController.navigateTopLevel(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

/**
 * Oeffnet den Now-Playing-Screen; [launchSingleTop] verhindert, dass
 * wiederholte Titel-Taps mehrere identische Eintraege stapeln.
 */
private fun NavHostController.openNowPlaying() {
    navigate(ROUTE_NOW_PLAYING) { launchSingleTop = true }
}
