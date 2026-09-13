package com.dropsync.core.designsystem.theme

import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * C2: Zentral bereitgestellte Fenstergroesse. Die App-Shell berechnet sie
 * einmal (`calculateWindowSizeClass`) und stellt sie hier bereit; Screens
 * lesen den Breakpoint, ohne ihn durch jede Navigations-Ebene zu reichen.
 *
 * `null` ist der Vorschau-/Test-Fall (keine Activity): dann gilt bewusst
 * Compact, damit eine Vorschau nie an einer fehlenden Versorgung scheitert.
 */
val LocalWindowSizeClass = staticCompositionLocalOf<WindowSizeClass?> { null }

/** Breiten-Breakpoint des aktuellen Fensters; ohne Shell-Versorgung Compact. */
@Composable
fun rememberWindowWidthSizeClass(): WindowWidthSizeClass =
    LocalWindowSizeClass.current?.widthSizeClass ?: WindowWidthSizeClass.Compact

/** True, sobald mehr als Telefonbreite verfuegbar ist (Tablet/Landscape). */
val WindowWidthSizeClass.isWide: Boolean
    get() = this != WindowWidthSizeClass.Compact
