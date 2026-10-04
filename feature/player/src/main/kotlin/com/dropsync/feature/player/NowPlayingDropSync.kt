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

/**
 * Paket 4.18: verdrahtet die zuvor orphaned [DropRestCard]. Sichtbar,
 * solange ein DropSync-Rest laeuft oder startbar ist; sonst bleibt der
 * Player ruhig (der reine Blockadegrund gehoert in den Train-Tab).
 */
@Composable
internal fun DropRestSection(
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
