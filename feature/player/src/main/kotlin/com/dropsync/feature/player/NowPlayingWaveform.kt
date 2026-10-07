package com.dropsync.feature.player

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material.icons.outlined.RepeatOne
import androidx.compose.material.icons.outlined.Shuffle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dropsync.core.designsystem.chart.RunningWaveform
import com.dropsync.core.designsystem.chart.WaveformMapping
import com.dropsync.core.designsystem.chart.WaveformPlaceholder
import com.dropsync.core.designsystem.component.CoverImage
import com.dropsync.core.designsystem.icon.BrandIcons
import com.dropsync.core.designsystem.theme.OverlayTokens
import com.dropsync.core.designsystem.theme.isWide
import com.dropsync.core.designsystem.theme.rememberReducedMotion
import com.dropsync.core.designsystem.theme.rememberWindowWidthSizeClass
import com.dropsync.core.model.SongMarker
import com.dropsync.domain.playback.QueueItem
import com.dropsync.domain.playback.RepeatMode
import com.dropsync.domain.timer.DropRestEligibility
import com.dropsync.domain.timer.DropSyncFailureReason
import com.dropsync.domain.timer.DropSyncMode
import com.dropsync.domain.timer.TimerMode
import com.dropsync.domain.timer.TimerStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.abs

internal const val MARKER_HIT_SLOP_FRACTION = 0.06f

/** Hoehe der Waveform-Zone (Referenz: ca. 320 px auf 2400 px Hoehe). */
private val WAVEFORM_HEIGHT = 150.dp

/** Deckkraft der kommenden (noch nicht gespielten) Waveform-Haelfte. */
internal const val WAVE_UPCOMING_ALPHA = 0.38f

/** Deckkraft der Spiegelung unter der Waveform. */
internal const val WAVE_REFLECTION_ALPHA = 0.16f

@Composable
internal fun PowerampWaveformTransport(
    positionMs: () -> Long,
    durationMs: Long,
    isPlaying: Boolean,
    waveformState: WaveformUiState,
    markers: List<SongMarker>,
    palette: NowPlayingPalette,
    markerFractions: List<Float>,
    suggestionFractions: List<Float>,
    targetFraction: Float?,
    onTogglePlayPause: () -> Unit,
    onSeek: (Long) -> Unit,
    onScrubbingChange: (Boolean) -> Unit,
    onLongPressAt: (Float) -> Unit,
    onMoveMarker: (Float) -> Unit,
    onMarkerTap: (Float) -> Unit,
    onRetryAnalysis: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Scrub-Vorschau als State, nicht als `by`-Delegat: gelesen wird sie nur
    // in der Zeitanzeige und im Zeichenblock der Waveform.
    val scrubPositionState = remember { mutableStateOf<Long?>(null) }
    val safeDuration = durationMs.coerceAtLeast(1L)
    // Lambdas statt Werte: der Fortschritt erreicht die Waveform ohne diese
    // Composable bei jedem Ticker-Schritt zu invalidieren (P1-Fix).
    val shownPosition: () -> Long = { scrubPositionState.value ?: positionMs() }
    val fraction: () -> Float = { (shownPosition().toFloat() / safeDuration).coerceIn(0f, 1f) }
    Column(modifier = modifier.fillMaxWidth().widthIn(max = 900.dp)) {
        Box(
            modifier = Modifier.fillMaxWidth().height(WAVEFORM_HEIGHT),
            contentAlignment = Alignment.Center,
        ) {
            when (waveformState) {
                is WaveformUiState.Ready -> {
                    RunningWaveform(
                        buckets = waveformState.buckets,
                        progressFraction = fraction,
                        onSeek = { onSeek((it * safeDuration).toLong()) },
                        onScrubPreview = { value ->
                            // Media3-Scrubbing-Modus mitschalten: der Player
                            // optimiert waehrend der Geste auf schnelle Seeks.
                            val wasScrubbing = scrubPositionState.value != null
                            scrubPositionState.value = value?.let { (it * safeDuration).toLong() }
                            val isScrubbing = value != null
                            if (isScrubbing != wasScrubbing) onScrubbingChange(isScrubbing)
                        },
                        markerFractions = markerFractions,
                        onLongPress = onLongPressAt,
                        onMoveMarker = onMoveMarker,
                        suggestionFractions = suggestionFractions,
                        targetFraction = targetFraction,
                        onMarkerTap = onMarkerTap,
                        // Gleiche Trefferzone wie die Sheet-Zuordnung im
                        // Screen (6 %), damit Tap und Langdruck identisch
                        // treffen.
                        markerTapSlopFraction = MARKER_HIT_SLOP_FRACTION,
                        contentDescription = stringResource(R.string.now_playing_waveform_with_markers, markers.size),
                        playedColor = palette.accent,
                        upcomingColor = palette.waveUpcoming,
                        reflectionColor = palette.waveReflection,
                        markerColor = palette.accent,
                        modifier = Modifier.fillMaxSize(),
                    )
                }

                WaveformUiState.Loading -> {
                    WaveformPlaceholder(
                        contentDescription = stringResource(R.string.now_playing_waveform_loading),
                        modifier = Modifier.fillMaxWidth().height(70.dp),
                    )
                }

                // C1 (P-7): Fehlschlag und Laden sind unterscheidbar; der
                // Retry stoesst dieselbe Analyse erneut an (idempotent).
                WaveformUiState.Unavailable -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(R.string.now_playing_waveform_unavailable),
                            color = palette.contentMuted,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = onRetryAnalysis) {
                            Text(text = stringResource(R.string.now_playing_waveform_retry))
                        }
                    }
                }

                WaveformUiState.Hidden -> {
                    Unit
                }
            }
            // Play/Pause liegt auf der Abspielposition der Waveform (Vorbild: Poweramp). Die laufende
            // Waveform haelt die Position in der Mitte, der Button bleibt dort stehen.
            IconButton(
                onClick = onTogglePlayPause,
                modifier = Modifier.size(PLAY_BUTTON_SIZE).background(palette.accent, CircleShape),
            ) {
                Icon(
                    painter = painterResource(if (isPlaying) BrandIcons.Pause else BrandIcons.Play),
                    contentDescription = stringResource(if (isPlaying) R.string.player_pause else R.string.player_play),
                    tint = palette.onAccent,
                    modifier = Modifier.size(30.dp),
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            // Zeitanzeige zeichnet sich selbst neu, ohne die Elternkette
            // zu invalidieren (Draw-Phase-Read der Position).
            PositionText(position = shownPosition, color = palette.contentMuted)
            Text(
                formatClockMs(safeDuration),
                color = palette.contentMuted,
                // C1 (7.2): Typo-Skala statt 15-sp-Literal.
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

/**
 * P2-21: Legende nur zeigen, wenn die Waveform steht UND es etwas zu
 * unterscheiden gibt (bestaetigte Marker, Vorschlaege oder ein Ziel).
 */
@Composable
internal fun MarkerLegendSection(
    waveformReady: Boolean,
    markers: List<SongMarker>,
    suggestions: List<SongMarker>,
    targetFraction: Float?,
    palette: NowPlayingPalette,
    modifier: Modifier = Modifier,
) {
    val hasMarkerKinds = markers.isNotEmpty() || suggestions.isNotEmpty() || targetFraction != null
    if (!waveformReady || !hasMarkerKinds) return
    MarkerLegendRow(
        palette = palette,
        showTarget = targetFraction != null,
        modifier = modifier,
    )
}

/**
 * P2-21 (UI-Handbuch 14.3): Marker-Legende unter der Waveform. Reiner Text
 * mit den gezeichneten Symbolen; fuer Accessibility wird die ganze Zeile zu
 * einem Satz zusammengefasst.
 */
@Composable
private fun MarkerLegendRow(
    palette: NowPlayingPalette,
    showTarget: Boolean,
    modifier: Modifier = Modifier,
) {
    val a11y = stringResource(R.string.now_playing_marker_legend_a11y)
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .widthIn(max = 900.dp)
                .semantics(mergeDescendants = true) { contentDescription = a11y },
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LegendEntry(
            color = palette.accent,
            alpha = 1f,
            label = stringResource(R.string.now_playing_marker_legend_confirmed),
            textColor = palette.contentMuted,
        )
        LegendEntry(
            color = palette.accent,
            alpha = 0.45f,
            label = stringResource(R.string.now_playing_marker_legend_suggestion),
            textColor = palette.contentMuted,
        )
        if (showTarget) {
            LegendEntry(
                color = palette.accent,
                alpha = 1f,
                label = stringResource(R.string.now_playing_marker_legend_target),
                textColor = palette.contentMuted,
                diamond = true,
            )
        }
    }
}

/** Ein Legendeneintrag: gezeichnetes Symbol (Tick oder Diamant) + Text. */
@Composable
private fun LegendEntry(
    color: Color,
    alpha: Float,
    label: String,
    textColor: Color,
    diamond: Boolean = false,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Canvas(modifier = Modifier.size(width = 10.dp, height = 14.dp)) {
            if (diamond) {
                val radius = size.minDimension / 2f
                val centerY = size.height / 2f
                val path =
                    Path().apply {
                        moveTo(size.width / 2f, centerY - radius)
                        lineTo(size.width / 2f + radius, centerY)
                        lineTo(size.width / 2f, centerY + radius)
                        lineTo(size.width / 2f - radius, centerY)
                        close()
                    }
                drawPath(path = path, color = color.copy(alpha = alpha))
            } else {
                drawLine(
                    color = color.copy(alpha = alpha),
                    start = Offset(size.width / 2f, 0f),
                    end = Offset(size.width / 2f, size.height),
                    strokeWidth = 2.dp.toPx(),
                )
            }
        }
        Spacer(Modifier.width(5.dp))
        Text(text = label, style = MaterialTheme.typography.labelSmall, color = textColor)
    }
}

/**
 * Laufende Zeitanzeige. Der State-Read liegt in der `text`-Lambda von
 * [BasicText]-artigem Aufruf, damit ein Positionswechsel nur diese
 * Textzeile neu misst statt der ganzen Transport-Reihe (Compose-Phasen).
 */
@Composable
private fun PositionText(
    position: () -> Long,
    color: Color,
) {
    Text(
        text = formatClockMs(position()),
        color = color,
        // C1 (7.2): Typo-Skala statt 15-sp-Literal.
        style = MaterialTheme.typography.bodyMedium,
    )
}
