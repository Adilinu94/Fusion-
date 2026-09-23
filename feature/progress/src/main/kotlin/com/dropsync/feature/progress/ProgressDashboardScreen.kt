package com.dropsync.feature.progress

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
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
import com.dropsync.core.designsystem.component.FlowRepErrorState
import com.dropsync.core.designsystem.component.FlowRepPrimaryButton
import com.dropsync.core.designsystem.component.FlowRepSurface
import com.dropsync.core.designsystem.component.ProgressRing
import com.dropsync.core.designsystem.theme.LocalReducedMotion
import com.dropsync.core.designsystem.theme.Spacing
import com.dropsync.core.designsystem.theme.rememberAccentTextColor
import com.dropsync.core.designsystem.theme.rememberWindowWidthSizeClass
import com.dropsync.domain.workout.ExerciseInfo
import com.dropsync.domain.workout.ExerciseTarget
import com.dropsync.domain.workout.FlatSet
import com.dropsync.domain.workout.FlatSetRepository
import com.dropsync.domain.workout.PrRecord
import com.dropsync.domain.workout.TargetRepository
import com.dropsync.domain.workout.WorkoutGoalRepository
import com.dropsync.domain.workout.WorkoutRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import java.text.DateFormat
import java.util.Calendar
import java.util.Date
import javax.inject.Inject

/** Kombinierter Dashboard-Zustand: Aggregate (Ring/Streak/Chart) plus Zeilen-Feed. */
data class ProgressDashboardUiState(
    val progress: ProgressUiState = ProgressUiState.Empty,
    val feed: ProgressFeedUiState = ProgressFeedUiState.Empty,
    val goals: ProgressGoalsUiState = ProgressGoalsUiState.Empty,
)

/** Bento-Dashboard des Verlauf-Tabs nach UI-Vertrag 2026-08-22 (Schritt 6d-2). */
@HiltViewModel
class ProgressViewModel
    @Inject
    constructor(
        flatSetRepository: FlatSetRepository,
        workoutRepository: WorkoutRepository,
        workoutGoalRepository: WorkoutGoalRepository,
        targetRepository: TargetRepository,
        @ApplicationContext private val appContext: Context,
    ) : ViewModel() {
        // Wochenziel aus dem DataStore (Schritt 7): derselbe Wert wie in den
        // Einstellungen, live — Aenderungen wirken sofort auf Ring und Chart.
        // Uebungsziele aus Room (DB v9): ein neu gesetztes Ziel erscheint
        // ohne Neustart im Ziele-Tile.
        // Echte PRs aus personal_records (Befund 3.14/153): Tile 5 zeigt
        // jetzt die fachlich richtigen Rekord-Typen, nicht mehr nur den
        // volumenbasierten Bestwert der letzten Saetze.
        // C6 (U-7): Lade-/Fehlerzustand sichtbar; Retry baut den Flow neu auf.
        private val retryTrigger = MutableStateFlow(0)

        @OptIn(ExperimentalCoroutinesApi::class)
        val screenState: StateFlow<ProgressDashboardScreenState> =
            retryTrigger
                .flatMapLatest {
                    combine(
                        // Befund 5.3: begrenzter Strom — das Dashboard braucht
                        // nur die juengsten Saetze (10 recent + 7-Tage-Fenster
                        // fuer Tiles und Charts); die volle Historie gehoert
                        // der Alle-Saetze-Route mit Nachladen.
                        flatSetRepository.observeRecentSets(FEED_SETS_LIMIT),
                        workoutRepository.observeExercises("de"),
                        workoutGoalRepository.weeklyTrainingGoal,
                        targetRepository.observeAllTargets(),
                        workoutRepository.observeAllPersonalRecords(),
                    ) { sets, exercises, weeklyGoal, targets, personalRecords ->
                        dashboardState(sets, exercises, weeklyGoal, targets, personalRecords)
                    }.catch { emit(ProgressDashboardScreenState.Error) }
                }.stateIn(
                    viewModelScope,
                    SharingStarted.WhileSubscribed(5_000),
                    ProgressDashboardScreenState.Loading,
                )

        /** C6: laedt die Projektion nach einem Fehler neu. */
        fun retry() {
            retryTrigger.value++
        }

        private companion object {
            /**
             * Befund 5.3: Dashboard-Schranke — ~28 Saetze/Tag ueber das
             * 7-Tage-Fenster. Wer mehr loggt, sieht aeltere Tage nur in der
             * Alle-Saetze-Route (dort mit Nachladen).
             */
            const val FEED_SETS_LIMIT = 200
        }

        private fun dashboardState(
            sets: List<FlatSet>,
            exercises: List<ExerciseInfo>,
            weeklyGoal: Int,
            targets: List<ExerciseTarget>,
            personalRecords: List<PrRecord>,
        ): ProgressDashboardScreenState {
            val names = exercises.associate { it.id to it.displayName }
            val now = Calendar.getInstance()
            val fallbackName = appContext.getString(R.string.progress_default_exercise)
            return ProgressDashboardScreenState.Ready(
                ProgressDashboardUiState(
                    progress = ProgressUiState.from(sets, now, weeklyGoal),
                    feed =
                        ProgressFeedUiState.from(
                            sets = sets,
                            exerciseNames = names,
                            now = now,
                            fallbackExerciseName = fallbackName,
                            personalRecords = personalRecords,
                        ),
                    goals =
                        ProgressGoalsUiState.from(
                            targets = targets,
                            sets = sets,
                            exerciseNames = names,
                            now = now,
                            fallbackExerciseName = fallbackName,
                        ),
                ),
            )
        }
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
    val state by viewModel.screenState.collectAsStateWithLifecycle()
    // C6 (U-7): Laden und Fehler sind eigene Zustaende mit Retry; nur der
    // Ready-Fall zeigt das Bento-Dashboard.
    when (val current = state) {
        ProgressDashboardScreenState.Loading -> {
            ProgressLoading(contentPadding = contentPadding, modifier = modifier)
        }

        ProgressDashboardScreenState.Error -> {
            FlowRepErrorState(
                text = stringResource(R.string.progress_error_load),
                onRetry = viewModel::retry,
                retryLabel = stringResource(R.string.progress_retry),
                modifier = modifier.padding(contentPadding),
            )
        }

        is ProgressDashboardScreenState.Ready -> {
            ProgressDashboardContent(
                state = current.dashboard,
                contentPadding = contentPadding,
                onOpenTraining = onOpenTraining,
                onOpenAllSets = onOpenAllSets,
                onOpenExerciseLibrary = onOpenExerciseLibrary,
                modifier = modifier,
            )
        }
    }
}

/** C6: Ladezustand des Dashboards (zentrierter Indikator, keine leere Seite). */
@Composable
private fun ProgressLoading(
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.fillMaxSize().padding(contentPadding),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator()
    }
}

@Composable
internal fun ProgressDashboardContent(
    state: ProgressDashboardUiState,
    contentPadding: PaddingValues,
    onOpenTraining: () -> Unit,
    onOpenAllSets: () -> Unit,
    onOpenExerciseLibrary: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val progress = state.progress
    val feed = state.feed

    // A11y-Punkt 1 und 2 / C2: Ab doppelter Systemschrift wird das Grid
    // einspaltig (Ring + Text brauchen Platz) und der Ring schrumpft auf
    // 120 dp; breite Fenster (Tablet) nutzen drei Spalten. Alles andere zwei.
    val columns =
        dashboardColumnCount(
            isExpanded = rememberWindowWidthSizeClass() == WindowWidthSizeClass.Expanded,
            fontScale = LocalDensity.current.fontScale,
        )
    val singleColumn = columns != 2
    val reducedMotion = LocalReducedMotion.current

    LazyVerticalStaggeredGrid(
        columns = StaggeredGridCells.Fixed(columns),
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
            if (feed.freshRecords.isNotEmpty() || feed.newPrRecords.isNotEmpty()) {
                // PR-Zeile (R6): keine Kachel, nur Text — gefeiert wird im
                // TrainScreen, nicht drei Stunden spaeter im Archiv.
                // Befund 3.14/153: echte PR-Typen (Last/Volumen/Reps bei Last)
                // haben Vorrang vor der volumenbasierten Naeherung.
                item(span = StaggeredGridItemSpan.FullLine) {
                    if (feed.newPrRecords.isNotEmpty()) {
                        FreshPrRecordsRow(records = feed.newPrRecords, onOpenAllSets = onOpenAllSets)
                    } else {
                        FreshRecordsRow(records = feed.freshRecords, onOpenAllSets = onOpenAllSets)
                    }
                }
            }
            // Ziele-Tile (R5): Uebungen ohne Ziel erscheinen nicht. Ohne
            // gesetztes Ziel bleibt nur die Hinweiszeile aus R7.
            if (state.goals.hasAnyGoal) {
                item(span = StaggeredGridItemSpan.FullLine) {
                    GoalsTile(
                        goals = state.goals,
                        onOpenExerciseLibrary = onOpenExerciseLibrary,
                        modifier = Modifier.animateItem(),
                    )
                }
            } else {
                item(span = StaggeredGridItemSpan.FullLine) {
                    GoalsHintRow(onOpenExerciseLibrary = onOpenExerciseLibrary)
                }
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
    val ringState = statementRingState(progress = progress, missing = missing, newWeek = newWeek)
    // Der einzige Feiermoment des Screens (UI-Vertrag Bewegung): kurzes
    // Violett-Aufblitzen des Randes, einmalig, wenn der erste Tile erscheint.
    val flash = rememberCelebrationFlash(reducedMotion = reducedMotion)
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
            val distanceText = statementDistanceText(progress = progress, missing = missing, newWeek = newWeek)
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

/** TalkBack-Zustand des Rings (A11y-Punkt 3): nennt den Zustand, nicht die
 * Grafik. Aus `StatementTile` gezogen (Detekt: CyclomaticComplexMethod). */
@Composable
private fun statementRingState(
    progress: ProgressUiState,
    missing: Int,
    newWeek: Boolean,
): String =
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

/**
 * Distanz-Sprache unter dem Ring (R2/R3): nennt die Handlung, nicht den
 * Stand. Aus `StatementTile` gezogen (Detekt: LongMethod).
 */
@Composable
private fun statementDistanceText(
    progress: ProgressUiState,
    missing: Int,
    newWeek: Boolean,
): String =
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

/**
 * Einmaliges Violett-Aufblitzen des Tile-Randes beim ersten Erscheinen
 * (UI-Vertrag Bewegung); bei reduzierter Bewegung kein Effekt.
 */
@Composable
private fun rememberCelebrationFlash(reducedMotion: Boolean): Animatable<Float, AnimationVector1D> {
    var celebrated by rememberSaveable { mutableStateOf(false) }
    val flash = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        if (!celebrated && !reducedMotion) {
            flash.animateTo(1f, tween(durationMillis = 150, easing = LinearEasing))
            flash.animateTo(0f, tween(durationMillis = 150, easing = LinearEasing))
            celebrated = true
        }
    }
    return flash
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
internal fun ChartTile(
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
            // A5: jede Spalte nennt Woche + Volumen — TalkBack hoert sonst
            // achtmal nur „Button".
            Row(modifier = Modifier.matchParentSize()) {
                bars.forEachIndexed { index, bar ->
                    // A5-Ansage im Composable-Kontext aufloesen (semantics ist keiner).
                    val barLabel =
                        stringResource(
                            R.string.progress_chart_bar_a11y,
                            isoWeekNumber(bar.weekStartEpochMs),
                            ProgressFormatters.volume(bar.volumeKg),
                        )
                    Box(
                        modifier =
                            Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .semantics { contentDescription = barLabel }
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
                .minimumInteractiveComponentSize()
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
 * TILE 5 mit echten PRs (Befund 3.14/153): jede Zeile nennt Uebung, PR-Art
 * und den Rekordwert. Bei einem einzigen Rekord bleibt die einzeilige Form.
 */
@Composable
private fun FreshPrRecordsRow(
    records: List<ProgressPrRow>,
    onOpenAllSets: () -> Unit,
) {
    val text =
        if (records.size == 1) {
            val row = records.first()
            stringResource(
                R.string.progress_pr_single,
                row.exerciseName,
                formatPrValue(row.record),
            )
        } else {
            stringResource(R.string.progress_pr_many, records.size)
        }
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(Spacing.radiusCard))
                .minimumInteractiveComponentSize()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    role = Role.Button,
                ) { onOpenAllSets() }
                .semantics(mergeDescendants = true) { role = Role.Button }
                .padding(Spacing.space4),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (records.size > 1) {
                // Detailzeilen: max. drei, der Rest steht in der Alle-Saetze-Route.
                records.take(3).forEach { row ->
                    Text(
                        text = "${row.exerciseName} · ${prTypeLabel(row.record.type)}: ${formatPrValue(row.record)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/** Rekordwert im UI-Format: Last in kg, Reps ganzzahlig (E4c). */
internal fun formatPrValue(record: com.dropsync.domain.workout.PrRecord): String =
    when (record.valueUnit) {
        com.dropsync.core.model.PrValueUnit.MILLI_KG -> {
            ProgressFormatters.weight(record.valueLong / 1_000_000.0)
        }

        com.dropsync.core.model.PrValueUnit.REPS -> {
            record.valueLong.toString()
        }
    }

internal fun prTypeLabel(type: com.dropsync.core.model.PrType): Int =
    when (type) {
        com.dropsync.core.model.PrType.HIGHEST_LOAD -> R.string.progress_pr_type_highest_load
        com.dropsync.core.model.PrType.HIGHEST_SESSION_VOLUME -> R.string.progress_pr_type_session_volume
        com.dropsync.core.model.PrType.MOST_REPS_AT_LOAD -> R.string.progress_pr_type_most_reps
    }

/**
 * TILE 6 — Ziele (UI-Vertrag R5): Der einzige Tile, der mit dem Inhalt
 * waechst. Zeilen nennen die Distanz zum Ziel (R2), Fortschritt sind zehn
 * Violett-Punkte statt eines Balkens — auf 6 Zoll zaehlbar und bei zehn
 * Zeilen ruhiger. Jede Zeile oeffnet die ExerciseLibrary (R5b).
 */
@Composable
private fun GoalsTile(
    goals: ProgressGoalsUiState,
    onOpenExerciseLibrary: () -> Unit,
    modifier: Modifier = Modifier,
) {
    DarkTile(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.progress_section_goals),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // Der Zaehler beantwortet "lohnt sich das Scrollen?", ohne dass
            // gescrollt werden muss (R5).
            Text(
                text =
                    stringResource(
                        R.string.progress_goals_counter,
                        goals.reachedCount,
                        goals.totalCount,
                    ),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        goals.rows.forEach { row ->
            GoalRow(row = row, onOpenExerciseLibrary = onOpenExerciseLibrary)
        }
        if (goals.staleRows.isNotEmpty()) {
            Text(
                text = stringResource(R.string.progress_goals_stale),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = Spacing.space8),
            )
            goals.staleRows.forEach { row ->
                GoalRow(row = row, onOpenExerciseLibrary = onOpenExerciseLibrary)
            }
        }
        Text(
            text = stringResource(R.string.progress_goals_add),
            style = MaterialTheme.typography.titleSmall,
            color = rememberAccentTextColor(),
            modifier =
                Modifier
                    .padding(top = Spacing.space8)
                    .minimumInteractiveComponentSize()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        role = Role.Button,
                    ) { onOpenExerciseLibrary() },
        )
    }
}

/**
 * Eine Ziel-Zeile: Name, Distanz, zehn Punkte. Erreichte Ziele zeigen ein
 * Violett-Haekchen plus das Wort „erreicht" — Farbe ist nie der einzige
 * Kanal (R5).
 */
@Composable
private fun GoalRow(
    row: ProgressGoalRow,
    onOpenExerciseLibrary: () -> Unit,
) {
    val distanceText = goalDistanceText(row)
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(Spacing.radiusCard))
                .minimumInteractiveComponentSize()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    role = Role.Button,
                ) { onOpenExerciseLibrary() }
                .semantics(mergeDescendants = true) {
                    role = Role.Button
                    stateDescription = distanceText
                }.padding(vertical = Spacing.space8),
        verticalArrangement = Arrangement.spacedBy(Spacing.space4),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = row.exerciseName,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.space4),
            ) {
                Text(
                    text = distanceText,
                    style = MaterialTheme.typography.bodySmall,
                    color =
                        if (row.reached) {
                            MaterialTheme.colorScheme.secondary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                )
                if (row.reached) {
                    Icon(
                        imageVector = Icons.Filled.Check,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
        if (!row.reached) {
            GoalDots(filled = row.filledDots)
        }
    }
}

/**
 * Zehn Punkte als Zehnerteilung (R5): ein gefuellter Punkt = 10 %
 * geschlossene Restdistanz. Violett, weil es um ein Ziel geht — Lime bleibt
 * der Aktion vorbehalten (R1).
 */
@Composable
private fun GoalDots(filled: Int) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(Spacing.space4),
        modifier =
            Modifier.semantics {
                contentDescription = ""
            },
    ) {
        repeat(GOAL_DOTS) { index ->
            Box(
                modifier =
                    Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(
                            if (index < filled) {
                                MaterialTheme.colorScheme.secondary
                            } else {
                                MaterialTheme.colorScheme.outline
                            },
                        ),
            )
        }
    }
}

/**
 * Distanz-Sprache (R2): `10 kg fehlen`, nicht `90 kg (Ziel 100 kg)`. Sind
 * beide Bedingungen offen, nennt die Zeile beide.
 */
@Composable
private fun goalDistanceText(row: ProgressGoalRow): String {
    val missingWeightKg = row.missingWeightMilliKg / 1000.0
    return when {
        row.reached -> {
            stringResource(R.string.progress_goal_reached)
        }

        !row.hasAnySet -> {
            stringResource(R.string.progress_goal_no_set)
        }

        row.missingWeightMilliKg > 0 && row.missingReps == 1 -> {
            stringResource(
                R.string.progress_goal_missing_both_one,
                ProgressFormatters.weight(missingWeightKg),
            )
        }

        row.missingWeightMilliKg > 0 && row.missingReps > 1 -> {
            stringResource(
                R.string.progress_goal_missing_both_many,
                ProgressFormatters.weight(missingWeightKg),
                row.missingReps,
            )
        }

        row.missingWeightMilliKg > 0 -> {
            stringResource(
                R.string.progress_goal_missing_weight,
                ProgressFormatters.weight(missingWeightKg),
            )
        }

        row.missingReps == 1 -> {
            stringResource(R.string.progress_goal_missing_reps_one)
        }

        else -> {
            stringResource(R.string.progress_goal_missing_reps_many, row.missingReps)
        }
    }
}

/**
 * TILE 6 — Ziele, Leerzustand: Ohne gesetztes Ziel eine Zeile statt eines
 * leeren Abschnitts (UI-Vertrag Ausblenden).
 */
@Composable
private fun GoalsHintRow(onOpenExerciseLibrary: () -> Unit) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(Spacing.radiusCard))
                .minimumInteractiveComponentSize()
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
            color = rememberAccentTextColor(),
            modifier =
                Modifier
                    .padding(top = Spacing.space8)
                    .clip(RoundedCornerShape(Spacing.radiusSmall))
                    .minimumInteractiveComponentSize()
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

/** Zehnerteilung des Ziel-Fortschritts (UI-Vertrag R5). */
private const val GOAL_DOTS = 10

/**
 * C2: Spalten des Dashboard-Grids. Doppelte Systemschrift erzwingt eine
 * Spalte (Ring und Beleg brauchen nebeneinander zu viel Platz), breite
 * Fenster (>= 840 dp) nutzen drei; alles andere zwei. Pure Funktion, damit
 * die Entscheidung ohne Compose testbar ist.
 */
internal fun dashboardColumnCount(
    isExpanded: Boolean,
    fontScale: Float,
): Int =
    when {
        fontScale > 1.5f -> 1
        isExpanded -> 3
        else -> 2
    }
