package com.dropsync.feature.workout

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dropsync.core.designsystem.chart.BarChart
import com.dropsync.core.designsystem.chart.LineChart
import com.dropsync.core.model.PrType
import com.dropsync.core.model.PrValueUnit
import com.dropsync.domain.workout.PrRecord
import com.dropsync.domain.workout.ProgressClassification
import com.dropsync.domain.workout.ProgressStatus
import com.dropsync.domain.workout.ProgressSuggestion

/**
 * Uebungsdetail (Abschnitt 3): drei getrennte PR-Kategorien, 1RM-Trend
 * (nie als PR ausgewiesen), Volumen ueber Zeit, Gewichtsverlauf sowie
 * Klassifizierungs-Badge mit Plateau-Hinweis und Vorschlag.
 */
@Composable
fun ExerciseDetailScreen(
    contentPadding: PaddingValues,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ExerciseDetailViewModel = hiltViewModel(),
) {
    val detail by viewModel.detail.collectAsStateWithLifecycle()
    val personalRecords by viewModel.personalRecords.collectAsStateWithLifecycle()
    val points by viewModel.points.collectAsStateWithLifecycle()
    val classification by viewModel.classification.collectAsStateWithLifecycle()

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = contentPadding,
    ) {
        item {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = detail?.displayName ?: "",
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onBack) {
                        Text(stringResource(R.string.workout_back))
                    }
                }
                val current = detail
                if (current != null) {
                    Text(
                        text =
                            current.equipment.name + " - " +
                                current.muscles.joinToString { "${it.group.name} ${it.percent}%" },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                classification?.let { ClassificationBadge(it) }
            }
        }
        item {
            Column(Modifier.padding(horizontal = 16.dp)) {
                Text(
                    text = stringResource(R.string.detail_prs_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                // Drei getrennte Kategorien; geschaetztes 1RM ist bewusst
                // KEINE davon (Abschnitt 3).
                PrLine(PrType.HIGHEST_LOAD, personalRecords)
                PrLine(PrType.HIGHEST_SESSION_VOLUME, personalRecords)
                PrLine(PrType.MOST_REPS_AT_LOAD, personalRecords)
            }
        }
        if (points.isEmpty()) {
            item {
                Text(
                    text = stringResource(R.string.detail_no_data),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(16.dp),
                )
            }
        } else {
            item {
                val oneRmSeries =
                    points.map { (it.bestEstimatedOneRmMilliKg ?: 0L).toFloat() / 1000f }
                ChartCard(
                    title = stringResource(R.string.detail_trend_1rm),
                ) {
                    LineChart(
                        values = oneRmSeries,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .height(160.dp),
                        contentDescription =
                            stringResource(
                                R.string.detail_chart_summary,
                                points.size,
                                formatKg((points.last().bestEstimatedOneRmMilliKg ?: 0L)),
                            ),
                    )
                }
            }
            item {
                ChartCard(title = stringResource(R.string.detail_volume)) {
                    BarChart(
                        values = points.map { it.totalVolumeMilliKg.toFloat() / 1000f },
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .height(160.dp),
                        contentDescription =
                            stringResource(
                                R.string.detail_chart_summary,
                                points.size,
                                formatKg(points.last().totalVolumeMilliKg),
                            ),
                    )
                }
            }
            item {
                ChartCard(title = stringResource(R.string.detail_weight_history)) {
                    LineChart(
                        values = points.map { it.maxEffectiveLoadMilliKg.toFloat() / 1000f },
                        fill = false,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .height(160.dp),
                        contentDescription =
                            stringResource(
                                R.string.detail_chart_summary,
                                points.size,
                                formatKg(points.last().maxEffectiveLoadMilliKg),
                            ),
                    )
                }
            }
        }
    }
}

@Composable
private fun ChartCard(
    title: String,
    content: @Composable () -> Unit,
) {
    Card(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(text = title, style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
private fun PrLine(
    type: PrType,
    records: List<PrRecord>,
) {
    val record = records.firstOrNull { it.type == type }
    val label =
        when (type) {
            PrType.HIGHEST_LOAD -> stringResource(R.string.detail_pr_highest_load)
            PrType.HIGHEST_SESSION_VOLUME -> stringResource(R.string.detail_pr_session_volume)
            PrType.MOST_REPS_AT_LOAD -> stringResource(R.string.detail_pr_reps_at_load)
        }
    val value =
        when {
            record == null -> {
                stringResource(R.string.detail_pr_none)
            }

            record.valueUnit == PrValueUnit.MILLI_KG -> {
                "${formatKg(record.valueLong)} kg"
            }

            else -> {
                val load = record.comparableLoadMilliKg
                val suffix = load?.let { " @ ${formatKg(it)} kg" } ?: ""
                "${record.valueLong} x$suffix"
            }
        }
    Text(
        text = "$label: $value",
        style = MaterialTheme.typography.bodyMedium,
    )
}

/** Badge progressiv/stagnierend/ruecklaeufig + Plateau + Vorschlag. */
@Composable
private fun ClassificationBadge(classification: ProgressClassification) {
    val statusText =
        when (classification.status) {
            ProgressStatus.PROGRESSING -> stringResource(R.string.detail_status_progressing)
            ProgressStatus.STAGNATING -> stringResource(R.string.detail_status_stagnating)
            ProgressStatus.DECLINING -> stringResource(R.string.detail_status_declining)
        }
    val suggestionText =
        when (classification.suggestion) {
            ProgressSuggestion.KEEP_GOING -> stringResource(R.string.suggestion_keep_going)
            ProgressSuggestion.DELOAD -> stringResource(R.string.suggestion_deload)
            ProgressSuggestion.INCREASE_VOLUME -> stringResource(R.string.suggestion_increase_volume)
            ProgressSuggestion.CHANGE_VARIATION -> stringResource(R.string.suggestion_change_variation)
        }
    Column {
        // Keine Funktion nur ueber Farbe: Status steht als Text (12.1).
        Text(
            text = statusText,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        if (classification.plateau) {
            Text(
                text = stringResource(R.string.detail_plateau),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Text(
            text = stringResource(R.string.detail_suggestion, suggestionText),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

/**
 * Fortschrittsuebersicht (Abschnitt 3): Uebungen mit Klassifizierung als
 * Einstieg in die Detailansicht.
 */
@Composable
fun ProgressScreen(
    contentPadding: PaddingValues,
    onOpenExercise: (Long) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ProgressViewModel = hiltViewModel(),
) {
    val items by viewModel.items.collectAsStateWithLifecycle()

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = contentPadding,
    ) {
        item {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.progress_title),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onBack) {
                    Text(stringResource(R.string.workout_back))
                }
            }
        }
        if (items.isEmpty()) {
            item {
                Text(
                    text = stringResource(R.string.detail_no_data),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
        }
        items(items, key = { it.exerciseId }) { item ->
            Card(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                        .clickable { onOpenExercise(item.exerciseId) },
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        text = item.displayName,
                        style = MaterialTheme.typography.titleMedium,
                    )
                    val classification = item.classification
                    if (classification != null) {
                        val statusText =
                            when (classification.status) {
                                ProgressStatus.PROGRESSING -> {
                                    stringResource(R.string.detail_status_progressing)
                                }

                                ProgressStatus.STAGNATING -> {
                                    stringResource(R.string.detail_status_stagnating)
                                }

                                ProgressStatus.DECLINING -> {
                                    stringResource(R.string.detail_status_declining)
                                }
                            }
                        val plateauSuffix =
                            if (classification.plateau) {
                                " - " + stringResource(R.string.detail_plateau_short)
                            } else {
                                ""
                            }
                        Text(
                            text = statusText + plateauSuffix,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}
