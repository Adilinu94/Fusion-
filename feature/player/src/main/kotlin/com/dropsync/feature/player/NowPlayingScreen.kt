package com.dropsync.feature.player

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dropsync.core.designsystem.chart.Waveform
import com.dropsync.core.designsystem.chart.WaveformMapping
import com.dropsync.core.designsystem.chart.WaveformPlaceholder
import com.dropsync.core.designsystem.component.CoverArtLoader
import com.dropsync.core.designsystem.component.CoverImage
import com.dropsync.core.designsystem.icon.BrandIcons
import com.dropsync.core.model.SongMarker
import com.dropsync.domain.playback.QueueItem
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.abs

/** Tick-Intervall des Positions-Tickers; laeuft nur bei sichtbarem Screen. */
private const val POSITION_TICK_MS = 200L

/** Grosszuegige Hoehe der Waveform-Bedienflaeche (Poweramp-Optik). */
private val WAVE_ZONE_HEIGHT = 180.dp

/** Durchmesser des Play-/Pause-Knopfs auf der Wellenform. */
private val PLAY_BUTTON_SIZE = 72.dp

/** Swipe-Distanz, ab der der Screen beim Loslassen schliesst. */
private val DISMISS_THRESHOLD_DP = 140.dp

/**
 * Trefferanteil der Marker-Ticks: ~24 dp auf der typischen Wellenform-
 * Breite (360-400 dp) — zusammen mit dem sichtbaren Tick ein ~48-dp-
 * Bedienziel (A11y-Mindestgroesse) fuer Long-Press und Drag-Start.
 */
private const val MARKER_HIT_SLOP_FRACTION = 0.06f

/**
 * Immersive Now-Playing surface mit blurred artwork, adaptive Farbwelt
 * aus dem Cover (statt festem Weiss), scrollable foreground, queue
 * paging und transport controls layered directly over the waveform.
 *
 * Umbau 2026-08 (Recherche-Bericht): YTM-Layout mit hoeheren Controls
 * (kleineres Cover, Waveform/Transport frueh im oberen Drittel),
 * Aktions-Carousel (EQ/Tempo/Marker/Mix/Queue), Swipe-down-dismiss am
 * Cover, Play/Pause-Shape-Morph (Expressive-Ziel ohne Alpha-API).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NowPlayingScreen(
    onBack: () -> Unit,
    contentPadding: PaddingValues = PaddingValues(),
    viewModel: PlayerViewModel = hiltViewModel(),
) {
    val state by viewModel.nowPlaying.collectAsStateWithLifecycle()
    val livePosition by viewModel.livePositionMs.collectAsStateWithLifecycle()
    val waveformState by viewModel.waveform.collectAsStateWithLifecycle()
    val markers by viewModel.nowPlayingMarkers.collectAsStateWithLifecycle()
    val queue by viewModel.queue.collectAsStateWithLifecycle()
    val dspConfig by viewModel.dspConfig.collectAsStateWithLifecycle()
    val playbackSpeed by viewModel.playbackSpeed.collectAsStateWithLifecycle()
    val trackBpm by viewModel.trackBpm.collectAsStateWithLifecycle()
    val bpmLockEnabled by viewModel.isBpmLockEnabled.collectAsStateWithLifecycle()
    val lockTargetBpm by viewModel.lockTargetBpm.collectAsStateWithLifecycle()

    // Adaptive Farbwelt aus dem Cover; neutraler Fallback bis zur Bitmap.
    val artworkColors by rememberArtworkColors(state.contentUri)

    // Cache-Miss stoesst die aufschiebbare Analyse an (Plan Phase 2/3).
    LaunchedEffect(state.songId) {
        viewModel.requestAnalysis(state.songId)
    }

    // Ticker nur, solange dieser Screen in der Composition ist.
    LaunchedEffect(state.isVisible) {
        while (state.isVisible) {
            viewModel.refreshPosition()
            delay(POSITION_TICK_MS)
        }
    }

    var menuOpen by remember { mutableStateOf(false) }
    var createMarkerAtMs by remember { mutableStateOf<Long?>(null) }
    var deleteMarker by remember { mutableStateOf<SongMarker?>(null) }
    var showQuickEq by remember { mutableStateOf(false) }
    var showTempo by remember { mutableStateOf(false) }
    var showQueue by remember { mutableStateOf(false) }
    val shownPositionMs = livePosition ?: state.positionMs
    val screenHeightDp = LocalConfiguration.current.screenHeightDp
    // YTM-Layout: Cover kompakter (0.40 statt 0.52), damit die Controls
    // in der oberen Haelfte liegen und daumenerreichbar sind.
    val coverCarouselHeight = (screenHeightDp * 0.40f).dp.coerceIn(240.dp, 420.dp)
    val haptics = LocalHapticFeedback.current

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        NowPlayingBackground(
            contentUri = state.contentUri,
            scrim = artworkColors.scrim,
        )

        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                TopAppBar(
                    title = {},
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(
                                painterResource(BrandIcons.Back),
                                contentDescription = stringResource(R.string.now_playing_back),
                                tint = artworkColors.content,
                            )
                        }
                    },
                    actions = {
                        if (state.isVisible) {
                            IconButton(onClick = { menuOpen = true }) {
                                Icon(
                                    painterResource(BrandIcons.More),
                                    contentDescription = stringResource(R.string.now_playing_more),
                                    tint = artworkColors.content,
                                )
                            }
                            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.now_playing_add_marker)) },
                                    onClick = {
                                        createMarkerAtMs = shownPositionMs
                                        menuOpen = false
                                    },
                                )
                            }
                        }
                    },
                    colors =
                        TopAppBarDefaults.topAppBarColors(
                            containerColor = Color.Transparent,
                            scrolledContainerColor = Color.Transparent,
                            navigationIconContentColor = artworkColors.content,
                            actionIconContentColor = artworkColors.content,
                        ),
                )
            },
        ) { innerPadding ->
            if (!state.isVisible) {
                Box(
                    modifier = Modifier.fillMaxSize().padding(innerPadding).padding(horizontal = 24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(R.string.now_playing_empty),
                        style = MaterialTheme.typography.bodyLarge,
                        color = artworkColors.content,
                        textAlign = TextAlign.Center,
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding =
                        PaddingValues(
                            start = 16.dp,
                            top = innerPadding.calculateTopPadding() + 8.dp,
                            end = 16.dp,
                            bottom =
                                innerPadding.calculateBottomPadding() + contentPadding.calculateBottomPadding() + 24.dp,
                        ),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    item {
                        if (queue.items.isNotEmpty()) {
                            CoverTitleCarousel(
                                items = queue.items,
                                currentIndex = queue.currentIndex,
                                coverResolver = viewModel::coverUriFor,
                                onSelectPage = viewModel::playQueueItem,
                                contentColor = artworkColors.content,
                                onDismiss = onBack,
                                modifier = Modifier.fillMaxWidth().height(coverCarouselHeight),
                            )
                        } else {
                            CoverTitleStatic(
                                title = state.title,
                                artist = state.artist,
                                contentUri = state.contentUri,
                                contentColor = artworkColors.content,
                                onDismiss = onBack,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                    item {
                        WaveformProgress(
                            positionMs = shownPositionMs,
                            durationMs = state.durationMs,
                            playbackSpeed = playbackSpeed,
                            isPlaying = state.isPlaying,
                            waveformState = waveformState,
                            markers = markers,
                            colors = artworkColors,
                            hasPrevious = queue.currentIndex > 0,
                            hasNext = queue.currentIndex in 0 until queue.items.lastIndex,
                            onPrevious = viewModel::skipToPrevious,
                            onTogglePlayPause = viewModel::togglePlayPause,
                            onNext = viewModel::skipToNext,
                            onSeek = viewModel::seekTo,
                            onLongPressAt = { fraction ->
                                val duration = state.durationMs.coerceAtLeast(1L)
                                val markerFractions =
                                    markers.map { (it.positionMs.toFloat() / duration).coerceIn(0f, 1f) }
                                val nearest =
                                    WaveformMapping.nearestMarkerIndex(
                                        fractions = markerFractions,
                                        fraction = fraction,
                                        slop = MARKER_HIT_SLOP_FRACTION,
                                    )
                                if (nearest >= 0) {
                                    deleteMarker = markers[nearest]
                                } else {
                                    createMarkerAtMs = (fraction * duration).toLong()
                                }
                            },
                            onMoveMarker = { fraction ->
                                val duration = state.durationMs.coerceAtLeast(1L)
                                val positionMs = (fraction * duration).toLong()
                                val marker =
                                    markers.minByOrNull { abs(it.positionMs - positionMs) }
                                if (marker != null) {
                                    // Beat-Snap: naechster Beat bei nahem BPM.
                                    val snapped = MarkerSnapping.snapToBeat(positionMs, trackBpm)
                                    if (snapped != null && snapped != positionMs) {
                                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    }
                                    viewModel.moveMarker(marker.id, snapped ?: positionMs)
                                }
                            },
                        )
                    }
                    item {
                        PlayerActionRow(
                            speed = playbackSpeed,
                            eqEnabled = dspConfig.eq.enabled,
                            crossfadeSeconds = dspConfig.crossfadeSeconds,
                            markerCount = markers.size,
                            colors = artworkColors,
                            onOpenEq = { showQuickEq = true },
                            onOpenTempo = { showTempo = true },
                            onOpenQueue = { showQueue = true },
                            onAddMarker = {
                                // Beat-Snap auch beim Setzen aus dem Carousel.
                                val snapped = MarkerSnapping.snapToBeat(shownPositionMs, trackBpm)
                                if (snapped != null && snapped != shownPositionMs) {
                                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                }
                                createMarkerAtMs = snapped ?: shownPositionMs
                            },
                            onToggleCrossfade = { viewModel.setCrossfadeEnabled(dspConfig.crossfadeSeconds == 0) },
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        )
                    }
                    item {
                        MarkerStatusPanel(
                            markers = markers,
                            colors = artworkColors,
                            onAddMarker = { createMarkerAtMs = shownPositionMs },
                            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                        )
                    }
                }
            }
        }
    }

    createMarkerAtMs?.let { markerPositionMs ->
        CreateMarkerDialog(
            positionMs = markerPositionMs,
            onConfirm = { label ->
                viewModel.createMarker(label, markerPositionMs)
                createMarkerAtMs = null
            },
            onDismiss = { createMarkerAtMs = null },
        )
    }

    deleteMarker?.let { marker ->
        DeleteMarkerDialog(
            marker = marker,
            onConfirm = {
                viewModel.deleteMarker(marker.id)
                deleteMarker = null
            },
            onDismiss = { deleteMarker = null },
        )
    }

    if (showQuickEq) {
        QuickEqSheet(
            config = dspConfig,
            onDismiss = { showQuickEq = false },
            onSetEnabled = viewModel::setEqEnabled,
            onBandGainFinished = { index, gain -> viewModel.setEqBandGain(index, gain) },
        )
    }

    if (showTempo) {
        TempoSheet(
            speed = playbackSpeed,
            trackBpm = trackBpm,
            bpmLockEnabled = bpmLockEnabled,
            lockTargetBpm = lockTargetBpm,
            onDismiss = { showTempo = false },
            onSpeedSelected = {
                viewModel.setBpmLock(false)
                viewModel.setPlaybackSpeed(it)
            },
            onBpmLockChanged = viewModel::setBpmLock,
            onTargetBpmChanged = viewModel::setLockTargetBpm,
        )
    }

    if (showQueue) {
        val queueState by viewModel.queue.collectAsStateWithLifecycle()
        QueueSheet(
            state = queueState,
            onDismiss = { showQueue = false },
            onPlay = viewModel::playQueueItem,
            onMove = viewModel::moveQueueItem,
            onRemove = viewModel::removeQueueItem,
        )
    }
}

@Composable
private fun MarkerStatusPanel(
    markers: List<SongMarker>,
    colors: PlayerArtworkColors,
    onAddMarker: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        color = colors.content.copy(alpha = 0.10f),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painter = painterResource(BrandIcons.Marker),
                contentDescription = null,
                tint = colors.accent ?: MaterialTheme.colorScheme.primary,
            )
            Column(modifier = Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(
                    text = stringResource(R.string.now_playing_marker_status, markers.size),
                    style = MaterialTheme.typography.titleSmall,
                    color = colors.content,
                )
                Text(
                    text = stringResource(R.string.now_playing_marker_legend),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.contentMuted,
                )
            }
            TextButton(onClick = onAddMarker) {
                Text(stringResource(R.string.now_playing_marker_add_action))
            }
        }
    }
}

@Composable
private fun NowPlayingBackground(
    contentUri: String?,
    scrim: Color,
) {
    val context = LocalContext.current
    val cover by
        produceState<ImageBitmap?>(initialValue = null, contentUri) {
            value = contentUri?.let { CoverArtLoader.load(context, it, BACKGROUND_COVER_DIM_PX) }
        }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        cover?.let { bitmap ->
            Image(
                bitmap = bitmap,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier =
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            scaleX = 1.18f
                            scaleY = 1.18f
                        }.blur(48.dp),
            )
        }
        // Adaptiver Scrim aus der Cover-Farbwelt statt purem Schwarz.
        Box(modifier = Modifier.fillMaxSize().background(scrim.copy(alpha = SCRIM_ALPHA)))
    }
}

/**
 * Karussell aus Cover + Titel + Interpret ueber die Warteschlange. Ein Wisch
 * wechselt den Titel (Apple-artiger 3D-Uebergang). Beim Einrasten auf eine
 * neue Seite wechselt die Wiedergabe; ein externer Wechsel scrollt das
 * Karussell gleichauf. Vertikales Ziehen schliesst den Screen (Swipe-down-
 * dismiss) mit Feder-Rueckstellung, wenn die Schwelle nicht erreicht wird.
 */
@Composable
private fun CoverTitleCarousel(
    items: List<QueueItem>,
    currentIndex: Int,
    coverResolver: suspend (Long) -> String?,
    onSelectPage: (Int) -> Unit,
    contentColor: Color,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val startPage = currentIndex.coerceIn(0, items.lastIndex)
    val pagerState = rememberPagerState(initialPage = startPage) { items.size }

    // Externer Titelwechsel (Auto-Advance, Skip aus Mini-Player) fuehrt das
    // Karussell nach; kein neuer Play-Aufruf, da die Zielseite == currentIndex.
    LaunchedEffect(currentIndex, items.size) {
        val target = currentIndex.coerceIn(0, items.lastIndex)
        if (target != pagerState.currentPage) {
            pagerState.animateScrollToPage(target)
        }
    }
    // Nutzer-Wisch: sobald eine neue Seite eingerastet ist, dort abspielen.
    LaunchedEffect(pagerState, currentIndex) {
        snapshotFlow { pagerState.settledPage }.collect { page ->
            if (page != currentIndex && page in items.indices) {
                onSelectPage(page)
            }
        }
    }

    SwipeDismissBox(onDismiss = onDismiss, modifier = modifier) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            pageSpacing = 8.dp,
            contentPadding = PaddingValues(horizontal = 40.dp),
        ) { page ->
            val pageOffset = (pagerState.currentPage - page) + pagerState.currentPageOffsetFraction
            CoverTitlePage(
                item = items[page],
                coverResolver = coverResolver,
                contentColor = contentColor,
                modifier =
                    Modifier.graphicsLayer {
                        val absOffset = abs(pageOffset).coerceIn(0f, 1f)
                        cameraDistance = 8f * density
                        rotationY = pageOffset * -28f
                        alpha = 1f - absOffset
                        val scale = 1f - absOffset * 0.18f
                        scaleX = scale
                        scaleY = scale
                    },
            )
        }
    }
}

/**
 * Swipe-down-dismiss: vertikales Ziehen verschiebt und blendet den
 * Inhalt aus; ueber [DISMISS_THRESHOLD_DP] schliesst die Geste den
 * Screen, darunter federt sie zurueck. Umhuellt die Cover-Zone, wo
 * kein vertikales Scrollen konkurriert.
 */
@Composable
private fun SwipeDismissBox(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    var dragOffset by remember { mutableFloatStateOf(0f) }
    val animatedOffset by animateFloatAsState(
        targetValue = dragOffset,
        animationSpec =
            spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessMediumLow,
            ),
        label = "swipe_dismiss_offset",
    )
    val thresholdPx = with(LocalDensity.current) { DISMISS_THRESHOLD_DP.toPx() }

    Box(
        modifier =
            modifier.graphicsLayer {
                translationY = animatedOffset.coerceAtLeast(0f)
                val progress = (animatedOffset / thresholdPx).coerceIn(0f, 1f)
                alpha = 1f - progress * 0.6f
            },
    ) {
        content()
        // Unsichtbare Gesten-Schicht ueber dem Cover: faengt vertikale
        // Zuege, laesst horizontale Pager-Wische durch (detectVertical-
        // DragGestures konsumiert nur vertikal).
        Box(
            modifier =
                Modifier
                    .matchParentSize()
                    .pointerInput(Unit) {
                        detectVerticalDragGestures(
                            onVerticalDrag = { change, amount ->
                                change.consume()
                                dragOffset += amount
                            },
                            onDragEnd = {
                                if (dragOffset >= thresholdPx) {
                                    onDismiss()
                                }
                                dragOffset = 0f
                            },
                            onDragCancel = { dragOffset = 0f },
                        )
                    },
        )
    }
}

/** One carousel page: artwork, title and artist move as a unit. */
@Composable
private fun CoverTitlePage(
    item: QueueItem,
    coverResolver: suspend (Long) -> String?,
    contentColor: Color,
    modifier: Modifier = Modifier,
) {
    val coverUri by
        produceState<String?>(initialValue = null, item.songId) {
            value = item.songId?.let { coverResolver(it) }
        }
    CoverTitleStatic(
        title = item.title,
        artist = item.artist,
        contentUri = coverUri,
        contentColor = contentColor,
        onDismiss = null,
        modifier = modifier,
    )
}

/** Cover + Titel + Interpret ohne Wisch (Fallback bei leerer Warteschlange). */
@Composable
private fun CoverTitleStatic(
    title: String,
    artist: String?,
    contentUri: String?,
    contentColor: Color,
    onDismiss: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val body: @Composable () -> Unit = {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Hero-Auftritt: das Cover waechst beim Oeffnen des Screens aus
            // Mini-Player-Groesse auf (Feder), passend zur Sheet-Transition.
            var coverAppeared by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) { coverAppeared = true }
            val coverScale by animateFloatAsState(
                targetValue = if (coverAppeared) 1f else 0.72f,
                animationSpec =
                    spring(
                        dampingRatio = Spring.DampingRatioMediumBouncy,
                        stiffness = Spring.StiffnessMediumLow,
                    ),
                label = "now_playing_cover_scale",
            )
            val coverAlpha by animateFloatAsState(
                targetValue = if (coverAppeared) 1f else 0f,
                animationSpec = spring(stiffness = Spring.StiffnessMedium),
                label = "now_playing_cover_alpha",
            )
            CoverImage(
                contentUri = contentUri,
                contentDescription = stringResource(R.string.now_playing_cover),
                maxDimPx = NOW_PLAYING_COVER_DIM_PX,
                modifier =
                    Modifier
                        .widthIn(max = 400.dp)
                        .fillMaxWidth(0.86f)
                        .aspectRatio(1f)
                        .graphicsLayer {
                            scaleX = coverScale
                            scaleY = coverScale
                            alpha = coverAlpha
                        }.clip(RoundedCornerShape(24.dp))
                        .background(Color.White.copy(alpha = 0.12f)),
            ) {
                Icon(
                    painterResource(BrandIcons.NavMusic),
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.68f),
                    modifier = Modifier.fillMaxSize(0.4f),
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = contentColor,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
            artist?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyLarge,
                    color = contentColor.copy(alpha = 0.72f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
    if (onDismiss != null) {
        SwipeDismissBox(onDismiss = onDismiss, modifier = modifier) { body() }
    } else {
        Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) { body() }
    }
}

/**
 * Grosse Wellenform als Bedienflaeche (Poweramp/FlowRep-Optik): der
 * Play-/Pause-Knopf sitzt als runder Akzent-Kreis direkt auf der
 * Wellenform an der Fortschrittsposition. Shape-Morph beim Play/Pause-
 * Wechsel (Expressive-Ziel: sichtbarer State-Kontrast statt harter
 * Form). Zeiten adaptiv gefaerbt; Restzeit tempo-korrigiert.
 */
@Composable
private fun WaveformProgress(
    positionMs: Long,
    durationMs: Long,
    playbackSpeed: Float,
    isPlaying: Boolean,
    waveformState: WaveformUiState,
    markers: List<SongMarker>,
    colors: PlayerArtworkColors,
    hasPrevious: Boolean,
    hasNext: Boolean,
    onPrevious: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onNext: () -> Unit,
    onSeek: (Long) -> Unit,
    onLongPressAt: (Float) -> Unit,
    onMoveMarker: (Float) -> Unit,
) {
    var scrubPositionMs by remember { mutableStateOf<Long?>(null) }
    val shownPositionMs = scrubPositionMs ?: positionMs
    val safeDuration = durationMs.coerceAtLeast(1L)
    val progressFraction = (shownPositionMs.toFloat() / safeDuration).coerceIn(0f, 1f)
    // Tempo-korrigierte Restzeit: bei 1,5x dauert der Rest 1/1,5 der
    // Uhrzeit (Recherche: Scrubber-Ehrlichkeit bei Tempo != 1,0).
    val remainingMs = ((safeDuration - shownPositionMs).coerceAtLeast(0L) / playbackSpeed.coerceAtLeast(0.1f)).toLong()

    // Shape-Morph: Pause = Squircle, Play = Kreis; Feder-Physik.
    val playCorner by animateDpAsState(
        targetValue = if (isPlaying) 22.dp else 36.dp,
        animationSpec =
            spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessMedium,
            ),
        label = "play_button_corner",
    )

    Column(modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(WAVE_ZONE_HEIGHT),
            contentAlignment = Alignment.Center,
        ) {
            when (waveformState) {
                is WaveformUiState.Ready -> {
                    Waveform(
                        buckets = waveformState.buckets,
                        progressFraction = progressFraction,
                        onSeek = { fraction -> onSeek((fraction * safeDuration).toLong()) },
                        onScrubPreview = { fraction ->
                            scrubPositionMs = fraction?.let { (it * safeDuration).toLong() }
                        },
                        markerFractions =
                            markers.map { (it.positionMs.toFloat() / safeDuration).coerceIn(0f, 1f) },
                        onLongPress = { fraction -> onLongPressAt(fraction) },
                        onMoveMarker = onMoveMarker,
                        contentDescription =
                            stringResource(
                                R.string.now_playing_waveform_with_markers,
                                markers.size,
                            ),
                        modifier = Modifier.fillMaxSize(),
                    )
                }

                WaveformUiState.Loading -> {
                    WaveformPlaceholder(
                        contentDescription = stringResource(R.string.now_playing_waveform_loading),
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .height(64.dp),
                    )
                }

                WaveformUiState.Unavailable, WaveformUiState.Hidden -> {
                    SeekSlider(
                        shownPositionMs = shownPositionMs,
                        safeDuration = safeDuration,
                        onScrub = { scrubPositionMs = it },
                        onSeek = {
                            scrubPositionMs?.let(onSeek)
                            scrubPositionMs = null
                        },
                    )
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                IconButton(
                    onClick = onPrevious,
                    enabled = hasPrevious,
                    modifier = Modifier.size(56.dp),
                    colors =
                        IconButtonDefaults.iconButtonColors(
                            contentColor = colors.content,
                            disabledContentColor = colors.content.copy(alpha = 0.32f),
                        ),
                ) {
                    Icon(
                        painter = painterResource(BrandIcons.SkipPrevious),
                        contentDescription = stringResource(R.string.player_previous),
                        modifier = Modifier.size(30.dp),
                    )
                }
                FilledIconButton(
                    onClick = onTogglePlayPause,
                    modifier = Modifier.size(PLAY_BUTTON_SIZE),
                    shape = RoundedCornerShape(playCorner),
                    colors =
                        IconButtonDefaults.filledIconButtonColors(
                            containerColor = colors.accent ?: MaterialTheme.colorScheme.primary,
                            contentColor = colors.onAccent,
                        ),
                ) {
                    Icon(
                        painter = painterResource(if (isPlaying) BrandIcons.Pause else BrandIcons.Play),
                        contentDescription =
                            stringResource(if (isPlaying) R.string.player_pause else R.string.player_play),
                        modifier = Modifier.size(30.dp),
                    )
                }
                IconButton(
                    onClick = onNext,
                    enabled = hasNext,
                    modifier = Modifier.size(56.dp),
                    colors =
                        IconButtonDefaults.iconButtonColors(
                            contentColor = colors.content,
                            disabledContentColor = colors.content.copy(alpha = 0.32f),
                        ),
                ) {
                    Icon(
                        painter = painterResource(BrandIcons.SkipNext),
                        contentDescription = stringResource(R.string.player_next),
                        modifier = Modifier.size(30.dp),
                    )
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = formatTimeMs(shownPositionMs),
                style = MaterialTheme.typography.labelMedium,
                color = colors.contentMuted,
            )
            Text(
                text =
                    if (playbackSpeed != 1f) {
                        "-${formatTimeMs(remainingMs)} (${formatSpeed(playbackSpeed)})"
                    } else {
                        formatTimeMs(durationMs)
                    },
                style = MaterialTheme.typography.labelMedium,
                color = colors.contentMuted,
            )
        }
    }
}

/** Aktions-Carousel unter den Controls (YTM-2025-Muster). */
@Composable
private fun PlayerActionRow(
    speed: Float,
    eqEnabled: Boolean,
    crossfadeSeconds: Int,
    markerCount: Int,
    colors: PlayerArtworkColors,
    onOpenEq: () -> Unit,
    onOpenTempo: () -> Unit,
    onOpenQueue: () -> Unit,
    onAddMarker: () -> Unit,
    onToggleCrossfade: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ActionChip(
            label =
                stringResource(
                    if (eqEnabled) R.string.now_playing_action_eq_on else R.string.now_playing_action_eq,
                ),
            iconRes = BrandIcons.Equalizer,
            colors = colors,
            onClick = onOpenEq,
        )
        ActionChip(
            label = formatSpeed(speed),
            iconRes = BrandIcons.Waveform,
            colors = colors,
            onClick = onOpenTempo,
        )
        ActionChip(
            label = stringResource(R.string.now_playing_action_add_marker),
            iconRes = BrandIcons.Marker,
            colors = colors,
            onClick = onAddMarker,
        )
        ActionChip(
            label =
                if (crossfadeSeconds > 0) {
                    stringResource(R.string.now_playing_action_mix_on, crossfadeSeconds)
                } else {
                    stringResource(R.string.now_playing_action_mix_off)
                },
            iconRes = BrandIcons.Swap,
            colors = colors,
            onClick = onToggleCrossfade,
        )
        ActionChip(
            label = stringResource(R.string.now_playing_action_queue, markerCount),
            iconRes = BrandIcons.Queue,
            colors = colors,
            onClick = onOpenQueue,
        )
    }
}

@Composable
private fun ActionChip(
    label: String,
    iconRes: Int,
    colors: PlayerArtworkColors,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(50),
        color = colors.content.copy(alpha = 0.10f),
        contentColor = colors.content,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = colors.accent ?: MaterialTheme.colorScheme.primary,
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}

/**
 * Bestaetigungsdialog fuer einen neuen Marker (Phase 4): ein
 * versehentlicher Long-Press legt nie ungefragt einen Marker an.
 */
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
                Text(
                    text =
                        stringResource(
                            R.string.now_playing_marker_add_position,
                            formatTimeMs(positionMs),
                        ),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text(stringResource(R.string.now_playing_marker_label)) },
                    placeholder = { Text(stringResource(R.string.now_playing_marker_default_label)) },
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(label) }) {
                Text(stringResource(R.string.now_playing_marker_add_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.now_playing_marker_cancel))
            }
        },
    )
}

/**
 * Bestaetigungsdialog zum Loeschen eines Markers (Phase 8): Long-Press
 * nahe einem Tick fragt nach, bevor ein Marker verschwindet.
 */
@Composable
private fun DeleteMarkerDialog(
    marker: SongMarker,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.now_playing_marker_delete_title)) },
        text = {
            Text(
                text =
                    stringResource(
                        R.string.now_playing_marker_delete_message,
                        marker.label.ifEmpty { stringResource(R.string.now_playing_marker_default_label) },
                        formatTimeMs(marker.positionMs),
                    ),
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.now_playing_marker_delete_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.now_playing_marker_cancel))
            }
        },
    )
}

/** Klassische Zeitleiste als Fallback und waehrend der Analyse. */
@Composable
private fun SeekSlider(
    shownPositionMs: Long,
    safeDuration: Long,
    onScrub: (Long) -> Unit,
    onSeek: () -> Unit,
) {
    Slider(
        value = (shownPositionMs.toFloat() / safeDuration).coerceIn(0f, 1f),
        onValueChange = { fraction -> onScrub((fraction * safeDuration).toLong()) },
        onValueChangeFinished = onSeek,
        modifier = Modifier.fillMaxWidth(),
    )
}

private const val NOW_PLAYING_COVER_DIM_PX = 1024
private const val BACKGROUND_COVER_DIM_PX = 512
private const val SCRIM_ALPHA = 0.72f

/** mm:ss bzw. h:mm:ss bei Ueberlaenge; stabile Locale-unabhaengige Ziffern. */
private fun formatTimeMs(ms: Long): String {
    val totalSeconds = (ms.coerceAtLeast(0L)) / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.ROOT, "%d:%02d", minutes, seconds)
    }
}

private fun formatSpeed(speed: Float): String =
    if (abs(speed - 1f) < 0.005f) {
        "1x"
    } else {
        String.format(Locale.ROOT, "%.2fx", speed)
    }
