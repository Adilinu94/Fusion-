package com.dropsync.feature.player

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dropsync.core.designsystem.chart.RunningWaveform
import com.dropsync.core.designsystem.chart.WaveformMapping
import com.dropsync.core.designsystem.chart.WaveformPlaceholder
import com.dropsync.core.designsystem.component.CoverImage
import com.dropsync.core.designsystem.icon.BrandIcons
import com.dropsync.core.designsystem.theme.OverlayTokens
import com.dropsync.core.designsystem.theme.isWide
import com.dropsync.core.designsystem.theme.rememberWindowWidthSizeClass
import com.dropsync.core.model.SongMarker
import com.dropsync.domain.playback.QueueItem
import com.dropsync.domain.playback.RepeatMode
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.abs

private const val MARKER_HIT_SLOP_FRACTION = 0.06f
private const val DISMISS_THRESHOLD_PX = 180f
private const val NOW_PLAYING_COVER_DIM_PX = 1024

/** Ab diesem Anteil der Hoehe ist der Hintergrund die reine Theme-Flaeche. */
private const val ACCENT_GRADIENT_END = 0.52f

/** Deckkraft des Akzent-Schimmers am oberen Bildschirmrand. */
private const val ACCENT_TINT_ALPHA = 0.28f

/** Dauer des Farbwechsels beim Songwechsel. */
private const val ACCENT_ANIMATION_MS = 520

/** Hoehe der Waveform-Zone (Referenz: ca. 320 px auf 2400 px Hoehe). */
private val WAVEFORM_HEIGHT = 150.dp

/** Durchmesser des zentralen Play-Kreises (Referenz ca. 160 px). */
private val PLAY_BUTTON_SIZE = 74.dp

/** Deckkraft der kommenden (noch nicht gespielten) Waveform-Haelfte. */
private const val WAVE_UPCOMING_ALPHA = 0.38f

/** Deckkraft der Spiegelung unter der Waveform. */
private const val WAVE_REFLECTION_ALPHA = 0.16f

/** Deckkraft inaktiver Transport-Symbole (Repeat/Shuffle aus). */
private const val INACTIVE_ALPHA = 0.38f

/** Deckkraft deaktivierter Transport-Symbole (kein Vorgaenger/Nachfolger). */
private const val DISABLED_ALPHA = 0.20f

/**
 * Farbsatz des Now-Playing-Screens — P3-Fix #25.
 *
 * Vorher standen hier sechs feste Poweramp-Farben (`CYAN = 0xFF009FE3`,
 * `POWERAMP_TEXT`, `POWERAMP_MUTED`, ...) und ein `background(Color.White)`.
 * Der Screen war damit der einzige im Projekt, der das Designsystem
 * (BrandBlack/BrandWhite/Lime, Dark-Mode als Standard) ignoriert hat: im
 * Dark-Mode blendete er weiss, und die in [PlayerArtworkColors] berechneten
 * Werte `scrim`, `content`, `contentMuted` und `onAccent` blieben ungenutzt —
 * nur `accent` wurde gelesen.
 *
 * Jetzt kommt die Helligkeit aus dem Theme, der Farbton aus dem Cover und
 * der Akzent-Fallback aus `colorScheme.primary` (also der in den
 * Einstellungen gewaehlten Markenfarbe).
 */
private data class NowPlayingPalette(
    val background: Color,
    val accent: Color,
    val content: Color,
    val contentMuted: Color,
    val onAccent: Color,
    val inactive: Color,
    val disabled: Color,
    val waveUpcoming: Color,
    val waveReflection: Color,
)

@Composable
private fun rememberNowPlayingPalette(contentUri: String?): NowPlayingPalette {
    val artwork by rememberArtworkColors(contentUri)
    val fallbackAccent = MaterialTheme.colorScheme.primary
    val onFallbackAccent = MaterialTheme.colorScheme.onPrimary
    val accent = artwork.accent ?: fallbackAccent
    val animatedAccent by animateColorAsState(
        targetValue = accent,
        animationSpec = tween(durationMillis = ACCENT_ANIMATION_MS, easing = FastOutSlowInEasing),
        label = "now_playing_accent",
    )
    return NowPlayingPalette(
        background = artwork.scrim,
        accent = animatedAccent,
        content = artwork.content,
        contentMuted = artwork.contentMuted,
        onAccent = if (artwork.accent != null) artwork.onAccent else onFallbackAccent,
        inactive = artwork.content.copy(alpha = INACTIVE_ALPHA),
        disabled = artwork.content.copy(alpha = DISABLED_ALPHA),
        waveUpcoming = animatedAccent.copy(alpha = WAVE_UPCOMING_ALPHA),
        waveReflection = animatedAccent.copy(alpha = WAVE_REFLECTION_ALPHA),
    )
}

/** Poweramp-style Now Playing surface: white, circular artwork and a moving waveform window. */
@Composable
fun NowPlayingScreen(
    onBack: () -> Unit,
    contentPadding: PaddingValues = PaddingValues(),
    viewModel: PlayerViewModel = hiltViewModel(),
    // B4: App-weiter Snackbar-Host (Undo) — die Shell blendet ihn ueber dem
    // Mini-Player ein.
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
) {
    val state by viewModel.nowPlaying.collectAsStateWithLifecycle()
    // P1-Fix (deferred state read): die Live-Position wird ABSICHTLICH nicht
    // per `by` in die Composition gelesen. Der 200-ms-Ticker haette sonst
    // fuenfmal pro Sekunde den gesamten Screen-Body invalidiert — Cover-Pager,
    // Titelzeile, Modus-Reihe und Transport inklusive. Stattdessen wandert der
    // State als Lambda nach unten und wird erst dort gelesen, wo er gebraucht
    // wird (Zeichenphase der Waveform, Zeitanzeige).
    val livePositionState = viewModel.livePositionMs.collectAsStateWithLifecycle()
    val waveformState by viewModel.waveform.collectAsStateWithLifecycle()
    val markers by viewModel.nowPlayingMarkers.collectAsStateWithLifecycle()
    val queue by viewModel.queue.collectAsStateWithLifecycle()
    val playbackSpeed by viewModel.playbackSpeed.collectAsStateWithLifecycle()
    val trackBpm by viewModel.trackBpm.collectAsStateWithLifecycle()
    val bpmLockEnabled by viewModel.isBpmLockEnabled.collectAsStateWithLifecycle()
    val lockTargetBpm by viewModel.lockTargetBpm.collectAsStateWithLifecycle()
    val fallbackPositionMs = state.positionMs
    val shownPositionMs: () -> Long = { livePositionState.value ?: fallbackPositionMs }
    val currentSongIndex = queue.currentIndex
    val hasNext = currentSongIndex in 0 until queue.items.lastIndex
    val hasPrevious = currentSongIndex > 0
    // C2: Auf Tablet/Landscape das kreisrunde Cover begrenzen — sonst fuellt
    // der Kreis die ganze Breite und verdraengt Titel/Transport.
    val wide = rememberWindowWidthSizeClass().isWide

    LaunchedEffect(state.songId) { viewModel.requestAnalysis(state.songId) }

    var menuOpen by remember { mutableStateOf(false) }
    var createMarkerAtMs by remember { mutableStateOf<Long?>(null) }
    var showQueue by remember { mutableStateOf(false) }
    var showTempo by remember { mutableStateOf(false) }

    // B4: Undo-Texte im Composable-Kontext aufloesen (Gesten-Lambdas sind keiner).
    val scope = rememberCoroutineScope()
    val queueRemovedText = stringResource(R.string.player_queue_removed)
    val markerDeletedText = stringResource(R.string.player_marker_deleted)
    val undoText = stringResource(R.string.player_undo)

    /**
     * B4: Zeigt nach einer loeschenden Aktion Undo an; bei Bestaetigung laeuft
     * das gemerkte Undo im ViewModel.
     */
    fun showUndoSnackbar(
        message: String,
        hasUndo: Boolean,
        onUndo: () -> Unit,
    ) {
        if (!hasUndo) return
        scope.launch {
            val result =
                snackbarHostState.showSnackbar(
                    message = message,
                    actionLabel = undoText,
                    withDismissAction = true,
                )
            if (result == SnackbarResult.ActionPerformed) onUndo()
        }
    }

    // Marker-Anteile EINMAL je Marker-/Dauer-Aenderung berechnen (P1-Fix).
    // Vorher entstand diese Liste zweimal pro Recomposition — also zehnmal
    // pro Sekunde, solange der Ticker lief.
    val markerFractions =
        remember(markers, state.durationMs) {
            val duration = state.durationMs.coerceAtLeast(1L)
            markers.map { (it.positionMs.toFloat() / duration).coerceIn(0f, 1f) }
        }

    val palette = rememberNowPlayingPalette(state.contentUri)

    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(palette.background)
                .pointerInput(onBack) {
                    var totalDrag = 0f
                    detectVerticalDragGestures(
                        onDragStart = { totalDrag = 0f },
                        onVerticalDrag = { change, dragAmount ->
                            totalDrag += dragAmount
                            change.consume()
                        },
                        onDragEnd = {
                            if (totalDrag >= DISMISS_THRESHOLD_PX) onBack()
                        },
                    )
                },
    ) {
        AccentBackground(palette = palette)

        // P3-Fix #28/#29: sichtbare und fokussierbare Zurueck-Affordance.
        // Der Screen war vorher ausschliesslich per vertikaler Wischgeste
        // schliessbar — fuer TalkBack, Switch Access und Nutzer ohne Kenntnis
        // der Geste faktisch eine Sackgasse.
        IconButton(
            onClick = onBack,
            modifier =
                Modifier
                    .statusBarsPadding()
                    .padding(start = 8.dp, top = 4.dp)
                    .size(48.dp),
        ) {
            Icon(
                painter = painterResource(BrandIcons.Back),
                contentDescription = stringResource(R.string.now_playing_back),
                tint = palette.content,
                modifier = Modifier.size(24.dp),
            )
        }

        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .padding(
                        top = 12.dp,
                        start = 20.dp,
                        end = 20.dp,
                        bottom = contentPadding.calculateBottomPadding() + 8.dp,
                    ),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (!state.isVisible) {
                Spacer(Modifier.height(180.dp))
                Text(
                    text = stringResource(R.string.now_playing_empty),
                    color = palette.content,
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                )
            } else {
                PowerampCover(
                    contentUri = state.contentUri,
                    queueItems = queue.items,
                    currentIndex = currentSongIndex,
                    coverResolver = viewModel::coverUriFor,
                    onSelectPage = viewModel::playQueueItem,
                    palette = palette,
                    modifier = if (wide) Modifier.widthIn(max = 440.dp) else Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(20.dp))
                PowerampTitleRow(
                    title = state.title,
                    artist = state.artist,
                    palette = palette,
                    menuOpen = menuOpen,
                    onOpenMenu = { menuOpen = true },
                    onDismissMenu = { menuOpen = false },
                    onAddMarker = {
                        createMarkerAtMs = shownPositionMs()
                        menuOpen = false
                    },
                    onOpenQueue = {
                        showQueue = true
                        menuOpen = false
                    },
                    onOpenTempo = {
                        showTempo = true
                        menuOpen = false
                    },
                    onToggleFavorite = viewModel::toggleFavorite,
                    queueSize = queue.items.size,
                )
                Spacer(Modifier.height(18.dp))
                PowerampModeRow(
                    repeatMode = state.repeatMode,
                    shuffleEnabled = state.shuffleEnabled,
                    hasPrevious = hasPrevious,
                    hasNext = hasNext,
                    palette = palette,
                    onCycleRepeat = viewModel::cycleRepeat,
                    onToggleShuffle = viewModel::toggleShuffle,
                    onPrevious = viewModel::skipToPrevious,
                    onNext = viewModel::skipToNext,
                )
                Spacer(Modifier.height(8.dp))
                PowerampWaveformTransport(
                    positionMs = shownPositionMs,
                    durationMs = state.durationMs,
                    isPlaying = state.isPlaying,
                    waveformState = waveformState,
                    markers = markers,
                    palette = palette,
                    onTogglePlayPause = viewModel::togglePlayPause,
                    onSeek = viewModel::seekTo,
                    onScrubbingChange = viewModel::setScrubbing,
                    onLongPressAt = { fraction ->
                        val duration = state.durationMs.coerceAtLeast(1L)
                        val nearest =
                            WaveformMapping.nearestMarkerIndex(
                                markerFractions,
                                fraction,
                                MARKER_HIT_SLOP_FRACTION,
                            )
                        if (nearest >= 0) {
                            // B4: sofort loeschen + Undo statt Bestaetigungsdialog.
                            val marker = markers[nearest]
                            viewModel.deleteMarker(marker.id)
                            showUndoSnackbar(
                                message = markerDeletedText,
                                hasUndo = viewModel.hasMarkerUndo(),
                                onUndo = viewModel::undoDeleteMarker,
                            )
                        } else {
                            createMarkerAtMs = (fraction * duration).toLong()
                        }
                    },
                    markerFractions = markerFractions,
                    onMoveMarker = { fraction ->
                        val duration = state.durationMs.coerceAtLeast(1L)
                        val targetMs = (fraction * duration).toLong()
                        markers.minByOrNull { abs(it.positionMs - targetMs) }?.let { marker ->
                            viewModel.moveMarker(marker.id, targetMs)
                        }
                    },
                    modifier = Modifier.weight(1f, fill = false),
                )
            }
        }
    }

    createMarkerAtMs?.let { positionMs ->
        CreateMarkerDialog(
            positionMs = positionMs,
            onConfirm = { label ->
                viewModel.createMarker(label, positionMs)
                createMarkerAtMs = null
            },
            onDismiss = { createMarkerAtMs = null },
        )
    }
    if (showQueue) {
        QueueSheet(
            state = queue,
            onDismiss = { showQueue = false },
            onPlay = viewModel::playQueueItem,
            onMove = viewModel::moveQueueItem,
            onRemove = { index ->
                viewModel.removeQueueItem(index)
                showUndoSnackbar(
                    message = queueRemovedText,
                    hasUndo = viewModel.hasQueueUndo(),
                    onUndo = viewModel::undoRemoveQueueItem,
                )
            },
        )
    }
    if (showTempo) {
        TempoSheet(
            speed = playbackSpeed,
            trackBpm = trackBpm,
            bpmLockEnabled = bpmLockEnabled,
            lockTargetBpm = lockTargetBpm,
            onDismiss = { showTempo = false },
            onSpeedSelected = viewModel::setPlaybackSpeed,
            onBpmLockChanged = viewModel::setBpmLock,
            onTargetBpmChanged = viewModel::setLockTargetBpm,
        )
    }
}

/**
 * Hintergrund: Akzentschimmer des Covers oben, weich in die Theme-Flaeche
 * auslaufend. Kein Blur-Bild — der Verlauf traegt die Songfarbe, ohne die
 * Helligkeit des Screens dem Cover zu ueberlassen (P3-Fix #25).
 */
@Composable
private fun AccentBackground(palette: NowPlayingPalette) {
    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(palette.background)
                .background(
                    Brush.verticalGradient(
                        colorStops =
                            arrayOf(
                                0f to palette.accent.copy(alpha = ACCENT_TINT_ALPHA),
                                ACCENT_GRADIENT_END to Color.Transparent,
                                1f to Color.Transparent,
                            ),
                    ),
                ),
    )
}

@Composable
private fun PowerampCover(
    contentUri: String?,
    queueItems: List<QueueItem>,
    currentIndex: Int,
    coverResolver: suspend (Long) -> String?,
    onSelectPage: (Int) -> Unit,
    palette: NowPlayingPalette,
    modifier: Modifier = Modifier,
) {
    val pageCount = queueItems.size.coerceAtLeast(1)
    val pagerState =
        rememberPagerState(
            initialPage = currentIndex.coerceIn(0, pageCount - 1),
        ) { pageCount }
    LaunchedEffect(currentIndex, pageCount) {
        val target = currentIndex.coerceIn(0, pageCount - 1)
        if (target != pagerState.currentPage) pagerState.animateScrollToPage(target)
    }
    LaunchedEffect(pagerState, currentIndex) {
        snapshotFlow { pagerState.settledPage }.collect { page ->
            if (page != currentIndex && page in queueItems.indices) onSelectPage(page)
        }
    }
    HorizontalPager(
        state = pagerState,
        // Quadratischer Rahmen -> das Cover wird als exakter Kreis
        // maskiert (Referenz: kreisrund, nicht oval).
        modifier = modifier.aspectRatio(1f),
        userScrollEnabled = queueItems.size > 1,
    ) { page ->
        val pageUri by produceState<String?>(
            initialValue = if (page == currentIndex) contentUri else null,
            queueItems.getOrNull(page)?.songId,
        ) {
            value = queueItems.getOrNull(page)?.songId?.let { coverResolver(it) } ?: value
        }
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            CoverImage(
                contentUri = pageUri,
                contentDescription = stringResource(R.string.now_playing_cover),
                maxDimPx = NOW_PLAYING_COVER_DIM_PX,
                modifier =
                    Modifier
                        .fillMaxSize()
                        // Quadrat + CircleShape = echter Kreis. Vorher legte
                        // eine feste dp-Groesse in einem breiteren Pager eine
                        // ovale Maske darueber.
                        .aspectRatio(1f)
                        .shadow(
                            elevation = 12.dp,
                            shape = CircleShape,
                            clip = false,
                            ambientColor = OverlayTokens.scrim.copy(alpha = 0.12f),
                            spotColor = OverlayTokens.scrim.copy(alpha = 0.12f),
                        ).clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            ) {
                Icon(
                    painter = painterResource(BrandIcons.NavMusic),
                    contentDescription = null,
                    tint = palette.inactive,
                    modifier = Modifier.fillMaxSize(0.28f),
                )
            }
        }
    }
}

@Composable
private fun PowerampTitleRow(
    title: String,
    artist: String?,
    palette: NowPlayingPalette,
    menuOpen: Boolean,
    onOpenMenu: () -> Unit,
    onDismissMenu: () -> Unit,
    onAddMarker: () -> Unit,
    onOpenQueue: () -> Unit,
    onOpenTempo: () -> Unit,
    onToggleFavorite: () -> Unit,
    queueSize: Int,
) {
    Box(modifier = Modifier.fillMaxWidth().widthIn(max = 900.dp)) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 44.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = title,
                // C2: feste 30 sp -> Typo-Token (skaliert mit Systemschrift).
                style = MaterialTheme.typography.headlineMedium.copy(color = palette.accent),
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = artist.orEmpty(),
                // C2: feste 18 sp -> Typo-Token.
                style = MaterialTheme.typography.titleMedium.copy(color = palette.contentMuted),
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Box(modifier = Modifier.align(Alignment.CenterEnd)) {
            // A5: 48-dp-Touch-Ziel statt 36 dp.
            IconButton(
                onClick = onOpenMenu,
                modifier =
                    Modifier
                        .size(48.dp)
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape),
            ) {
                Icon(
                    painter = painterResource(BrandIcons.More),
                    contentDescription = stringResource(R.string.now_playing_more),
                    tint = palette.accent,
                    modifier = Modifier.size(22.dp),
                )
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = onDismissMenu) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.now_playing_add_marker)) },
                    onClick = onAddMarker,
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.player_queue_open)) },
                    onClick = onOpenQueue,
                    enabled = queueSize > 0,
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.now_playing_tempo_title)) },
                    onClick = onOpenTempo,
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.now_playing_like)) },
                    onClick = {
                        onToggleFavorite()
                        onDismissMenu()
                    },
                )
            }
        }
    }
}

/**
 * Optionsreihe mit vier flachen Slots wie in der Referenz (keine Cards,
 * keine Labels): Previous, Repeat, Shuffle, Next. Die Queue bleibt im
 * Overflow-Menue; Sleep Timer und Visualizer sind bewusst nicht Teil des
 * Produktumfangs.
 */
@Composable
private fun PowerampModeRow(
    repeatMode: RepeatMode,
    shuffleEnabled: Boolean,
    hasPrevious: Boolean,
    hasNext: Boolean,
    palette: NowPlayingPalette,
    onCycleRepeat: () -> Unit,
    onToggleShuffle: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().widthIn(max = 860.dp),
        horizontalArrangement = Arrangement.SpaceAround,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PowerampModeButton(
            icon = { Icon(painterResource(BrandIcons.SkipPrevious), contentDescription = null) },
            description = stringResource(R.string.player_previous),
            active = false,
            enabled = hasPrevious,
            palette = palette,
            onClick = onPrevious,
        )
        PowerampModeButton(
            icon = {
                Icon(
                    imageVector =
                        if (repeatMode == RepeatMode.ONE) {
                            Icons.Outlined.RepeatOne
                        } else {
                            Icons.Outlined.Repeat
                        },
                    contentDescription = null,
                )
            },
            description =
                stringResource(
                    when (repeatMode) {
                        RepeatMode.OFF -> R.string.now_playing_repeat_off
                        RepeatMode.ALL -> R.string.now_playing_repeat_all
                        RepeatMode.ONE -> R.string.now_playing_repeat_one
                    },
                ),
            active = repeatMode != RepeatMode.OFF,
            palette = palette,
            onClick = onCycleRepeat,
        )
        PowerampModeButton(
            icon = { Icon(Icons.Outlined.Shuffle, contentDescription = null) },
            description =
                stringResource(
                    if (shuffleEnabled) R.string.now_playing_shuffle_on else R.string.now_playing_shuffle_off,
                ),
            palette = palette,
            active = shuffleEnabled,
            onClick = onToggleShuffle,
        )
        PowerampModeButton(
            icon = { Icon(painterResource(BrandIcons.SkipNext), contentDescription = null) },
            description = stringResource(R.string.player_next),
            active = false,
            enabled = hasNext,
            palette = palette,
            onClick = onNext,
        )
    }
}

@Composable
private fun PowerampModeButton(
    icon: @Composable () -> Unit,
    description: String,
    active: Boolean,
    palette: NowPlayingPalette,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    val scale by animateFloatAsState(
        targetValue = if (active) 1.1f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "poweramp_mode_scale",
    )
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        IconButton(
            onClick = onClick,
            enabled = enabled,
            // P3-Fix #28: contentDescription statt stateDescription. Die
            // inneren Icons setzen bewusst `contentDescription = null`, der
            // Button hatte also GAR KEIN Label — TalkBack las nur "Button".
            // `stateDescription` beschreibt per Kontrakt den ZUSTAND eines
            // bereits benannten Elements und ersetzt keinen Namen.
            modifier =
                Modifier.size(48.dp).semantics {
                    role = Role.Button
                    contentDescription = description
                },
        ) {
            Box(
                modifier =
                    Modifier.size(26.dp).graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                    },
            ) {
                androidx.compose.runtime.CompositionLocalProvider(
                    androidx.compose.material3.LocalContentColor provides
                        when {
                            !enabled -> palette.disabled
                            active -> palette.accent
                            else -> palette.inactive
                        },
                ) { icon() }
            }
        }
    }
}

@Composable
private fun PowerampWaveformTransport(
    positionMs: () -> Long,
    durationMs: Long,
    isPlaying: Boolean,
    waveformState: WaveformUiState,
    markers: List<SongMarker>,
    palette: NowPlayingPalette,
    markerFractions: List<Float>,
    onTogglePlayPause: () -> Unit,
    onSeek: (Long) -> Unit,
    onScrubbingChange: (Boolean) -> Unit,
    onLongPressAt: (Float) -> Unit,
    onMoveMarker: (Float) -> Unit,
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

                WaveformUiState.Unavailable, WaveformUiState.Hidden -> {
                    Text(
                        text = stringResource(R.string.now_playing_waveform_loading),
                        color = palette.contentMuted,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
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
                formatTimeMs(safeDuration),
                color = palette.contentMuted,
                fontSize = 15.sp,
                fontWeight = FontWeight.Normal,
            )
        }
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
        text = formatTimeMs(position()),
        color = color,
        fontSize = 15.sp,
        fontWeight = FontWeight.Normal,
    )
}

@Composable
private fun CreateMarkerDialog(
    positionMs: Long,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var label by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.now_playing_marker_add_title)) },
        text = {
            Column {
                Text(stringResource(R.string.now_playing_marker_add_position, formatTimeMs(positionMs)))
                Spacer(Modifier.height(12.dp))
                androidx.compose.material3.OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text(stringResource(R.string.now_playing_marker_label)) },
                    placeholder = { Text(stringResource(R.string.now_playing_marker_default_label)) },
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(label) }) { Text(stringResource(R.string.now_playing_marker_add_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.now_playing_marker_cancel)) }
        },
    )
}

private fun formatTimeMs(ms: Long): String {
    val totalSeconds = ms.coerceAtLeast(0L) / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.ROOT, "%d:%02d", minutes, seconds)
    }
}
