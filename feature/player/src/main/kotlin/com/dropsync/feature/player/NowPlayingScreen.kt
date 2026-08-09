package com.dropsync.feature.player

import androidx.compose.foundation.background
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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dropsync.core.designsystem.chart.Waveform
import com.dropsync.core.designsystem.chart.WaveformPlaceholder
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
private val WAVE_ZONE_HEIGHT = 150.dp

/** Durchmesser des mittigen Play-/Pause-Knopfs auf der Wellenform. */
private val PLAY_BUTTON_SIZE = 74.dp

/**
 * Now-Playing-Screen im FlowRep-/Poweramp-Stil: grosses rundes Cover in
 * einem 3D-Swipe-Karussell (Cover, Titel, Interpret wandern gemeinsam),
 * Titel in der Akzentfarbe, kein Vor/Zurueck (Titelwechsel per Wisch), und
 * eine grosse Wellenform mit dem Play-/Pause-Knopf mittig darauf.
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
    val shownPositionMs = livePosition ?: state.positionMs

    Scaffold(
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            painterResource(BrandIcons.Back),
                            contentDescription = stringResource(R.string.now_playing_back),
                        )
                    }
                },
                actions = {
                    if (state.isVisible) {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(
                                painterResource(BrandIcons.More),
                                contentDescription = stringResource(R.string.now_playing_more),
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
            )
        },
    ) { innerPadding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(bottom = contentPadding.calculateBottomPadding())
                    .padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            if (!state.isVisible) {
                Text(
                    text = stringResource(R.string.now_playing_empty),
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                )
                return@Column
            }

            if (queue.items.isNotEmpty()) {
                CoverTitleCarousel(
                    items = queue.items,
                    currentIndex = queue.currentIndex,
                    coverResolver = viewModel::coverUriFor,
                    onSelectPage = viewModel::playQueueItem,
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                CoverTitleStatic(
                    title = state.title,
                    artist = state.artist,
                    contentUri = state.contentUri,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Spacer(modifier = Modifier.height(28.dp))

            WaveformProgress(
                positionMs = shownPositionMs,
                durationMs = state.durationMs,
                isPlaying = state.isPlaying,
                waveformState = waveformState,
                markers = markers,
                onTogglePlayPause = viewModel::togglePlayPause,
                onSeek = viewModel::seekTo,
                onLongPressAt = { createMarkerAtMs = it },
            )
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
}

/**
 * Karussell aus Cover + Titel + Interpret ueber die Warteschlange. Ein Wisch
 * wechselt den Titel (Apple-artiger 3D-Uebergang: die aktuelle Seite dreht
 * und blendet aus, die naechste blendet ein). Beim Einrasten auf eine neue
 * Seite wechselt die Wiedergabe; ein externer Wechsel scrollt das Karussell
 * gleichauf, ohne eine neue Wiedergabe auszuloesen.
 */
@Composable
private fun CoverTitleCarousel(
    items: List<QueueItem>,
    currentIndex: Int,
    coverResolver: suspend (Long) -> String?,
    onSelectPage: (Int) -> Unit,
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

    HorizontalPager(
        state = pagerState,
        modifier = modifier,
        pageSpacing = 8.dp,
        contentPadding = PaddingValues(horizontal = 40.dp),
    ) { page ->
        val pageOffset = (pagerState.currentPage - page) + pagerState.currentPageOffsetFraction
        CoverTitlePage(
            item = items[page],
            coverResolver = coverResolver,
            modifier =
                Modifier.graphicsLayer {
                    val absOffset = abs(pageOffset).coerceIn(0f, 1f)
                    // Perspektive fuer die Kartendrehung; leichter Winkel reicht.
                    cameraDistance = 8f * density
                    rotationY = pageOffset * -28f
                    // Aus-/Einblenden und dezentes Verkleinern beim Verlassen.
                    alpha = 1f - absOffset
                    val scale = 1f - absOffset * 0.18f
                    scaleX = scale
                    scaleY = scale
                },
        )
    }
}

/** Eine Karussellseite: rundes Cover, Titel in Akzentfarbe, Interpret. */
@Composable
private fun CoverTitlePage(
    item: QueueItem,
    coverResolver: suspend (Long) -> String?,
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
        modifier = modifier,
    )
}

/** Cover + Titel + Interpret ohne Wisch (Fallback bei leerer Warteschlange). */
@Composable
private fun CoverTitleStatic(
    title: String,
    artist: String?,
    contentUri: String?,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CoverImage(
            contentUri = contentUri,
            contentDescription = stringResource(R.string.now_playing_cover),
            maxDimPx = NOW_PLAYING_COVER_DIM_PX,
            modifier =
                Modifier
                    .fillMaxWidth(0.82f)
                    .aspectRatio(1f)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        ) {
            Icon(
                painterResource(BrandIcons.NavMusic),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxSize(0.4f),
            )
        }
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
        artist?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * Grosse Wellenform als Bedienflaeche mit dem Play-/Pause-Knopf mittig
 * darauf (Poweramp/FlowRep-Optik). Waehrend der Analyse pulsiert ein
 * Platzhalter, ohne Analyse bleibt die klassische Zeitleiste; Tap springt,
 * Long-Press meldet die Position fuer einen Marker.
 */
@Composable
private fun WaveformProgress(
    positionMs: Long,
    durationMs: Long,
    isPlaying: Boolean,
    waveformState: WaveformUiState,
    markers: List<SongMarker>,
    onTogglePlayPause: () -> Unit,
    onSeek: (Long) -> Unit,
    onLongPressAt: (Long) -> Unit,
) {
    var scrubPositionMs by remember { mutableStateOf<Long?>(null) }
    val shownPositionMs = scrubPositionMs ?: positionMs
    val safeDuration = durationMs.coerceAtLeast(1L)

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
                        progressFraction = (shownPositionMs.toFloat() / safeDuration).coerceIn(0f, 1f),
                        onSeek = { fraction -> onSeek((fraction * safeDuration).toLong()) },
                        onScrubPreview = { fraction ->
                            scrubPositionMs = fraction?.let { (it * safeDuration).toLong() }
                        },
                        markerFractions =
                            markers.map { (it.positionMs.toFloat() / safeDuration).coerceIn(0f, 1f) },
                        onLongPress = { fraction ->
                            onLongPressAt((fraction * safeDuration).toLong())
                        },
                        contentDescription = stringResource(R.string.now_playing_waveform),
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

            // Play-/Pause-Knopf mittig auf der Wellenform (Akzentfarbe).
            FilledIconButton(
                onClick = onTogglePlayPause,
                modifier =
                    Modifier
                        .align(Alignment.Center)
                        .size(PLAY_BUTTON_SIZE),
                colors =
                    IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    ),
            ) {
                Icon(
                    painter =
                        painterResource(if (isPlaying) BrandIcons.Pause else BrandIcons.Play),
                    contentDescription =
                        stringResource(if (isPlaying) R.string.player_pause else R.string.player_play),
                    modifier = Modifier.size(32.dp),
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = formatTimeMs(shownPositionMs),
                style = MaterialTheme.typography.labelMedium,
            )
            Text(
                text = formatTimeMs(durationMs),
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
