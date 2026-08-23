package com.dropsync.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.dropsync.core.designsystem.component.FlowRepMetricCard
import com.dropsync.core.designsystem.component.FlowRepPrimaryButton
import com.dropsync.core.designsystem.component.FlowRepSectionHeader
import com.dropsync.core.designsystem.component.FlowRepSurface
import com.dropsync.domain.workout.ExerciseInfo
import com.dropsync.domain.workout.FlatSet
import com.dropsync.domain.workout.FlatSetRepository
import com.dropsync.domain.workout.WorkoutRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import java.text.DateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import javax.inject.Inject

/** Lokale, aus dem flachen Satz-Log berechnete Trainingsauswertung. */
@HiltViewModel
class HistoryViewModel
    @Inject
    constructor(
        flatSetRepository: FlatSetRepository,
        workoutRepository: WorkoutRepository,
    ) : ViewModel() {
        val state: StateFlow<HistoryUiState> =
            combine(flatSetRepository.observeAllSets(), workoutRepository.observeExercises("de")) { sets, exercises ->
                HistoryUiState.from(sets, exercises)
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HistoryUiState.Empty)
    }

data class HistoryUiState(
    val todayVolumeKg: Double,
    val weekVolumeKg: Double,
    val monthVolumeKg: Double,
    val personalRecords: List<HistorySet>,
    val recentSets: List<HistorySet>,
) {
    val isEmpty: Boolean get() = recentSets.isEmpty()

    companion object {
        val Empty = HistoryUiState(0.0, 0.0, 0.0, emptyList(), emptyList())

        fun from(
            sets: List<FlatSet>,
            exercises: List<ExerciseInfo>,
        ): HistoryUiState {
            val names = exercises.associate { it.id to it.displayName }
            val now = Calendar.getInstance()
            // Each threshold uses an independent calendar. Reusing the week
            // calendar would report the prior month on a Sunday month boundary.
            val todayStart = (now.clone() as Calendar).startOfDay()
            val weekStart = (now.clone() as Calendar).startOfWeek()
            val monthStart = (now.clone() as Calendar).startOfMonth()
            val history = sets.map { HistorySet(it, names[it.exerciseId] ?: "Übung") }
            val records =
                history
                    .groupBy { it.set.exerciseId }
                    .values
                    .mapNotNull { exerciseSets ->
                        exerciseSets.maxByOrNull { it.set.volumeKg }
                    }.sortedByDescending { it.set.volumeKg }
                    .take(3)

            return HistoryUiState(
                todayVolumeKg = history.filter { it.set.loggedAtEpochMs >= todayStart }.sumOf { it.set.volumeKg },
                weekVolumeKg = history.filter { it.set.loggedAtEpochMs >= weekStart }.sumOf { it.set.volumeKg },
                monthVolumeKg = history.filter { it.set.loggedAtEpochMs >= monthStart }.sumOf { it.set.volumeKg },
                personalRecords = records,
                recentSets = history.take(12),
            )
        }
    }
}

data class HistorySet(
    val set: FlatSet,
    val exerciseName: String,
)

@Composable
fun HistoryScreen(
    contentPadding: PaddingValues,
    onOpenTraining: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: HistoryViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Text(
                text = "Verlauf",
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.padding(start = 20.dp, top = 12.dp, end = 20.dp),
            )
        }
        if (state.isEmpty) {
            item {
                FlowRepSurface(modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
                    Text("Noch kein Training geloggt", style = MaterialTheme.typography.titleLarge)
                    Text(
                        text =
                            "Nach deinem ersten Satz zeigt FlowRep hier Volumen, Bestwerte und " +
                                "deinen Übungsverlauf.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    FlowRepPrimaryButton(
                        text = "Training öffnen",
                        onClick = onOpenTraining,
                        modifier = Modifier.padding(top = 24.dp),
                    )
                }
            }
        } else {
            item { VolumeOverview(state) }
            if (state.personalRecords.isNotEmpty()) {
                item {
                    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                        FlowRepSectionHeader(
                            "Persönliche Bestwerte",
                            modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
                        )
                        FlowRepSurface(contentPadding = PaddingValues(vertical = 4.dp)) {
                            state.personalRecords.forEach { HistoryRow(it, showRecord = true) }
                        }
                    }
                }
            }
            item {
                FlowRepSectionHeader(
                    title = "Letzte Sätze",
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
            }
            items(state.recentSets, key = { it.set.id }) { entry ->
                FlowRepSurface(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    HistoryRow(entry, showRecord = false)
                }
            }
        }
    }
}

@Composable
private fun VolumeOverview(state: HistoryUiState) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            VolumeMetric("Heute", state.todayVolumeKg, Modifier.weight(1f))
            VolumeMetric("Woche", state.weekVolumeKg, Modifier.weight(1f))
        }
        VolumeMetric("Monat", state.monthVolumeKg, Modifier.weight(1f))
    }
}

@Composable
private fun VolumeMetric(
    label: String,
    volumeKg: Double,
    modifier: Modifier,
) {
    FlowRepMetricCard(
        label = label,
        value = String.format(Locale.ROOT, "%.0f kg", volumeKg),
        modifier = modifier,
    )
}

@Composable
private fun HistoryRow(
    entry: HistorySet,
    showRecord: Boolean,
) {
    // TalkBack (Plan 6.2): die ganze Zeile als ein Element vorlesen statt
    // vier einzelne Texte; mergeDescendants fasst sie zusammen.
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .semantics(mergeDescendants = true) {},
    ) {
        Text(
            entry.exerciseName,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(entry.set.loggedAtEpochMs)),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = String.format(Locale.ROOT, "%.1f kg x %d", entry.set.weightMilliKg / 1_000_000.0, entry.set.reps),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(top = 8.dp),
        )
        Text(
            text =
                if (showRecord) {
                    "Bestes Volumen"
                } else {
                    String.format(
                        Locale.ROOT,
                        "%.0f kg Volumen",
                        entry.set.volumeKg,
                    )
                },
            style = MaterialTheme.typography.bodySmall,
            color = if (showRecord) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun Calendar.startOfDay(): Long =
    apply {
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

private fun Calendar.startOfWeek(): Long =
    apply {
        firstDayOfWeek = Calendar.MONDAY
        startOfDay()
        set(Calendar.DAY_OF_WEEK, firstDayOfWeek)
    }.timeInMillis

private fun Calendar.startOfMonth(): Long =
    apply {
        startOfDay()
        set(Calendar.DAY_OF_MONTH, 1)
    }.timeInMillis
