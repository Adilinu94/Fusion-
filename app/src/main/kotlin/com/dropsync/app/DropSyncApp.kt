package com.dropsync.app

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
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
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.dropsync.core.designsystem.icon.BrandIcons
import com.dropsync.core.designsystem.theme.LocalReducedMotion
import com.dropsync.core.designsystem.theme.LocalWindowSizeClass
import com.dropsync.core.designsystem.theme.isReducedMotion
import com.dropsync.feature.audio.AudioSettingsScreen
import com.dropsync.feature.library.LibraryScreen
import com.dropsync.feature.player.MiniPlayer
import com.dropsync.feature.player.NowPlayingScreen
import com.dropsync.feature.player.PlayerViewModel
import com.dropsync.feature.progress.AllSetsScreen
import com.dropsync.feature.progress.ProgressDashboardScreen
import com.dropsync.feature.settings.SettingsScreen
import com.dropsync.feature.timer.TimerScreen
import com.dropsync.feature.workout.CalibrationWizardScreen
import com.dropsync.feature.workout.ExerciseLibraryScreen
import com.dropsync.feature.workout.TrainScreen
import kotlinx.coroutines.delay

/**
 * Unterseite der Einstellungen (kein viertes Hauptziel): Audio/DSP-Regler.
 * Erreichbar ueber den Audio-Einstieg in [SettingsScreen].
 */
internal const val ROUTE_AUDIO_SETTINGS = "audio_settings"

/**
 * Standalone-Resttimer (B-ARCH-2 / P2-17, Nutzerentscheidung "Verdrahten"):
 * kein Hauptziel, erreichbar per Tap auf die Countdown-Anzeige der
 * Train-Pausenkonsole. Teilt sich die TimerEngine mit dem Train-Tab.
 */
internal const val ROUTE_TIMER = "timer"

/**
 * Now-Playing-Screen (Marker/Waveform-Plan Phase 1), erreichbar per Tap
 * auf den Mini-Player; kein viertes Hauptziel.
 */
internal const val ROUTE_NOW_PLAYING = "now_playing"

/**
 * Alle-Saetze-Route hinter dem Progress-Dashboard (UI-Vertrag Verlauf):
 * die volle Satz-Liste als eigene Route, Android-Back gilt normal.
 */
internal const val ROUTE_ALL_SETS = "progress/all_sets"

/**
 * Uebungsbibliothek (Schritt 7): Ort der Uebungs- und Ziel-Pflege
 * (Entscheidung 13), erreichbar vom Train-Tab und dem Dashboard.
 */
internal const val ROUTE_EXERCISE_LIBRARY = "exercise_library"

/** Guided-Calibration-Wizard (Phase 4 Schritt 3), aus dem Train-Tab. */
internal const val ROUTE_CALIBRATION = "calibration/{exerciseId}/{deviceId}"
internal const val ARG_EXERCISE_ID = "exerciseId"
internal const val ARG_DEVICE_ID = "deviceId"

/**
 * Befund 7.1.3: Einfuehrung erneut aufrufbar — dieselbe [OnboardingScreen]
 * wie beim First-Run, aber als NavHost-Route aus den Einstellungen (der
 * First-Run-Gate oben bleibt ausserhalb der Navigation).
 */
internal const val ROUTE_ONBOARDING = "onboarding"

@Composable
fun DropSyncApp(windowSizeClass: WindowSizeClass) {
    val navController = rememberNavController()
    val useRail = windowSizeClass.widthSizeClass != WindowWidthSizeClass.Compact
    // Reduced Motion: einmal am Activity-Kontext lesen und app-weit
    // bereitstellen (Befund: nur das Dashboard wertete den Systemwert aus).
    val reducedMotion = LocalContext.current.isReducedMotion()
    // B3: First-Run-Onboarding — null, solange DataStore laedt (kein Flackern
    // fuer bestehende Nutzer), danach genau einmal bis zum Abschluss.
    val onboardingViewModel: OnboardingViewModel = hiltViewModel()
    val onboardingSeen by onboardingViewModel.seen.collectAsStateWithLifecycle()
    // C2: Breakpoint fuer alle Screens bereitstellen (Now-Playing, Train,
    // Dashboard lesen ihn ueber `rememberWindowWidthSizeClass`).
    CompositionLocalProvider(
        LocalWindowSizeClass provides windowSizeClass,
        LocalReducedMotion provides reducedMotion,
    ) {
        when (onboardingSeen) {
            null -> {
                Box(modifier = Modifier.fillMaxSize())
            }

            false -> {
                OnboardingScreen(onFinish = onboardingViewModel::markSeen)
            }

            true -> {
                if (useRail) {
                    Row(modifier = Modifier.fillMaxSize()) {
                        DropSyncNavigationRail(navController)
                        DropSyncContent(navController, showBottomBar = false)
                    }
                } else {
                    DropSyncContent(navController, showBottomBar = true)
                }
            }
        }
    }
}

@Composable
private fun DropSyncContent(
    navController: NavHostController,
    showBottomBar: Boolean,
) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    // Im Now-Playing-Screen wird nur der Mini-Player ausgeblendet (der Screen
    // ist der Player). Die normale App-Navigation (Musik, Train, Verlauf,
    // Einstellungen) bleibt wie in der Bibliothek sichtbar — der Player
    // bringt keine eigene Leiste mit.
    val hideMiniPlayer = currentRoute == ROUTE_NOW_PLAYING

    // P0-Fix (EINE Player-Instanz): der Mini-Player lebt in der bottomBar,
    // also AUSSERHALB des NavHost, der Now-Playing-Screen INNERHALB einer
    // Route. `hiltViewModel()` loest `LocalViewModelStoreOwner` auf — dort die
    // Activity, hier die NavBackStackEntry. Beide Screens bekamen deshalb
    // getrennte PlayerViewModel-Objekte: doppelte init-Bloecke, doppelte
    // DB-Abfragen pro Player-Ereignis, doppelte Waveform-Analyse, und im
    // Player gesetzte Zustaende (Tempo, BPM-Lock, EQ) waren dem Mini-Player
    // unbekannt. Hier EINMAL aufgeloest (Activity-Owner) und an beide
    // uebergeben.
    val playerViewModel: PlayerViewModel = hiltViewModel()

    // Positions-Ticker in der Shell statt im Now-Playing-Screen: so laeuft
    // auch die Fortschrittsleiste des Mini-Players (vorher stand sie still,
    // weil `playbackRepository.state` die Position nur bei Player-Ereignissen
    // aktualisiert und der Ticker nur im Player lief).
    val miniPlayerState by playerViewModel.miniPlayer.collectAsStateWithLifecycle()
    val playerVisible = miniPlayerState.isVisible
    LaunchedEffect(playerVisible) {
        while (playerVisible) {
            playerViewModel.refreshPosition()
            delay(POSITION_TICK_MS)
        }
    }

    // B4: Ein Snackbar-Host fuer die ganze Shell (Undo nach Queue-/Marker-
    // Aktionen) — Screens zeigen darueber, kein eigener Host je Screen.
    val appSnackbar = remember { SnackbarHostState() }

    // C2 (5.10): Ein Skip waehrend eines scharfen Plans wird sichtbar
    // zurueckgenommen und laesst sich per Undo neu armieren. Das Ereignis
    // kommt aus dem ViewModel (auch fuer Bluetooth-/Queue-Skips).
    val skippedText = stringResource(R.string.app_dropsync_skipped)
    val undoText = stringResource(R.string.app_undo)
    LaunchedEffect(playerViewModel) {
        playerViewModel.skipOverridden.collect {
            val result =
                appSnackbar.showSnackbar(
                    message = skippedText,
                    actionLabel = undoText,
                    withDismissAction = true,
                )
            if (result == SnackbarResult.ActionPerformed) playerViewModel.undoOverride()
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(hostState = appSnackbar) },
        bottomBar = {
            Column {
                if (!hideMiniPlayer) {
                    // Der aktive Mini-Player bleibt in der Shell sichtbar (12.2).
                    MiniPlayer(
                        onOpenNowPlaying = { navController.openNowPlaying() },
                        viewModel = playerViewModel,
                        snackbarHostState = appSnackbar,
                    )
                }
                if (showBottomBar) {
                    FlowRepGlassNavigation(navController)
                }
            }
        },
    ) { innerPadding ->
        DropSyncNavHost(
            navController,
            innerPadding,
            playerViewModel,
            appSnackbar,
        )
    }
}

/** Kadenz des Positions-Tickers; 5 Hz reichen fuer eine fluessige Anzeige. */
private const val POSITION_TICK_MS = 200L

@Composable
private fun DropSyncNavHost(
    navController: NavHostController,
    contentPadding: PaddingValues,
    playerViewModel: PlayerViewModel,
    snackbarHostState: SnackbarHostState,
) {
    // In Transition-Lambdas ist kein @Composable-Kontext: Reduced-Motion
    // hier einmal vor dem NavHost einfangen.
    val reducedMotion = LocalReducedMotion.current
    NavHost(
        navController = navController,
        startDestination = TopLevelDestination.MUSIC.route,
        // Now-Playing als Sheet-Moment: von unten aufsteigend, zurueck
        // gleitend; alle anderen Ziele bleiben bei weichem Fade (kein
        // Slide-Wettlauf mit der Tab-Pille). Echte Shared-Element-Transition
        // MiniPlayer->Now-Playing benoetigt den Player als Overlay/Sheet in
        // der Shell statt einer Route (Movement-Scope erreicht den Mini-
        // Player ausserhalb des NavHost nicht) — als Folgearbeit notiert.
        enterTransition = { libraryEnterTransition(targetState.destination.route, reducedMotion) },
        exitTransition = { fadeOut() },
        popEnterTransition = { fadeIn() },
        popExitTransition = { libraryPopExitTransition(targetState.destination.route, reducedMotion) },
    ) {
        composable(TopLevelDestination.TRAIN.route) {
            // FlowRep Train-Tab: flaches Satz-Log (Phase 2).
            TrainScreen(
                contentPadding = contentPadding,
                onOpenCalibration = { exerciseId, deviceId ->
                    navController.navigate(calibrationRoute(exerciseId, deviceId))
                },
                onOpenLibrary = { navController.navigate(ROUTE_EXERCISE_LIBRARY) { launchSingleTop = true } },
                onOpenTimer = { navController.navigate(ROUTE_TIMER) { launchSingleTop = true } },
                snackbarHostState = snackbarHostState,
            )
        }
        composable(TopLevelDestination.MUSIC.route) {
            LibraryScreen(
                contentPadding = contentPadding,
                // Tap auf einen Titel oeffnet direkt den Now-Playing-Screen
                // (wie Poweramp), zusaetzlich zum Mini-Player-Tap.
                onOpenNowPlaying = { navController.openNowPlaying() },
                snackbarHostState = snackbarHostState,
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
                onBack = { navController.popBackStack() },
            )
        }
        composable(TopLevelDestination.SETTINGS.route) {
            SettingsScreen(
                contentPadding = contentPadding,
                onOpenAudioSettings = { navController.navigate(ROUTE_AUDIO_SETTINGS) },
                // B2: Timer-Einstieg aus den Einstellungen (kein fuenfter Tab).
                onOpenTimer = { navController.navigate(ROUTE_TIMER) { launchSingleTop = true } },
                // Befund 7.1.3: Einfuehrung erneut ansehen.
                onOpenOnboarding = { navController.navigate(ROUTE_ONBOARDING) },
            )
        }
        composable(ROUTE_AUDIO_SETTINGS) {
            AudioSettingsScreen(
                contentPadding = contentPadding,
                onBack = { navController.popBackStack() },
            )
        }
        composable(ROUTE_TIMER) {
            TimerScreen(
                contentPadding = contentPadding,
                onBack = { navController.popBackStack() },
            )
        }
        // Befund 7.1.3: Einfuehrung als Route (Android-Back gilt normal);
        // Fertig/Skip fuehrt zurueck, das First-Run-Flag bleibt gesetzt.
        composable(ROUTE_ONBOARDING) {
            OnboardingScreen(onFinish = { navController.popBackStack() })
        }
        composable(ROUTE_NOW_PLAYING) {
            NowPlayingScreen(
                contentPadding = contentPadding,
                onBack = { navController.popBackStack() },
                viewModel = playerViewModel,
                snackbarHostState = snackbarHostState,
                // C10 (P-10): Review-Liste liegt auf Music Home; der
                // Overflow verlinkt dorthin (Tab-Wechsel, kein neuer Screen).
                onOpenMarkerReview = {
                    navController.navigateTopLevel(TopLevelDestination.MUSIC.route)
                },
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
    // A5: Auf Sub-Routen (Timer, Now-Playing, ...) ist kein Tab aktiv — null
    // statt per coerceAtLeast(0) faelschlich „Musik" zu melden. Der Indikator
    // bleibt auf dem zuletzt gueltigen Tab stehen und blendet aus.
    val selectedIndex: Int? = destinations.indexOfFirst { it.route == currentRoute }.takeIf { it >= 0 }
    var indicatorIndex by remember { mutableIntStateOf(0) }
    LaunchedEffect(selectedIndex) {
        if (selectedIndex != null) indicatorIndex = selectedIndex
    }
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
        // Bounce, damit der Wechsel modern und lebendig wirkt. Bei Reduced
        // Motion springt die Pille ohne Feder (Ausbauplan Paket 0.3).
        val reducedMotion = LocalReducedMotion.current
        val indicatorOffset by animateDpAsState(
            targetValue = tabWidth * indicatorIndex,
            animationSpec = navIndicatorSpec(reducedMotion),
            label = "bottom-nav-indicator",
        )

        Box(
            modifier =
                Modifier
                    .offset { IntOffset(indicatorOffset.roundToPx(), 0) }
                    .width(tabWidth)
                    .height(72.dp)
                    .padding(4.dp)
                    .alpha(if (selectedIndex == null) 0f else 1f)
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
                val isSelected = index == selectedIndex
                GlassNavigationTab(
                    label = stringResource(destination.labelRes),
                    iconRes = destination.iconRes,
                    isSelected = isSelected,
                    reducedMotion = reducedMotion,
                    // TalkBack-Ansage aus den Ressourcen (B-UI-3): hartcodiert
                    // sprach die Navigation auch auf englischen Geraeten deutsch.
                    stateLabel =
                        stringResource(
                            if (isSelected) R.string.nav_state_selected else R.string.nav_state_not_selected,
                        ),
                    onClick = { navController.navigateTopLevel(destination.route) },
                )
            }
        }
    }
}

/** Indikator-Animation der Glas-Navigation; Reduced Motion springt ohne Feder. */
@Composable
private fun navIndicatorSpec(reducedMotion: Boolean): AnimationSpec<Dp> =
    if (reducedMotion) {
        snap()
    } else {
        spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow,
        )
    }

/** Ein Tab der Glas-Navigation inkl. Icon-Pop und TalkBack-Zustand. */
@Composable
private fun RowScope.GlassNavigationTab(
    label: String,
    iconRes: Int,
    isSelected: Boolean,
    reducedMotion: Boolean,
    stateLabel: String,
    onClick: () -> Unit,
) {
    // Leichter Pop auf dem aktiven Icon, passend zur gleitenden Pille;
    // inaktive Icons bleiben ruhig. Bei Reduced Motion entfaellt der Pop.
    val iconScale by animateFloatAsState(
        targetValue = if (isSelected) 1.12f else 1f,
        animationSpec =
            if (reducedMotion) {
                snap()
            } else {
                spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessMedium,
                )
            },
        label = "bottom-nav-icon-scale",
    )
    Column(
        modifier =
            Modifier
                .weight(1f)
                .fillMaxHeight()
                .padding(vertical = 6.dp)
                .semantics {
                    selected = isSelected
                    stateDescription = stateLabel
                }.clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    role = Role.Tab,
                    onClick = onClick,
                ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            modifier =
                Modifier
                    .size(30.dp)
                    .graphicsLayer {
                        scaleX = iconScale
                        scaleY = iconScale
                    },
            tint =
                if (isSelected) {
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
                if (isSelected) {
                    MaterialTheme.colorScheme.onPrimary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
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
internal fun NavHostController.navigateTopLevel(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

/**
 * Enter-Transition des NavHost (Paket 4.16 ausgelagert): Now-Playing kommt
 * als Sheet von unten, alle anderen Ziele blenden weich ein; bei Reduced
 * Motion ohne Slide.
 */
internal fun libraryEnterTransition(
    targetRoute: String?,
    reducedMotion: Boolean,
): EnterTransition =
    if (targetRoute == ROUTE_NOW_PLAYING) {
        if (reducedMotion) {
            fadeIn(snap())
        } else {
            slideInVertically(
                animationSpec =
                    spring(
                        dampingRatio = Spring.DampingRatioLowBouncy,
                        stiffness = Spring.StiffnessMediumLow,
                    ),
                initialOffsetY = { it },
            ) + fadeIn()
        }
    } else {
        fadeIn()
    }

/** Pop-Exit-Transition des NavHost; Gegenstueck zu [libraryEnterTransition]. */
internal fun libraryPopExitTransition(
    targetRoute: String?,
    reducedMotion: Boolean,
): ExitTransition =
    if (targetRoute == ROUTE_NOW_PLAYING) {
        if (reducedMotion) {
            fadeOut(snap())
        } else {
            slideOutVertically(
                animationSpec =
                    spring(
                        dampingRatio = Spring.DampingRatioNoBouncy,
                        stiffness = Spring.StiffnessMediumLow,
                    ),
                targetOffsetY = { it },
            ) + fadeOut()
        }
    } else {
        fadeOut()
    }

/**
 * Oeffnet den Now-Playing-Screen; [launchSingleTop] verhindert, dass
 * wiederholte Titel-Taps mehrere identische Eintraege stapeln.
 */
internal fun NavHostController.openNowPlaying() {
    navigate(ROUTE_NOW_PLAYING) { launchSingleTop = true }
}

/**
 * Baut die Kalibrierungs-Route (Paket 1.6): als eigene Funktion, damit die
 * Argument-Formatierung unit-testbar ist (vorher inline im Callback).
 */
internal fun calibrationRoute(
    exerciseId: Long,
    deviceId: String,
): String = "calibration/$exerciseId/$deviceId"
