package com.dropsync.feature.timer

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dropsync.core.designsystem.theme.FlowRepTheme
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Paket 5 (Befund 7.1.5): Das Timer-Stellrad (B2, Stunden/Minuten/Sekunden)
 * ist der vierte Screen im Screenshot-Gate — hell und dunkel. Referenzen
 * unter `src/test/screenshots/`, erneuert per `recordRoborazziDebug`.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = RobolectricDeviceQualifiers.Pixel6)
class TimerWheelScreenshotsTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun timerRadHell() {
        compose.setContent {
            FlowRepTheme(darkTheme = false) {
                Column(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.background),
                ) {
                    TimerWheel(seconds = 5_400, onSecondsChange = {})
                }
            }
        }
        compose.onRoot().captureRoboImage()
    }

    @Test
    fun timerRadDunkel() {
        compose.setContent {
            FlowRepTheme(darkTheme = true) {
                Column(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.background),
                ) {
                    TimerWheel(seconds = 5_400, onSecondsChange = {})
                }
            }
        }
        compose.onRoot().captureRoboImage()
    }
}
