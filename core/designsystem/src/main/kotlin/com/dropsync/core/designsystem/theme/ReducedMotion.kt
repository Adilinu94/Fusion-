package com.dropsync.core.designsystem.theme

import android.content.Context
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Zentrales Reduced-Motion-Signal (Befund: Reduced Motion fast ueberall
 * ignoriert). Die Shell liest einmal den Systemwert
 * `ANIMATOR_DURATION_SCALE == 0` (Entwickleroption "Animationen aus" bzw.
 * "Animationen entfernen") und stellt ihn hier bereit; Screens und
 * Komponenten schalten ihre Federn/Teens darauf um.
 *
 * `false` ist der Default (Vorschau, Tests, unversorgte Baeume): dort laufen
 * Animationen wie bisher. Nicht reaktiv auf Aenderungen waehrend des Laufs —
 * der Wert wird beim App-Start gelesen, wie es auch die fruehere
 * Dashboard-Lokaloesung handhabte.
 */
val LocalReducedMotion = staticCompositionLocalOf { false }

/** Bequemer Lesezugriff in Composables. */
@Composable
fun rememberReducedMotion(): Boolean = LocalReducedMotion.current

/** Systemwert: true, wenn Animationen global abgeschaltet sind. */
fun Context.isReducedMotion(): Boolean =
    Settings.Global.getFloat(contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
