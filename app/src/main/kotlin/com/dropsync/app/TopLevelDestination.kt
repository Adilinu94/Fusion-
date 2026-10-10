package com.dropsync.app

import com.dropsync.core.designsystem.icon.BrandIcons

/**
 * Hauptnavigation mit vier Zielen (Fusion-Design 2026-08-07):
 * Music (Start), Train, Verlauf, Einstellungen. Kompakt: Bottom Navigation;
 * ab Medium: Navigation Rail per Window Size Classes.
 *
 * D2 (MatchingDeclarationName): eigene Datei statt Beifang in
 * [DropSyncApp] — die Deklaration hat einen eigenen Namen.
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
 * Start-Tab der App: Wurzel des Backstacks und Ziel von `popUpTo` in [navigateTopLevel].
 * Entscheidung 2026-09-28: Train (vorher Music). Eine Konstante, damit der NavHost in
 * [DropSyncApp] und sein Spiegel im Backstack-Test nicht auseinanderlaufen.
 */
internal val START_TAB: TopLevelDestination = TopLevelDestination.TRAIN
