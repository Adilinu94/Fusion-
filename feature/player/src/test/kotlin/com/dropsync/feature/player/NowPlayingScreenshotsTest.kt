package com.dropsync.feature.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dropsync.core.designsystem.theme.FlowRepTheme
import com.dropsync.core.model.MarkerSource
import com.dropsync.core.model.SongMarker
import com.dropsync.domain.timer.DropSyncMode
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * D1 (I-7): Der Now-Playing-Hero (Titelzeile, Waveform mit Markern,
 * Legende, DropSync-Statuszeile) in beiden Themes. Der Screen ist
 * dark-first, hell bleibt aber in Gebrauch (C8-Kontrastarbeiten) — beide
 * Varianten sind eingefroren. Jede unbeabsichtigte Verschiebung faellt im
 * CI-Gate, nicht erst auf dem Geraet.
 *
 * Bewusst ohne ViewModel: die Bausteine bekommen feste, realistische Daten
 * (statische Position, keine laufende Uhr), damit das Bild deterministisch
 * bleibt. Referenzen liegen unter `src/test/screenshots/` und werden per
 * `recordRoborazziDebug` erneuert.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = RobolectricDeviceQualifiers.Pixel6)
class NowPlayingScreenshotsTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun nowPlayingDunkel() {
        hero(darkTheme = true)
    }

    @Test
    fun nowPlayingHell() {
        hero(darkTheme = false)
    }

    private fun hero(darkTheme: Boolean) {
        compose.setContent {
            FlowRepTheme(darkTheme = darkTheme) {
                val palette = testPalette()
                Column(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .background(palette.background),
                ) {
                    PowerampTitleRow(
                        title = "Neon Alley",
                        artist = "Nova Kane",
                        palette = palette,
                        menuOpen = false,
                        onOpenMenu = {},
                        onDismissMenu = {},
                        onAddMarker = {},
                        onOpenQueue = {},
                        onOpenTempo = {},
                        onToggleFavorite = {},
                        onOpenMarkerReview = {},
                        queueSize = 3,
                    )
                    PowerampWaveformTransport(
                        positionMs = { 62_000L },
                        durationMs = 180_000L,
                        isPlaying = true,
                        waveformState = WaveformUiState.Ready(buckets = TEST_BUCKETS),
                        markers = TEST_MARKERS,
                        palette = palette,
                        markerFractions = listOf(0.42f),
                        suggestionFractions = listOf(0.68f),
                        targetFraction = 0.42f,
                        onTogglePlayPause = {},
                        onSeek = {},
                        onScrubbingChange = {},
                        onLongPressAt = {},
                        onMoveMarker = {},
                        onMarkerTap = {},
                        onRetryAnalysis = {},
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    MarkerLegendSection(
                        waveformReady = true,
                        markers = TEST_MARKERS,
                        suggestions = TEST_SUGGESTIONS,
                        targetFraction = 0.42f,
                        palette = palette,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                    DropSyncStatusRow(
                        status =
                            DropStatusLine.Ready(
                                songTitle = "Neon Alley",
                                markerLabel = "Drop",
                                remainingMs = 48_000L,
                                mode = DropSyncMode.LANDING_AT_REST_END,
                            ),
                        palette = palette,
                        onDismiss = {},
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        }
        compose.onRoot().captureRoboImage()
    }

    /**
     * Derselbe Aufbau wie `rememberNowPlayingPalette` mit Cover ohne
     * Akzent (Fallback = Theme-Primaerfarbe) — die Testbilder zeigen damit
     * genau die Designsystem-Farben.
     */
    @Composable
    private fun testPalette(): NowPlayingPalette {
        val scheme = MaterialTheme.colorScheme
        return NowPlayingPalette(
            background = scheme.background,
            accent = scheme.primary,
            content = scheme.onBackground,
            contentMuted = scheme.onSurfaceVariant,
            onAccent = scheme.onPrimary,
            inactive = scheme.onBackground.copy(alpha = 0.38f),
            disabled = scheme.onBackground.copy(alpha = 0.20f),
            waveUpcoming = scheme.primary.copy(alpha = 0.45f),
            waveReflection = scheme.primary.copy(alpha = 0.16f),
        )
    }

    private companion object {
        /** Deterministische Min/Max-Paare (reine Mathematik, kein Zufall). */
        val TEST_BUCKETS: List<Pair<Float, Float>> =
            List(220) { i ->
                val envelope = 0.35f + 0.55f * kotlin.math.abs(kotlin.math.sin(i / 23f))
                val low = -envelope * (0.6f + 0.3f * kotlin.math.abs(kotlin.math.sin(i / 7f)))
                val high = envelope * (0.5f + 0.4f * kotlin.math.abs(kotlin.math.cos(i / 11f)))
                low to high
            }

        val TEST_MARKERS: List<SongMarker> =
            listOf(
                SongMarker(
                    id = 1L,
                    label = "Drop",
                    positionMs = 75_600L,
                    source = MarkerSource.MANUAL,
                    isEnabled = true,
                    linkedSongId = 42L,
                ),
            )

        val TEST_SUGGESTIONS: List<SongMarker> =
            listOf(
                SongMarker(
                    id = 2L,
                    label = "Drop?",
                    positionMs = 122_400L,
                    source = MarkerSource.AUTO_DETECTED,
                    isEnabled = false,
                    linkedSongId = 42L,
                ),
            )
    }
}
