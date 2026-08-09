package com.dropsync.feature.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dropsync.core.designsystem.icon.BrandIcons
import com.dropsync.domain.timer.CancelReason
import com.dropsync.domain.timer.DropRestBlockReason
import com.dropsync.domain.timer.DropRestEligibility
import com.dropsync.domain.timer.TimerMode
import com.dropsync.domain.timer.TimerStatus

/**
 * "Rest bis zum naechsten Drop" (Schritt 11/12): zeigt die effektive,
 * nicht editierbare Dauer (11.3) oder den Blockadegrund (11.2) und den
 * laufenden DropSync-Timer mit Abbruch (11.4). Beruehrungsziele
 * mindestens 48 dp (12.5); Status per stateDescription statt
 * Dauerbeschallung (12.4).
 */
@Composable
fun DropRestCard(
    modifier: Modifier = Modifier,
    viewModel: DropRestViewModel = hiltViewModel(),
) {
    val eligibility by viewModel.eligibility.collectAsStateWithLifecycle()
    val timer by viewModel.timerState.collectAsStateWithLifecycle()

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

    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    painterResource(BrandIcons.RestDropSync),
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
                Text(
                    text = stringResource(R.string.drop_rest_title),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            if (dropSyncActive) {
                ActiveDropRest(viewModel)
            } else {
                IdleDropRest(eligibility, viewModel)
            }
        }
    }
}

@Composable
private fun ActiveDropRest(viewModel: DropRestViewModel) {
    val timer by viewModel.timerState.collectAsStateWithLifecycle()
    val remainingSeconds = (timer.remainingMs + 999) / 1000

    val statusText =
        when (timer.status) {
            TimerStatus.PREPARING, TimerStatus.RUNNING -> {
                stringResource(R.string.drop_rest_running, remainingSeconds)
            }

            TimerStatus.COMPLETED -> {
                stringResource(R.string.drop_rest_completed)
            }

            TimerStatus.CANCELLED -> {
                if (timer.cancelReason == CancelReason.PLAYBACK_INTERRUPTED) {
                    stringResource(R.string.drop_rest_interrupted)
                } else {
                    stringResource(R.string.drop_rest_cancelled)
                }
            }

            else -> {
                stringResource(R.string.drop_rest_cancelled)
            }
        }
    Text(
        text = statusText,
        style = MaterialTheme.typography.headlineSmall,
        modifier = Modifier.semantics { stateDescription = statusText },
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (timer.status == TimerStatus.RUNNING || timer.status == TimerStatus.PREPARING) {
            OutlinedButton(
                onClick = viewModel::cancelDropRest,
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text(stringResource(R.string.drop_rest_cancel))
            }
        } else {
            Button(
                onClick = viewModel::acknowledgeEnd,
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text(stringResource(R.string.drop_rest_ok))
            }
        }
    }
}

@Composable
private fun IdleDropRest(
    eligibility: DropRestEligibility,
    viewModel: DropRestViewModel,
) {
    when (eligibility) {
        is DropRestEligibility.Eligible -> {
            val seconds = (eligibility.effectiveDurationMs + 999) / 1000
            Text(
                text = stringResource(R.string.drop_rest_effective_duration, seconds),
                style = MaterialTheme.typography.bodyMedium,
            )
            Button(
                onClick = viewModel::startDropRest,
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text(stringResource(R.string.drop_rest_start))
            }
        }

        is DropRestEligibility.Ineligible -> {
            // Abnahme 3 (Schritt 11): erkennbar deaktiviert mit Grund.
            Text(
                text =
                    stringResource(
                        when (eligibility.reason) {
                            DropRestBlockReason.NO_CURRENT_SONG -> {
                                R.string.drop_rest_blocked_no_song
                            }

                            DropRestBlockReason.PLAYBACK_NOT_RUNNING -> {
                                R.string.drop_rest_blocked_not_playing
                            }

                            DropRestBlockReason.NO_FUTURE_MARKER -> {
                                R.string.drop_rest_blocked_no_marker
                            }

                            DropRestBlockReason.MARKER_TOO_CLOSE -> {
                                R.string.drop_rest_blocked_too_close
                            }
                        },
                    ),
                style = MaterialTheme.typography.bodyMedium,
            )
            Button(
                onClick = {},
                enabled = false,
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text(stringResource(R.string.drop_rest_start))
            }
        }
    }
}
