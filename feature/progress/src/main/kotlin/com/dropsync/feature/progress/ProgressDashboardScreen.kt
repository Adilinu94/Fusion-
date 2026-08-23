package com.dropsync.feature.progress

import android.content.Context
import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.dropsync.core.designsystem.chart.BarChart
import com.dropsync.core.designsystem.component.CountUpText
import com.dropsync.core.designsystem.component.FlowRepPrimaryButton
import com.dropsync.core.designsystem.component.FlowRepSurface
import com.dropsync.core.designsystem.component.ProgressRing
import com.dropsync.core.designsystem.theme.Spacing
import com.dropsync.domain.workout.FlatSetRepository
import com.dropsync.domain.workout.WorkoutGoalRepository
import com.dropsync.domain.workout.WorkoutRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import java.text.DateFormat
import java.util.Calendar
import java.util.Date
import javax.inject.Inject

/** Kombinierter Dashboard-Zustand: Aggregate (Ring/Streak/Chart) plus Zeilen-Feed. */
data class ProgressDashboardUiState(
    val progress: ProgressUiState = ProgressUiState.Empty,
    val feed: ProgressFeedUiState = ProgressFeedUiState.Empty,
)

/** Bento-Dashboard des Verlauf-Tabs nach UI-Vertrag 2026-08-22 (Schritt 6d-2). */
@HiltViewModel
class ProgressViewModel
    @Inject
    constructor(
        flatSetRepository: FlatSetRepository,
        workoutRepository: WorkoutRepository,
        workoutGoalRepository: WorkoutGoalRepository,
        @ApplicationContext private val appContext: Context,
    ) : ViewModel() {
        // Wochenziel aus dem DataStore (Schritt 7): derselbe Wert wie in den
        // Einstellungen, live — Aenderungen wirken sofort auf Ring und Chart.
        val state: StateFlow<ProgressDashboardUiState> =
            combine(
                flatSetRepository.observeAllSets(),
                workoutRepository.observeExercises("de"),
                workoutGoalRepository.weeklyTrainingGoal,
            ) { sets, exercises, weeklyGoal ->
                val names = exercises.associate { it.id to it.displayName }
                val now = Calendar.getInstance()
                ProgressDashboardUiState(
                    progress = ProgressUiState.from(sets, now, weeklyGoal),
                    feed =
                        ProgressFeedUiState.from(
                            sets = sets,
                            exerciseNames = names,
                            now = now,
                            fallbackExerciseName = appContext.getString(R.string.progress_default_exercise),
                        ),
                )
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ProgressDashboardUiState())
    }

/**
 * Progress-Dashboard (Verlauf-Tab): Beantwortet „bin ich diese Woche auf
 * Kurs?" — Aussage-Tile (Ring) plus Belege im Bento-Grid. Tiles ohne Aussage
 * werden nicht gezeigt (UI-Vertrag „Ein- und Ausblenden"); die Rohdaten stehen
 * als Tile 7 mit eigener Alle-Saetze-Route dahinter.
 */
@Composable
fun ProgressDashboardScreen(
    contentPadding: PaddingValues,
    onOpenTraining: () -> Unit,
    onOpenAllSets: () -> Unit,
    onOpenExerciseLibrary: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ProgressViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    ProgressDashboardContent(
        state = state,
        contentPadding = contentPadding,
        onOpenTraining = onOpenTraining,
        onOpenAllSets = onOpenAllSets,
        onOpenExerciseLibrary = onOpenExerciseLibrary,
        modifier = modifier,
    )
}

@Composable
private fun ProgressDashboardContent(
    state: ProgressDashboardUiState,
    contentPadding: PaddingValues,
    onOpenTraining: () -> Unit,
    onOpenAllSets: () -> Unit,
    onOpenExerciseLibrary: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val progress = state.progress
    val feed = state.feed

    // A11y-Punkt 1 und 2: Ab doppelter Systemschrift wird das Grid einspaltig
    // und der Ring schrumpft auf 120 dp mit der Zahl darunter.
    val singleColumn = LocalDensity.current.fontScale > 1.5f
    val reducedMotion = LocalContext.current.isReducedMotion()

    LazyVerticalStaggeredGrid(
        columns = StaggeredGridCells.Fixed(if (singleColumn) 1 else 2),
        modifier = modifier.fillMaxSize(),
        contentPadding =
            PaddingValues(
                start = Spacing.space16,
                end = Spacing.space16,
                top = contentPadding.calculateTopPadding() + Spacing.space12,
                bottom = contentPadding.calculateBottomPadding() + Spacing.space24,
            ),
        verticalItemSpacing = Spacing.space12,
        horizontalArrangement = Arrangement.spacedBy(Spacing.space12),
    ) {
        item(span = StaggeredGridItemSpan.FullLine) {
            Text(
                text = stringResource(R.string.progress_title_dashboard),
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.padding(start = Spacing.space4, top = Spacing.space12),
            )
        }
        if (!progress.hasAnySets) {
            // Leerzustand ist Onboarding (R7): einzige Lime-Flaeche ist der
            // Training-Button, kein leerer Ring.
            item(span = StaggeredGridItemSpan.FullLine) {
                FlowRepSurface {
                    Text(
                        text = stringResource(R.string.progress_empty_title),
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Text(
                        text = stringResource(R.string.progress_empty_body),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = Spacing.space8),
                    )
                    FlowRepPrimaryButton(
                        text = stringResource(R.string.progress_empty_open_training),
                        onClick = onOpenTraining,
                        modifier = Modifier.padding(top = Spacing.space24),
                    )
                }
            }
        } else {
            item(span = StaggeredGridItemSpan.FullLine) {
                EnterTile(reducedMotion) {
                    StatementTile(progress = progress, singleColumn = singleColumn, reducedMotion = reducedMotion)
                }
            }
            if (progress.streakVisible) {
                item {
                    StreakTile(progress = progress, fixedHeight = !singleColumn, modifier = Modifier.animateItem())
                }
            }
            if (progress.weekVolumeKg > 0.0) {
                item {
                    VolumeTile(
                        volumeKg = progress.weekVolumeKg,
                        fixedHeight = !singleColumn,
                        modifier = Modifier.animateItem(),
                    )
                }
            }
            if (progress.chartVisible) {
                item(span = StaggeredGridItemSpan.FullLine) {
                    ChartTile(progress = progress, modifier = Modifier.animateItem())
                }
            }
            if (feed.freshRecords.isNotEmpty()) {
                // PR-Zeile (R6): keine Kachel, nur Text — gefeiert wird im
                // TrainScreen, nicht drei Stunden spaeter im Archiv.
                item(span = StaggeredGridItemSpan.FullLine) {
                    FreshRecordsRow(records = feed.freshRecords, onOpenAllSets = onOpenAllSets)
                }
            }
            // Ziele-Tile bis DB v9 (Schritt 7): noch koennen keine Ziele
            // gesetzt sein, deshalb die Zwischenzustands-Zeile aus R7.
            item(span = StaggeredGridItemSpan.FullLine) {
                GoalsHintRow(onOpenExerciseLibrary = onOpenExerciseLibrary)
            }
            if (feed.recentSets.isNotEmpty()) {
                item(span = StaggeredGridItemSpan.FullLine) {
                    RecentSetsTile(
                        rows = feed.recentSets,
                        onOpenAllSets = onOpenAllSets,
                        modifier = Modifier.animateItem(),
                    )
                }
            }
        }
    }
}

/** Tile-Ersterscheinen (UI-Vertrag Bewegung): fadeIn + scaleIn(0.96f), 200 ms. */
@Composable
private fun EnterTile(
    reducedMotion: Boolean,
    content: @Composable () -> Unit,
) {
    if (reducedMotion) {
        content()
        return
    }
    val visibleState = remember { MutableTransitionState(false).apply { targetState = true } }
    AnimatedVisibility(
        visibleState = visibleState,
        enter =
            fadeIn(tween(durationMillis = 200)) +
                scaleIn(initialScale = 0.96f, animationSpec = tween(durationMillis = 200)),
    ) {
        content()
    }
}

/**
 * TILE 1 — Aussage: der einzige helle Tile (E7E6FB) mit dem Wochenziel-Ring.
 * Der Ring-Track bleibt dunkel, damit der Lime-Fortschritt nie E7E6FB
 * beruehrt (Kontrast 1,08, UI-Vertrag Kontrast-Regel 2). Die Distanz-Sprache
 * nennt die Handlung, nicht den Stand (R2); am Montag ohne Satz zaehlt vorwaerts
 * gerichtete Neue-Woche-Sprache (R3).
 */
@Composable
private fun StatementTile(
    progress: ProgressUiState,
    singleColumn: Boolean,
    reducedMotion: Boolean,
) {
    val onSurface = MaterialTheme.colorScheme.onSecondaryContainer
    val missing = progress.weeklyGoal - progress.trainingDaysThisWeek
    val newWeek = progress.firstWeekDayWithoutSets && progress.trainingDaysThisWeek == 0
    // A11y-Punkt 3: TalkBack liest den Zustand, nicht die Grafik.
    val ringState =
        when {
            newWeek -> {
                stringResource(R.string.progress_ring_a11y_new_week, progress.weeklyGoal)
            }

            progress.weeklyGoalExceeded -> {
                stringResource(R.string.progress_ring_a11y_exceeded, progress.trainingDaysThisWeek, progress.weeklyGoal)
            }

            progress.weeklyGoalReached -> {
                stringResource(R.string.progress_ring_a11y_reached, progress.trainingDaysThisWeek, progress.weeklyGoal)
            }

            missing == 1 -> {
                stringResource(
                    R.string.progress_ring_a11y_missing_one,
                    progress.trainingDaysThisWeek,
                    progress.weeklyGoal,
                )
            }

            else -> {
                stringResource(
                    R.string.progress_ring_a11y_missing_many,
                    progress.trainingDaysThisWeek,
                    progress.weeklyGoal,
                    missing,
                )
            }
        }
    // Der einzige Feiermoment des Screens (UI-Vertrag Bewegung): kurzes
    // Violett-Aufblitzen des Randes, einmalig, wenn der erste Tile erscheint.
    var celebrated by rememberSaveable { mutableStateOf(false) }
    val flash = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        if (!celebrated && !reducedMotion) {
            flash.animateTo(1f, tween(durationMillis = 150, easing = LinearEasing))
            flash.animateTo(0f, tween(durationMillis = 150, easing = LinearEasing))
            celebrated = true
        }
    }
    val heroShape = RoundedCornerShape(Spacing.radiusHero)
    val ringSize = if (singleColumn) 120.dp else 220.dp

    Surface(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = if (singleColumn) 0.dp else 340.dp)
                .border(
                    BorderStroke(2.dp, MaterialTheme.colorScheme.secondary.copy(alpha = flash.value)),
                    heroShape,
                ),
        shape = heroShape,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = onSurface,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(Spacing.space24)
                    .semantics(mergeDescendants = true) { stateDescription = ringState },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Spacing.space8, Alignment.CenterVertically),
        ) {
            ProgressRing(
                progress = progress.ringProgress,
                excessProgress = ProgressUiState.excessProgress(progress.trainingDaysThisWeek, progress.weeklyGoal),
                ringSize = ringSize,
                strokeWidth = if (singleColumn) 10.dp else 14.dp,
                trackColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
                if (!singleColumn) {
                    CountUpText(
                        targetValue = progress.trainingDaysThisWeek,
                        style = MaterialTheme.typography.displayMedium,
                        color = onSurface,
                    )
                }
            }
            if (singleColumn) {
                CountUpText(
                    targetValue = progress.trainingDaysThisWeek,
                    style = MaterialTheme.typography.displayMedium,
                    color = onSurface,
                )
            }
            Text(
                text =
                    if (newWeek) {
                        stringResource(R.string.progress_ring_new_week)
                    } else {
                        stringResource(R.string.progress_ring_of, progress.weeklyGoal)
                    },
                style = MaterialTheme.typography.titleMedium,
            )
            val distanceText =
                when {
                    newWeek -> {
                        stringResource(R.string.progress_ring_planned, progress.weeklyGoal)
                    }

                    missing <= 0 -> {
                        stringResource(
                            if (progress.weeklyGoalExceeded) {
                                R.string.progress_ring_exceeded
                            } else {
                                R.string.progress_ring_reached
                            },
                        )
                    }

                    missing == 1 -> {
                        stringResource(R.string.progress_ring_missing_one)
                    }

                    else -> {
                        stringResource(R.string.progress_ring_missing_many, missing)
                    }
                }
            // R1: Auf dem hellen Tile ist Lime unlesbar — der Goal-Gradient-
            // Moment „genau ein Training fehlt" traegt Gewicht statt Farbe.
            Text(
                text = distanceText,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight =
                    if (missing == 1 && !newWeek) {
                        FontWeight.Bold
                    } else {
                        null
                    },
            )
            if (newWeek && progress.streakVisible) {
                Text(
                    text = stringResource(R.string.progress_streak_line, progress.streakDays),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

/** TILE 2 — Streak: Trainingstage in Folge, erst ab 2 sichtbar (keine Serie). */
@Composable
private fun StreakTile(
    progress: ProgressUiState,
    fixedHeight: Boolean,
    modifier: Modifier = Modifier,
) {
    DarkTile(modifier = modifier.tileHeight(fixedHeight, 120.dp)) {
        Text(
            text = progress.streakDays.toString(),
            style = MaterialTheme.typography.displaySmall,
        )
        Text(
            text = stringResource(R.string.progress_streak_days),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (progress.streakAtRisk) {
            Text(
                text = stringResource(R.string.progress_streak_keep),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** TILE 3 — Volumen diese Woche; nur wenn ein Satz existiert (sonst weg). */
@Composable
private fun VolumeTile(
    volumeKg: Double,
    fixedHeight: Boolean,
    modifier: Modifier = Modifier,
) {
    DarkTile(modifier = modifier.tileHeight(fixedHeight, 120.dp)) {
        Text(
            text = ProgressFormatters.volume(volumeKg),
            style = MaterialTheme.typography.displaySmall,
        )
        Text(
            text = stringResource(R.string.progress_volume_this_week),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * TILE 4 — Chart der letzten acht Wochen (R4): Hoehe = Volumen, Grundlinie =
 * Wochenziel erfuellt (Violett), Farbe = jetzt (Violett) mit Lime-Wert-Pille.
 * Tap auf einen Balken nennt `KW x · Volumen · y von z`.
 */
@Composable
private fun ChartTile(
    progress: ProgressUiState,
    modifier: Modifier = Modifier,
) {
    val bars = progress.weekBars
    val currentWeek = bars.lastOrNull { it.isCurrentWeek }
    val highlightIndex = bars.indexOfLast { it.isCurrentWeek && it.volumeKg > 0.0 }.takeIf { it >= 0 }
    var selectedWeek by remember { mutableIntStateOf(-1) }
    val trendRes =
        when (chartTrend(bars)) {
            1 -> R.string.progress_trend_up
            -1 -> R.string.progress_trend_down
            else -> R.string.progress_trend_flat
        }
    val chartDescription =
        stringResource(
            R.string.progress_chart_a11y,
            stringResource(trendRes),
            currentWeek?.let { ProgressFormatters.volume(it.volumeKg) } ?: "",
        )

    DarkTile(modifier = modifier) {
        Box {
            BarChart(
                values = bars.map { it.volumeKg.toFloat() },
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(120.dp)
                        .semantics { contentDescription = chartDescription },
                barColor = MaterialTheme.colorScheme.onSurfaceVariant,
                highlightIndex = highlightIndex,
                fulfilled = bars.map { it.trainingDays >= progress.weeklyGoal },
                pillText = currentWeek?.takeIf { it.volumeKg > 0.0 }?.let { ProgressFormatters.volume(it.volumeKg) },
            )
            // Tap-Ebene: acht unsichtbare Spalten ueber dem Canvas, damit der
            // Balken-Dialog ohne eigene Pointer-Logik im Chart funktioniert.
            Row(modifier = Modifier.matchParentSize()) {
                bars.forEachIndexed { index, _ ->
                    Box(
                        modifier =
                            Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                    role = Role.Button,
                                ) { selectedWeek = index },
                    )
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = stringResource(R.string.progress_chart_week_label, isoWeekNumber(bars.first().weekStartEpochMs)),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(R.string.progress_chart_week_label, isoWeekNumber(bars.last().weekStartEpochMs)),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (selectedWeek >= 0 && selectedWeek < bars.size) {
            val week = bars[selectedWeek]
            Text(
                text =
                    stringResource(
                        R.string.progress_chart_week_info,
                        isoWeekNumber(week.weekStartEpochMs),
                        ProgressFormatters.volume(week.volumeKg),
                        week.trainingDays,
                        progress.weeklyGoal,
                    ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** TILE 5 — PR-Zeile der letzten 7 Tage: Text statt Kachel, ohne Lime (R6). */
@Composable
private fun FreshRecordsRow(
    records: List<ProgressSetRow>,
    onOpenAllSets: () -> Unit,
) {
    val text =
        if (records.size == 1) {
            val record = records.first()
            stringResource(
                R.string.progress_pr_single,
                record.exerciseName,
                ProgressFormatters.weightTimesReps(record.set.weightMilliKg / 1_000_000.0, record.set.reps),
            )
        } else {
            stringResource(R.string.progress_pr_many, records.size)
        }
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(Spacing.radiusCard))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    role = Role.Button,
                ) { onOpenAllSets() }
                .semantics(mergeDescendants = true) { role = Role.Button }
                .padding(Spacing.space4),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * TILE 6 — Ziele: bis TargetEntity existiert (Schritt 7, DB v9) die
 * Zwischenzustands-Zeile aus R7 statt eines leeren Abschnitts.
 */
@Composable
private fun GoalsHintRow(onOpenExerciseLibrary: () -> Unit) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(Spacing.radiusCard))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    role = Role.Button,
                ) { onOpenExerciseLibrary() }
                .semantics(mergeDescendants = true) { role = Role.Button }
                .padding(vertical = Spacing.space8, horizontal = Spacing.space4),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.space8),
    ) {
        Text(
            text = stringResource(R.string.progress_goals_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Icon(
            imageVector = Icons.AutoMirrored.Filled.ArrowForward,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** TILE 7 — Letzte zehn Saetze plus Einstieg in die Alle-Saetze-Route. */
@Composable
private fun RecentSetsTile(
    rows: List<ProgressSetRow>,
    onOpenAllSets: () -> Unit,
    modifier: Modifier = Modifier,
) {
    DarkTile(modifier = modifier) {
        Text(
            text = stringResource(R.string.progress_section_recent_sets),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        rows.forEach { row ->
            RecentSetRow(row)
        }
        Text(
            text = stringResource(R.string.progress_show_all_sets),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier =
                Modifier
                    .padding(top = Spacing.space8)
                    .clip(RoundedCornerShape(Spacing.radiusSmall))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        role = Role.Button,
                    ) { onOpenAllSets() }
                    .semantics(mergeDescendants = true) { role = Role.Button }
                    .padding(vertical = Spacing.space8, horizontal = Spacing.space4),
        )
    }
}

/** Satz-Zeile: ein TalkBack-Element via mergeDescendants (A11y-Punkt 7). */
@Composable
private fun RecentSetRow(row: ProgressSetRow) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(top = Spacing.space12)
                .semantics(mergeDescendants = true) {},
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = row.exerciseName,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text =
                    ProgressFormatters.weightTimesReps(
                        row.set.weightMilliKg / 1_000_000.0,
                        row.set.reps,
                    ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(row.set.loggedAtEpochMs)),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Dunkle Tile-Flaeche: space24 Innenabstand, kein Rahmen, kein Schatten. */
@Composable
private fun DarkTile(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(Spacing.radiusCard),
        color = MaterialTheme.colorScheme.surfaceContainer,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column(modifier = Modifier.padding(Spacing.space24), content = content)
    }
}

/**
 * Feste Tile-Hoehe nur bei normaler Schrift: Die Anordnung soll beim
 * Datenwechsel nicht springen (UI-Vertrag Bento), bei fontScale > 1.5 wuerde
 * eine feste Hoehe Text abschneiden (A11y-Punkt 2).
 */
private fun Modifier.tileHeight(
    fixed: Boolean,
    height: Dp,
): Modifier = if (fixed) this.height(height) else this

/** Trend des 8-Wochen-Chart: 1 steigend, -1 sinkend, 0 gleichbleibend. */
private fun chartTrend(bars: List<ProgressWeekBar>): Int {
    val volumes = bars.map { it.volumeKg }.filter { it > 0.0 }
    if (volumes.size < 2) return 0
    val first = volumes.first()
    val last = volumes.last()
    return when {
        last > first * 1.05 -> 1
        last < first * 0.95 -> -1
        else -> 0
    }
}

/** ISO-Kalenderwoche (Montag-Wochen, 4 Tage minimum) fuer die KW-Labels. */
private fun isoWeekNumber(weekStartEpochMs: Long): Int =
    Calendar
        .getInstance()
        .apply {
            timeInMillis = weekStartEpochMs
            firstDayOfWeek = Calendar.MONDAY
            minimalDaysInFirstWeek = 4
        }.get(Calendar.WEEK_OF_YEAR)

/** Bei reduzierter Systemanimation: Endwerte sofort, kein Blitz (UI-Vertrag Bewegung). */
private fun Context.isReducedMotion(): Boolean =
    Settings.Global.getFloat(contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
