package com.dropsync.app

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dropsync.core.designsystem.theme.FlowRepTheme
import com.dropsync.core.model.AccentColor
import com.dropsync.core.model.ThemeMode
import com.dropsync.domain.settings.AccentColorRepository
import com.dropsync.domain.settings.ThemeSettingsRepository
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Einzige Activity; hostet die Navigation mit den drei Zielen Musik,
 * Training, Einstellungen (Bauplan Schritt 12.2). Die Shell passt sich
 * ueber Window Size Classes an (12.6). Das App-Design (Hell/Dunkel/System)
 * kommt aus den Einstellungen und wird hier auf Theme und Systemleisten
 * angewendet.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject
    lateinit var themeSettings: ThemeSettingsRepository

    @Inject
    lateinit var accentColorSettings: AccentColorRepository

    @OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val themeMode by
                themeSettings.themeMode.collectAsStateWithLifecycle(
                    initialValue = ThemeMode.SYSTEM,
                )
            val darkTheme =
                when (themeMode) {
                    ThemeMode.SYSTEM -> isSystemInDarkTheme()
                    ThemeMode.LIGHT -> false
                    ThemeMode.DARK -> true
                }
            val accent by
                accentColorSettings.accentColor.collectAsStateWithLifecycle(
                    initialValue = AccentColor.LIME,
                )
            // Systemleisten-Icons an das gewaehlte Design koppeln (nicht nur
            // an das Systemdesign), damit Kontraste im erzwungenen Hell-/
            // Dunkelmodus stimmen (offizielles Edge-to-edge-Muster).
            DisposableEffect(darkTheme) {
                enableEdgeToEdge(
                    statusBarStyle =
                        SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { darkTheme },
                    navigationBarStyle =
                        SystemBarStyle.auto(LIGHT_SCRIM, DARK_SCRIM) { darkTheme },
                )
                onDispose {}
            }
            FlowRepTheme(darkTheme = darkTheme, accent = accent) {
                DropSyncApp(windowSizeClass = calculateWindowSizeClass(this))
            }
        }
    }

    private companion object {
        // Standard-Scrims aus enableEdgeToEdge fuer die 3-Knopf-Navigation.
        private val LIGHT_SCRIM = Color.argb(0xe6, 0xFF, 0xFF, 0xFF)
        private val DARK_SCRIM = Color.argb(0x80, 0x1b, 0x1b, 0x1b)
    }
}
