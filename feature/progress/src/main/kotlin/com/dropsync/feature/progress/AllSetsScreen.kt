package com.dropsync.feature.progress

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.dropsync.core.designsystem.component.FlowRepEmptyState
import com.dropsync.core.designsystem.component.FlowRepErrorState
import com.dropsync.core.designsystem.component.FlowRepSectionHeader
import com.dropsync.core.designsystem.component.FlowRepSurface
import com.dropsync.core.designsystem.component.FlowRepTopBar
import com.dropsync.core.designsystem.theme.rememberAccentTextColor
import com.dropsync.domain.workout.ExerciseInfo
import com.dropsync.domain.workout.FlatSet
import com.dropsync.domain.workout.FlatSetRepository
import com.dropsync.domain.workout.WorkoutRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import java.text.DateFormat
import java.util.Date
import javax.inject.Inject

/** Vollstaendiges Satz-Log hinter dem Dashboard („Alle Sätze anzeigen", UI-Vertrag Verlauf). */
@HiltViewModel
class AllSetsViewModel
    @Inject
    constructor(
        flatSetRepository: FlatSetRepository,
        workoutRepository: WorkoutRepository,
        @ApplicationContext private val appContext: Context,
    ) : ViewModel() {
        // C6 (U-7): Laden und Fehler sind eigene Zustaende; Retry baut den
        // Flow neu auf, statt den Fehler stumm zu verschlucken.
        private val retryTrigger = MutableStateFlow(0)

        @OptIn(ExperimentalCoroutinesApi::class)
        val screenState: StateFlow<AllSetsScreenState> =
            retryTrigger
                .flatMapLatest {
                    val ready: Flow<AllSetsScreenState> =
                        combine(
                            flatSetRepository.observeAllSets(),
                            workoutRepository.observeExercises("de"),
                        ) { sets, exercises ->
                            AllSetsScreenState.Ready(
                                AllSetsUiState.from(
                                    sets,
                                    exercises,
                                    appContext.getString(R.string.progress_default_exercise),
                                ),
                            )
                        }
                    ready.catch { emit(AllSetsScreenState.Error) }
                }.stateIn(
                    viewModelScope,
                    SharingStarted.WhileSubscribed(5_000),
                    AllSetsScreenState.Loading,
                )

        /** C6: laedt das Satz-Log nach einem Fehler neu. */
        fun retry() {
            retryTrigger.value++
        }
    }

/**
 * C6 (U-7): Lade-/Fehlerzustand des Satz-Logs (vorher blieb ein Ladefehler
 * als leere Liste stehen und sah wie "keine Saetze" aus).
 */
sealed interface AllSetsScreenState {
    data object Loading : AllSetsScreenState

    data object Error : AllSetsScreenState

    data class Ready(
        val sets: AllSetsUiState,
    ) : AllSetsScreenState
}

data class AllSetsUiState(
    val personalRecords: List<ProgressSetRow>,
    val allSets: List<ProgressSetRow>,
) {
    companion object {
        val Empty = AllSetsUiState(emptyList(), emptyList())

        fun from(
            sets: List<FlatSet>,
            exercises: List<ExerciseInfo>,
            fallbackExerciseName: String,
        ): AllSetsUiState {
            val names = exercises.associate { it.id to it.displayName }
            val rows = sets.map { ProgressSetRow(it, names[it.exerciseId] ?: fallbackExerciseName) }
            val records =
                rows
                    .groupBy { it.set.exerciseId }
                    .values
                    .mapNotNull { exerciseRows -> exerciseRows.maxByOrNull { it.set.volumeKg } }
                    .sortedByDescending { it.set.volumeKg }
                    .take(3)
            return AllSetsUiState(personalRecords = records, allSets = rows)
        }
    }
}

/**
 * Alle-Saetze-Route: die Rohdaten unter den Aggregaten des Dashboards. Android
 * Back gilt hier normal (der Grund, warum das kein Segmented Control ist).
 */
@Composable
fun AllSetsScreen(
    contentPadding: PaddingValues,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: AllSetsViewModel = hiltViewModel(),
) {
    val state by viewModel.screenState.collectAsStateWithLifecycle()
    when (val current = state) {
        AllSetsScreenState.Loading -> {
            Box(
                modifier = modifier.fillMaxSize().padding(contentPadding),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
        }

        AllSetsScreenState.Error -> {
            FlowRepErrorState(
                text = stringResource(R.string.progress_error_load),
                onRetry = viewModel::retry,
                retryLabel = stringResource(R.string.progress_retry),
                modifier = modifier.padding(contentPadding),
            )
        }

        is AllSetsScreenState.Ready -> {
            AllSetsList(
                state = current.sets,
                contentPadding = contentPadding,
                onBack = onBack,
                modifier = modifier,
            )
        }
    }
}

/** C6: die eigentliche Liste (Ready-Fall), unveraendert zum Vorzustand. */
@Composable
private fun AllSetsList(
    state: AllSetsUiState,
    contentPadding: PaddingValues,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            FlowRepTopBar(
                title = stringResource(R.string.progress_title_all_sets),
                onBack = onBack,
                backContentDescription = stringResource(R.string.progress_back),
            )
        }
        // UI-Befund 4.2.7: Leerzustand statt stiller leerer Liste.
        if (state.allSets.isEmpty()) {
            item {
                FlowRepEmptyState(
                    text = stringResource(R.string.progress_empty_title),
                    modifier = Modifier.fillParentMaxHeight(0.5f),
                )
            }
        }
        if (state.personalRecords.isNotEmpty()) {
            item {
                Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                    FlowRepSectionHeader(
                        stringResource(R.string.progress_section_records),
                        modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
                    )
                    FlowRepSurface(contentPadding = PaddingValues(vertical = 4.dp)) {
                        state.personalRecords.forEach { AllSetsRow(it, showRecord = true) }
                    }
                }
            }
        }
        item {
            FlowRepSectionHeader(
                title = stringResource(R.string.progress_section_recent_sets),
                modifier = Modifier.padding(horizontal = 20.dp),
            )
        }
        items(state.allSets, key = { it.set.id }) { row ->
            FlowRepSurface(
                modifier = Modifier.padding(horizontal = 16.dp),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            ) {
                AllSetsRow(row, showRecord = false)
            }
        }
    }
}

@Composable
private fun AllSetsRow(
    row: ProgressSetRow,
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
            row.exerciseName,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(row.set.loggedAtEpochMs)),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = ProgressFormatters.weightTimesReps(row.set.weightMilliKg / 1_000_000.0, row.set.reps),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(top = 8.dp),
        )
        Text(
            text =
                if (showRecord) {
                    stringResource(R.string.progress_row_best_volume)
                } else {
                    stringResource(R.string.progress_row_volume, ProgressFormatters.volume(row.set.volumeKg))
                },
            style = MaterialTheme.typography.bodySmall,
            color = if (showRecord) rememberAccentTextColor() else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
