package com.dropsync.core.designsystem.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
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
 * C1-Screenshot-Gate: Die Designsystem-Komponenten sehen in Hell und Dunkel
 * definiert aus — jede unbeabsichtigte Verschiebung (Tokens, Typo, Farben)
 * faellt hier, nicht erst auf dem Geraet. Referenzen liegen unter
 * `src/test/screenshots/` und werden per `recordRoborazziDebug` erneuert
 * (nur bei beabsichtigtem Redesign, danach Review des Diffs; record und
 * verify brauchen getrennte Gradle-Aufrufe).
 *
 * Bewusst nur statische Komponenten: Animiertes (ProgressRing, pressScale)
 * waere Frame-abhaengig und damit flaky.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = RobolectricDeviceQualifiers.Pixel6)
class ComponentScreenshotsTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun buttonsHell() {
        compose.setContent {
            FlowRepTheme(darkTheme = false) {
                ButtonGallery()
            }
        }
        compose.onRoot().captureRoboImage()
    }

    @Test
    fun buttonsDunkel() {
        compose.setContent {
            FlowRepTheme(darkTheme = true) {
                ButtonGallery()
            }
        }
        compose.onRoot().captureRoboImage()
    }

    @Test
    fun brandCardHell() {
        compose.setContent {
            FlowRepTheme(darkTheme = false) {
                BrandCard {
                    Text("Titel der Karte")
                }
            }
        }
        compose.onRoot().captureRoboImage()
    }

    @Test
    fun brandCardDunkel() {
        compose.setContent {
            FlowRepTheme(darkTheme = true) {
                BrandCard {
                    Text("Titel der Karte")
                }
            }
        }
        compose.onRoot().captureRoboImage()
    }

    @Test
    fun flowRepSurfaceHell() {
        compose.setContent {
            FlowRepTheme(darkTheme = false) {
                FlowRepSurface {
                    Text("Flaeche mit System-Radius")
                }
            }
        }
        compose.onRoot().captureRoboImage()
    }

    @Test
    fun flowRepSurfaceDunkel() {
        compose.setContent {
            FlowRepTheme(darkTheme = true) {
                FlowRepSurface {
                    Text("Flaeche mit System-Radius")
                }
            }
        }
        compose.onRoot().captureRoboImage()
    }
}

@Composable
private fun ButtonGallery() {
    Column(
        modifier = Modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        BrandButtonPrimary(text = "Primaer", onClick = {})
        BrandButtonSecondary(text = "Sekundaer", onClick = {})
        BrandButtonGhost(text = "Ghost", onClick = {})
        BrandButtonPrimary(text = "Deaktiviert", onClick = {}, enabled = false)
    }
}
