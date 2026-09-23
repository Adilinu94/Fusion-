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
internal data class NowPlayingPalette(
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
    // C10 (P-10): verlinkt die Marker-Review-Liste aus dem Overflow.
    onOpenMarkerReview: () -> Unit = {},
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
    // P2-21: Vorschlaege (unbestaetigte Onset-Kandidaten) und das bevorzugte
    // DropSync-Ziel des laufenden Songs — beide aus derselben Quelle wie die
    // Review-Liste bzw. die Planung.
    val suggestions by viewModel.nowPlayingSuggestions.collectAsStateWithLifecycle()
    val targetMarkerId by viewModel.targetMarkerId.collectAsStateWithLifecycle()
    val dropStatus by viewModel.dropStatus.collectAsStateWithLifecycle()
    val queue by viewModel.queue.collectAsStateWithLifecycle()
    val playbackSpeed by viewModel.playbackSpeed.collectAsStateWithLifecycle()
    val trackBpm by viewModel.trackBpm.collectAsStateWithLifecycle()
    val trackDownbeatOffsetMs by viewModel.trackDownbeatOffsetMs.collectAsStateWithLifecycle()
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

    // Paket 4.16: requestAnalysis ist nicht suspendierend (launcht intern) —
    // SideEffect mit Key statt LaunchedEffect spart die Coroutine pro Songwechsel.
    SideEffect(state.songId) { viewModel.requestAnalysis(state.songId) }

    var menuOpen by remember { mutableStateOf(false) }
    var createMarkerAtMs by remember { mutableStateOf<Long?>(null) }
    var showQueue by remember { mutableStateOf(false) }
    var showTempo by remember { mutableStateOf(false) }
    // P2-21: offenes Marker-Sheet (ID statt Objekt, damit Aenderungen aus
    // move/rename/confirm sofort im Sheet ankommen) und die Feinjustierung.
    var selectedMarkerId by remember { mutableStateOf<Long?>(null) }
    var markerEdit by remember { mutableStateOf<MarkerEditState?>(null) }
    val selectedMarker =
        selectedMarkerId?.let { id -> markers.find { it.id == id } ?: suggestions.find { it.id == id } }

    // B4: Undo-Texte im Composable-Kontext aufloesen (Gesten-Lambdas sind keiner).
    val scope = rememberCoroutineScope()
    val queueRemovedText = stringResource(R.string.player_queue_removed)
    val markerDeletedText = stringResource(R.string.player_marker_deleted)
    val undoText = stringResource(R.string.player_undo)
    // C2 (5.10): Hinweis der Skip-Doppelbestaetigung (erster Druck).
    val skipHintText = stringResource(R.string.player_skip_confirm_hint)
    // C10 (P-10): Bestaetigen wird quittiert und ist umkehrbar.
    val markerConfirmedText = stringResource(R.string.player_marker_confirmed)

    /**
     * B4: Zeigt nach einer loeschenden Aktion Undo an; bei Bestaetigung laeuft
     * das gemerkte Undo im ViewModel.
     */
    fun showUndo(
        message: String,
        hasUndo: Boolean,
        onUndo: () -> Unit,
    ) = showUndoSnackbar(
        scope = scope,
        snackbarHostState = snackbarHostState,
        undoText = undoText,
        message = message,
        hasUndo = hasUndo,
        onUndo = onUndo,
    )

    // C10 (P-10): Bestaetigen quittieren (Snackbar + Undo), wie das Loeschen.
    LaunchedEffect(viewModel) {
        viewModel.markerConfirmed.collect {
            showUndo(
                message = markerConfirmedText,
                hasUndo = viewModel.hasMarkerConfirmUndo(),
                onUndo = viewModel::undoConfirmMarker,
            )
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

    // P2-21: Anteile der Vorschlaege und des bevorzugten Ziels (gleiche
    // Einmal-Berechnung wie die Marker).
    val suggestionFractions =
        remember(suggestions, state.durationMs) {
            val duration = state.durationMs.coerceAtLeast(1L)
            suggestions.map { (it.positionMs.toFloat() / duration).coerceIn(0f, 1f) }
        }
    val targetFraction =
        remember(targetMarkerId, markers, state.durationMs) {
            val duration = state.durationMs.coerceAtLeast(1L)
            markers
                .firstOrNull { it.id == targetMarkerId }
                ?.let { (it.positionMs.toFloat() / duration).coerceIn(0f, 1f) }
        }

    val palette = rememberNowPlayingPalette(state.contentUri)

    /**
     * P2-21: oeffnet das Marker-Sheet fuer den Marker an [fraction];
     * false = kein Marker getroffen (Aufrufer entscheidet weiter).
     *
     * B4 (5.11): Beim Oeffnen rastet die Position automatisch auf das
     * Beat-Raster, aber nur bei gemessenem Offset (ohne Offset kein Snap,
     * das 0-ms-Raster waere geraten). Die Originalposition bleibt im
     * [MarkerEditState] erhalten ("Zurueck auf Original"), die Rastung
     * wird sofort uebernommen wie jede Feinjustierung.
     */
    fun openMarkerAt(fraction: Float): Boolean {
        val hit =
            markerAtFraction(fraction, markers, suggestions, markerFractions, suggestionFractions)
                ?: return false
        selectedMarkerId = hit.id
        markerEdit =
            markerEditOnOpen(hit.positionMs, trackBpm, trackDownbeatOffsetMs) { targetMs ->
                viewModel.moveMarker(hit.id, targetMs)
            }
        return true
    }

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
                    onOpenMarkerReview = {
                        menuOpen = false
                        onOpenMarkerReview()
                    },
                    queueSize = queue.items.size,
                )
                Spacer(Modifier.height(18.dp))
                PowerampModeRow(
                    repeatMode = state.repeatMode,
                    shuffleEnabled = state.shuffleEnabled,
                    hasPrevious = hasPrevious,
                    // C2 (5.10): Next bleibt auch bei scharfem Plan sichtbar;
                    // der erste Druck armirt nur (Hinweis), der zweite Druck
                    // springt — der Plan wird als Override mit Undo sichtbar
                    // zurueckgenommen. Die harte Sperre entfaellt.
                    hasNext = hasNext,
                    palette = palette,
                    onCycleRepeat = viewModel::cycleRepeat,
                    onToggleShuffle = viewModel::toggleShuffle,
                    onPrevious = viewModel::skipToPrevious,
                    onNext = {
                        if (viewModel.requestSkip() == SkipRequest.Hint) {
                            scope.launch { snackbarHostState.showSnackbar(skipHintText) }
                        }
                    },
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
                        // P2-21: Langdruck AUF einem Marker oeffnet das
                        // Marker-Sheet (wie der Tap); daneben setzt er einen
                        // neuen Marker (UI-Handbuch 15.3: nie sofort aktiv).
                        if (!openMarkerAt(fraction)) {
                            val duration = state.durationMs.coerceAtLeast(1L)
                            createMarkerAtMs = (fraction * duration).toLong()
                        }
                    },
                    onMarkerTap = { fraction -> openMarkerAt(fraction) },
                    // C1 (P-7): Retry fuer eine fehlgeschlagene Analyse.
                    onRetryAnalysis = { viewModel.requestAnalysis(state.songId) },
                    markerFractions = markerFractions,
                    suggestionFractions = suggestionFractions,
                    targetFraction = targetFraction,
                    onMoveMarker = { fraction ->
                        val duration = state.durationMs.coerceAtLeast(1L)
                        val targetMs = (fraction * duration).toLong()
                        markers.minByOrNull { abs(it.positionMs - targetMs) }?.let { marker ->
                            viewModel.moveMarker(marker.id, targetMs)
                        }
                    },
                    modifier = Modifier.weight(1f, fill = false),
                )
                // P2-21: Legende unter der Waveform (UI-Handbuch 14.3) — nur,
                // wenn es etwas zu unterscheiden gibt.
                MarkerLegendSection(
                    waveformReady = waveformState is WaveformUiState.Ready,
                    markers = markers,
                    suggestions = suggestions,
                    targetFraction = targetFraction,
                    palette = palette,
                    modifier = Modifier.padding(top = 6.dp),
                )
                // MP-7: DropSync-Statuszeile in derselben Sprache wie die
                // Train-Konsole; stumme Zustaende bleiben stumm. C1: Auch
                // Fehlschlaege werden genannt (quittierbar). C11: Bei einem
                // manuellen DropRest zeigt die Karte den Countdown — die
                // Zeile wuerde ihn doppeln und bleibt deshalb stumm.
                dropStatus
                    ?.takeIf {
                        it !is DropStatusLine.Ready || it.mode != DropSyncMode.UNTIL_MARKER
                    }?.let { status ->
                        DropSyncStatusRow(
                            status = status,
                            palette = palette,
                            onDismiss = viewModel::acknowledgePlanLost,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                // Paket 4.18: DropRestCard war orphaned — hier im Player
                // verdrahtet, wo der DropSync-Rest (Rest bis zum naechsten
                // Drop) hingehoert. Nur sichtbar, wenn ein Rest laeuft oder
                // startbar ist; der reine Blockadegrund bleibt dem Train-Tab
                // vorbehalten, damit der Player ruhig bleibt.
                DropRestSection(
                    modifier = Modifier.padding(top = 12.dp),
                    snackbarHostState = snackbarHostState,
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
                showUndo(
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

    // P2-21: Marker-Tap-Sheet — hoeren, feinjustieren, als DropSync-Ziel
    // waehlen, umbenennen, loeschen (UI-Handbuch 14.4/14.5).
    MarkerSheetHost(
        marker = selectedMarker,
        suggestions = suggestions,
        targetMarkerId = targetMarkerId,
        editState = markerEdit,
        durationMs = state.durationMs,
        allMarkers = markers,
        onEditStateChange = { markerEdit = it },
        onDismiss = {
            selectedMarkerId = null
            markerEdit = null
        },
        // C10 (P-10): Anhoeren mit 2,5 s Vorlauf inkl. Wiedergabe.
        onListen = viewModel::listenToMarker,
        onListenMarker = { entry -> viewModel.listenToMarker(entry.positionMs) },
        onMove = viewModel::moveMarker,
        onChooseTarget = viewModel::setDropTarget,
        onClearTarget = viewModel::clearDropTarget,
        onConfirm = viewModel::confirmMarker,
        onRename = viewModel::renameMarker,
        onDelete = { deleted ->
            selectedMarkerId = null
            markerEdit = null
            viewModel.deleteMarker(deleted.id)
            showUndo(
                message = markerDeletedText,
                hasUndo = viewModel.hasMarkerUndo(),
                onUndo = viewModel::undoDeleteMarker,
            )
        },
    )
}

/**
 * B4/C10: Undo-Snackbar (loeschen, bestaetigen). Top-level, damit
 * [NowPlayingScreen] nicht weiter an Verzweigungen waechst (Detekt:
 * CyclomaticComplexMethod) — die Verzweigungen liegen hier, nicht im Screen.
 */
private fun showUndoSnackbar(
    scope: CoroutineScope,
    snackbarHostState: SnackbarHostState,
    undoText: String,
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

/**
 * P2-21: haelt das Marker-Sheet offen und verdrahtet seine Aktionen — als
 * eigene Composable-Funktion, damit [NowPlayingScreen] nicht weiter an
 * Verzweigungen waechst (Detekt: CyclomaticComplexMethod).
 */
@Composable
private fun MarkerSheetHost(
    marker: SongMarker?,
    suggestions: List<SongMarker>,
    targetMarkerId: Long?,
    editState: MarkerEditState?,
    durationMs: Long,
    allMarkers: List<SongMarker>,
    onEditStateChange: (MarkerEditState?) -> Unit,
    onDismiss: () -> Unit,
    onListen: (Long) -> Unit,
    onListenMarker: (SongMarker) -> Unit,
    onMove: (Long, Long) -> Unit,
    onChooseTarget: (Long) -> Unit,
    onClearTarget: () -> Unit,
    onConfirm: (Long) -> Unit,
    onRename: (Long, String) -> Unit,
    onDelete: (SongMarker) -> Unit,
) {
    val current = marker ?: return
    val edit = editState ?: MarkerEditState.of(current.positionMs)
    MarkerSheet(
        marker = current,
        isSuggestion = suggestions.any { it.id == current.id },
        isTarget = current.id == targetMarkerId,
        editState = edit,
        onDismiss = onDismiss,
        onListen = { onListen(edit.editedPositionMs) },
        onAdjust = { deltaMs ->
            val next = edit.adjustedBy(deltaMs, durationMs)
            onEditStateChange(next)
            onMove(current.id, next.editedPositionMs)
        },
        onRevert = {
            val reverted = edit.reverted()
            onEditStateChange(reverted)
            onMove(current.id, reverted.editedPositionMs)
        },
        onChooseTarget = { onChooseTarget(current.id) },
        onClearTarget = onClearTarget,
        onConfirm = { onConfirm(current.id) },
        onRename = { label -> onRename(current.id, label) },
        onDelete = { onDelete(current) },
        allMarkers = allMarkers,
        onListenMarker = onListenMarker,
    )
}

/**
 * P2-21: Marker oder Vorschlag unter der angetippten/gedrueckten Stelle.
 * Trefferzone wie beim Long-Press (6 % der Breite); bestaetigte Marker
 * gewinnen gegen Vorschlaege.
 */
private fun markerAtFraction(
    fraction: Float,
    markers: List<SongMarker>,
    suggestions: List<SongMarker>,
    markerFractions: List<Float>,
    suggestionFractions: List<Float>,
): SongMarker? {
    val hit = WaveformMapping.nearestMarkerIndex(markerFractions, fraction, MARKER_HIT_SLOP_FRACTION)
    if (hit >= 0) return markers.getOrNull(hit)
    val suggestionHit =
        WaveformMapping.nearestMarkerIndex(suggestionFractions, fraction, MARKER_HIT_SLOP_FRACTION)
    return suggestions.getOrNull(suggestionHit)
}

/**
 * B4 (5.11): Feinjustierungs-Zustand beim Oeffnen des Marker-Sheets —
 * rastet automatisch auf das Beat-Raster, aber nur mit gemessenem Offset
 * (ohne Offset kein Snap, das 0-ms-Raster waere geraten). Die
 * Originalposition bleibt fuer "Zurueck auf Original" erhalten; eine
 * Rastung wird wie jede Feinjustierung sofort uebernommen ([persist]).
 */
private fun markerEditOnOpen(
    positionMs: Long,
    bpm: Float?,
    downbeatOffsetMs: Long?,
    persist: (Long) -> Unit,
): MarkerEditState {
    val snapped = MarkerSnapping.snapToBeat(positionMs, bpm, downbeatOffsetMs)
    val edit =
        if (snapped != null) {
            MarkerEditState.of(positionMs).snappedTo(snapped)
        } else {
            MarkerEditState.of(positionMs)
        }
    if (edit.isDirty) persist(edit.editedPositionMs)
    return edit
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
internal fun PowerampTitleRow(
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
    onOpenMarkerReview: () -> Unit,
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
                // C10 (P-10): Einstieg in die Review-Liste (Music Home).
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.now_playing_marker_review)) },
                    onClick = onOpenMarkerReview,
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
 * Paket 4.18: verdrahtet die zuvor orphaned [DropRestCard]. Sichtbar,
 * solange ein DropSync-Rest laeuft oder startbar ist; sonst bleibt der
 * Player ruhig (der reine Blockadegrund gehoert in den Train-Tab).
 */
@Composable
private fun DropRestSection(
    modifier: Modifier = Modifier,
    snackbarHostState: SnackbarHostState,
) {
    val dropRestViewModel: DropRestViewModel = hiltViewModel()
    val timer by dropRestViewModel.timerState.collectAsStateWithLifecycle()
    val eligibility by dropRestViewModel.eligibility.collectAsStateWithLifecycle()
    // Befund 6.2: Start-Fehlschlag sichtbar machen (vorher toter Knopf).
    val startFailedText = stringResource(R.string.drop_rest_start_failed)
    LaunchedEffect(dropRestViewModel) {
        dropRestViewModel.startFailed.collect {
            snackbarHostState.showSnackbar(startFailedText)
        }
    }
    val dropSyncActive =
        timer.session?.mode == TimerMode.DROPSYNC &&
            timer.status in
            setOf(
                TimerStatus.PREPARING,
                TimerStatus.RUNNING,
                TimerStatus.COMPLETED,
                TimerStatus.CANCELLED,
                TimerStatus.FAILED,
            )
    if (dropSyncActive || eligibility is DropRestEligibility.Eligible) {
        DropRestCard(modifier = modifier, viewModel = dropRestViewModel)
    }
}

/**
 * Optionsreihe mit vier flachen Slots wie in der Referenz (keine Cards,
 * keine Labels): Previous, Repeat, Shuffle, Next. Die Queue bleibt im
 * Overflow-Menue; Sleep Timer und Visualizer sind bewusst nicht Teil des
 * Produktumfangs.
 */
@Composable
internal fun PowerampModeRow(
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
    val reducedMotion = rememberReducedMotion()
    val scale by animateFloatAsState(
        targetValue = if (active) 1.1f else 1f,
        animationSpec =
            if (reducedMotion) {
                snap()
            } else {
                spring(dampingRatio = Spring.DampingRatioMediumBouncy)
            },
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
 * P2-21 (MP-7): DropSync-Statuszeile unter der Waveform in der Sprache der
 * Train-Konsole. TalkBack hoert einen ganzen Satz (UI-Handbuch 19.3), nicht
 * nur einen Lime-Punkt. C1: Fehlschlag-Zeilen nennen den Grund und lassen
 * sich quittieren ("Plan verloren").
 */
@Composable
internal fun DropSyncStatusRow(
    status: DropStatusLine,
    palette: NowPlayingPalette,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val label =
        when (status) {
            is DropStatusLine.Ready -> stringResource(R.string.now_playing_drop_ready)
            is DropStatusLine.BestEffort -> stringResource(R.string.now_playing_drop_best_effort)
            DropStatusLine.Overridden -> stringResource(R.string.now_playing_drop_overridden)
            is DropStatusLine.Failed -> stringResource(dropFailureLabelRes(status.reason))
        }
    val a11y =
        when (status) {
            is DropStatusLine.Ready -> {
                val spoken = spokenDuration(status.remainingMs)
                if (status.markerLabel.isBlank()) {
                    stringResource(R.string.now_playing_drop_a11y_no_marker, status.songTitle, spoken)
                } else {
                    stringResource(
                        R.string.now_playing_drop_a11y,
                        status.songTitle,
                        status.markerLabel,
                        spoken,
                    )
                }
            }

            is DropStatusLine.BestEffort -> {
                stringResource(R.string.now_playing_drop_a11y_best_effort)
            }

            DropStatusLine.Overridden -> {
                stringResource(R.string.now_playing_drop_a11y_overridden)
            }

            is DropStatusLine.Failed -> {
                stringResource(dropFailureA11yRes(status.reason))
            }
        }
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .widthIn(max = 900.dp)
                .semantics(mergeDescendants = true) { contentDescription = a11y },
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = palette.accent,
        )
        when (status) {
            is DropStatusLine.Ready -> {
                val line =
                    if (status.markerLabel.isBlank()) {
                        stringResource(
                            R.string.now_playing_drop_line_no_marker,
                            status.songTitle,
                            formatClockMs(status.remainingMs),
                        )
                    } else {
                        stringResource(
                            R.string.now_playing_drop_line,
                            status.songTitle,
                            status.markerLabel,
                            formatClockMs(status.remainingMs),
                        )
                    }
                Text(text = line, style = MaterialTheme.typography.bodySmall, color = palette.contentMuted)
            }

            is DropStatusLine.Failed -> {
                Text(
                    text = stringResource(dropFailureDetailRes(status.reason)),
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.contentMuted,
                )
                TextButton(onClick = onDismiss) {
                    Text(text = stringResource(R.string.now_playing_drop_dismiss))
                }
            }

            else -> {
                Unit
            }
        }
    }
}

/** C1: Label der Fehlschlag-Zeile je Grund. */
private fun dropFailureLabelRes(reason: DropSyncFailureReason): Int =
    when (reason) {
        DropSyncFailureReason.NO_REST_PLAYLIST -> R.string.now_playing_drop_failed_no_rest_playlist
        DropSyncFailureReason.NO_WORK_DROP -> R.string.now_playing_drop_failed_no_work_drop
        DropSyncFailureReason.REST_TOO_SHORT -> R.string.now_playing_drop_failed_rest_too_short
        DropSyncFailureReason.PLAYBACK_ERROR -> R.string.now_playing_drop_failed_playback
        DropSyncFailureReason.PLAN_LOST -> R.string.now_playing_drop_failed_plan_lost
    }

/** C1: Detailzeile je Fehlschlag-Grund (was der Nutzer tun kann). */
private fun dropFailureDetailRes(reason: DropSyncFailureReason): Int =
    when (reason) {
        DropSyncFailureReason.NO_REST_PLAYLIST -> R.string.now_playing_drop_failed_detail_no_rest_playlist
        DropSyncFailureReason.NO_WORK_DROP -> R.string.now_playing_drop_failed_detail_no_work_drop
        DropSyncFailureReason.REST_TOO_SHORT -> R.string.now_playing_drop_failed_detail_rest_too_short
        DropSyncFailureReason.PLAYBACK_ERROR -> R.string.now_playing_drop_failed_detail_playback
        DropSyncFailureReason.PLAN_LOST -> R.string.now_playing_drop_failed_detail_plan_lost
    }

/** C1: TalkBack-Satz je Fehlschlag-Grund. */
private fun dropFailureA11yRes(reason: DropSyncFailureReason): Int =
    when (reason) {
        DropSyncFailureReason.NO_REST_PLAYLIST -> R.string.now_playing_drop_a11y_failed_no_rest_playlist
        DropSyncFailureReason.NO_WORK_DROP -> R.string.now_playing_drop_a11y_failed_no_work_drop
        DropSyncFailureReason.REST_TOO_SHORT -> R.string.now_playing_drop_a11y_failed_rest_too_short
        DropSyncFailureReason.PLAYBACK_ERROR -> R.string.now_playing_drop_a11y_failed_playback
        DropSyncFailureReason.PLAN_LOST -> R.string.now_playing_drop_a11y_failed_plan_lost
    }

/** Dauer als gesprochener Satz ("1 Minute 27 Sekunden", UI-Handbuch 19.3). */
@Composable
private fun spokenDuration(ms: Long): String {
    val totalSeconds = ms.coerceAtLeast(0L) / 1000
    val minutes = (totalSeconds / 60).toInt()
    val seconds = (totalSeconds % 60).toInt()
    return if (minutes > 0) {
        val minutePart = pluralStringResource(R.plurals.now_playing_duration_minutes, minutes, minutes)
        val secondPart = pluralStringResource(R.plurals.now_playing_duration_seconds, seconds, seconds)
        "$minutePart $secondPart"
    } else {
        pluralStringResource(R.plurals.now_playing_duration_seconds, seconds, seconds)
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
                Text(stringResource(R.string.now_playing_marker_add_position, formatClockMs(positionMs)))
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
