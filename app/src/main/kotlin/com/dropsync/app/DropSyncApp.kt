package com.dropsync.app

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
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
import com.dropsync.feature.progress.AllSetsScreen
import com.dropsync.feature.progress.ProgressDashboardScreen
import com.dropsync.feature.settings.SettingsScreen
import com.dropsync.feature.workout.CalibrationWizardScreen
import com.dropsync.feature.workout.ExerciseLibraryScreen
import com.dropsync.feature.workout.TrainScreen

/**
 * Hauptnavigation mit vier Zielen (Fusion-Design 2026-08-07):
 * Music (Start), Train, Verlauf, Einstellungen. Kompakt: Bottom Navigation;
 * ab Medium: Navigation Rail per Window Size Classes.
 */
enum class TopLevelDestination(
    val route: String,
    val iconRes: Int,
    val labelRes: Int,
) {
    MUSIC("music", BrandIcons.NavMusic, R.string.nav_music),
    TRAIN("train", BrandIcons.NavTrain, R.string.nav_train),
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

/**
 * Alle-Saetze-Route hinter dem Progress-Dashboard (UI-Vertrag Verlauf):
 * die volle Satz-Liste als eigene Route, Android-Back gilt normal.
 */
private const val ROUTE_ALL_SETS = "progress/all_sets"

/**
 * Uebungsbibliothek (Schritt 7): Ort der Uebungs- und Ziel-Pflege
 * (Entscheidung 13), erreichbar vom Train-Tab und dem Dashboard.
 */
private const val ROUTE_EXERCISE_LIBRARY = "exercise_library"

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
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    // Now-Playing ist ein chromeloser Vollbild-Moment (Poweramp-Optik):
    // Mini-Player + Bottom-Nav werden dort ausgeblendet, damit das Cover
    // im echten Viewport zentriert werden kann und keine doppelten
    // Transport-Controls erscheinen.
    val immersive = currentRoute == ROUTE_NOW_PLAYING
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        bottomBar = {
            if (!immersive) {
                Column {
                    // Der aktive Mini-Player bleibt in der Shell sichtbar (12.2).
                    MiniPlayer(
                        onOpenNowPlaying = { navController.openNowPlaying() },
                    )
                    if (showBottomBar) {
                        FlowRepGlassNavigation(navController)
                    }
                }
            }
        },
    ) { innerPadding ->
        DropSyncNavHost(
            navController,
            if (immersive) PaddingValues() else innerPadding,
        )
    }
}

@Composable
private fun DropSyncNavHost(
    navController: NavHostController,
    contentPadding: PaddingValues,
) {
    NavHost(
        navController = navController,
        startDestination = TopLevelDestination.MUSIC.route,
        // Now-Playing als Sheet-Moment: von unten aufsteigend, zurueck
        // gleitend; alle anderen Ziele bleiben bei weichem Fade (kein
        // Slide-Wettlauf mit der Tab-Pille). Echte Shared-Element-Transition
        // MiniPlayer->Now-Playing benoetigt den Player als Overlay/Sheet in
        // der Shell statt einer Route (Movement-Scope erreicht den Mini-
        // Player ausserhalb des NavHost nicht) — als Folgearbeit notiert.
        enterTransition = {
            if (targetState.destination.route == ROUTE_NOW_PLAYING) {
                slideInVertically(
                    animationSpec =
                        spring(
                            dampingRatio = Spring.DampingRatioLowBouncy,
                            stiffness = Spring.StiffnessMediumLow,
                        ),
                    initialOffsetY = { it },
                ) + fadeIn()
            } else {
                fadeIn()
            }
        },
        exitTransition = { fadeOut() },
        popEnterTransition = { fadeIn() },
        popExitTransition = {
            if (targetState.destination.route == ROUTE_NOW_PLAYING) {
                slideOutVertically(
                    animationSpec =
                        spring(
                            dampingRatio = Spring.DampingRatioNoBouncy,
                            stiffness = Spring.StiffnessMediumLow,
                        ),
                    targetOffsetY = { it },
                ) + fadeOut()
            } else {
                fadeOut()
            }
        },
    ) {
        composable(TopLevelDestination.TRAIN.route) {
            // FlowRep Train-Tab: flaches Satz-Log (Phase 2).
            TrainScreen(
                contentPadding = contentPadding,
                onOpenCalibration = { exerciseId, deviceId ->
                    navController.navigate("calibration/$exerciseId/$deviceId")
                },
                onOpenLibrary = { navController.navigate(ROUTE_EXERCISE_LIBRARY) { launchSingleTop = true } },
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
            // Verlauf-Tab ist das Bento-Dashboard (Flowtimer-Integration 6d-2);
            // die Rohdaten stehen hinter der Alle-Saetze-Route.
            ProgressDashboardScreen(
                contentPadding = contentPadding,
                onOpenTraining = { navController.navigateTopLevel(TopLevelDestination.TRAIN.route) },
                onOpenAllSets = { navController.navigate(ROUTE_ALL_SETS) { launchSingleTop = true } },
                onOpenExerciseLibrary = { navController.navigate(ROUTE_EXERCISE_LIBRARY) { launchSingleTop = true } },
            )
        }
        composable(ROUTE_ALL_SETS) {
            AllSetsScreen(
                contentPadding = contentPadding,
                onBack = { navController.popBackStack() },
            )
        }
        composable(ROUTE_EXERCISE_LIBRARY) {
            ExerciseLibraryScreen(
                contentPadding = contentPadding,
                // TODO Flowtimer-Integration DB v9 (Schritt 7 Fortsetzung):
                // Der Tap auf eine Uebung oeffnet den Ziel-Dialog, sobald
                // TargetEntity existiert. Bis dahin fuehrt der Tap zurueck.
                onOpenExercise = { navController.popBackStack() },
                onBack = { navController.popBackStack() },
            )
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
private fun FlowRepGlassNavigation(navController: NavHostController) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val destinations = TopLevelDestination.entries
    val selectedIndex =
        destinations
            .indexOfFirst { it.route == currentRoute }
            .coerceAtLeast(0)
    val pillShape = RoundedCornerShape(50)
    val containerColor = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.92f)
    val borderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)

    BoxWithConstraints(
        modifier =
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .shadow(elevation = 12.dp, shape = pillShape, clip = false)
                .clip(pillShape)
                .background(containerColor)
                .border(1.dp, borderColor, pillShape),
    ) {
        val tabWidth = maxWidth / destinations.size
        // Gleitender Indikator: Feder-Physik laesst die Pille sichtbar von
        // einem Tab zum naechsten gleiten (kein harter Sprung). Etwas
        // Bounce, damit der Wechsel modern und lebendig wirkt.
        val indicatorOffset by animateDpAsState(
            targetValue = tabWidth * selectedIndex,
            animationSpec =
                spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessMediumLow,
                ),
            label = "bottom-nav-indicator",
        )

        Box(
            modifier =
                Modifier
                    .offset(x = indicatorOffset)
                    .width(tabWidth)
                    .height(72.dp)
                    .padding(4.dp)
                    .clip(pillShape)
                    .background(MaterialTheme.colorScheme.primary),
        )

        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(72.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            destinations.forEachIndexed { index, destination ->
                val label = stringResource(destination.labelRes)
                val selected = index == selectedIndex
                // Leichter Pop auf dem aktiven Icon, passend zur gleitenden
                // Pille; inaktive Icons bleiben ruhig.
                val iconScale by animateFloatAsState(
                    targetValue = if (selected) 1.12f else 1f,
                    animationSpec =
                        spring(
                            dampingRatio = Spring.DampingRatioMediumBouncy,
                            stiffness = Spring.StiffnessMedium,
                        ),
                    label = "bottom-nav-icon-scale",
                )
                Column(
                    modifier =
                        Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .padding(vertical = 6.dp)
                            .semantics {
                                stateDescription = if (selected) "Ausgewählt" else "Nicht ausgewählt"
                            }.clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                role = Role.Tab,
                                onClick = { navController.navigateTopLevel(destination.route) },
                            ),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
                ) {
                    Icon(
                        painter = painterResource(destination.iconRes),
                        contentDescription = null,
                        modifier =
                            Modifier
                                .size(30.dp)
                                .graphicsLayer {
                                    scaleX = iconScale
                                    scaleY = iconScale
                                },
                        tint =
                            if (selected) {
                                MaterialTheme.colorScheme.onPrimary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelSmall,
                        color =
                            if (selected) {
                                MaterialTheme.colorScheme.onPrimary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                    )
                }
            }
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
