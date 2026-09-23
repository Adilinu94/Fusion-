package com.dropsync.feature.progress

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.cash.turbine.test
import com.dropsync.core.testing.FakeFlatSetRepository
import com.dropsync.core.testing.FakeTargetRepository
import com.dropsync.core.testing.FakeWorkoutGoalRepository
import com.dropsync.core.testing.FakeWorkoutRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * C6 (U-7): Lade- und Fehlerzustand von Verlauf und Satz-Log. Vorher waren
 * beide von "keine Saetze" nicht unterscheidbar; jetzt gibt es Loading,
 * Error mit Retry und Ready.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class ProgressScreenStateTest {
    private val dispatcher = StandardTestDispatcher()
    private val flatSets = FakeFlatSetRepository()
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun dashboardViewModel() =
        ProgressViewModel(
            flatSetRepository = flatSets,
            workoutRepository = FakeWorkoutRepository(),
            workoutGoalRepository = FakeWorkoutGoalRepository(),
            targetRepository = FakeTargetRepository(),
            appContext = context,
        )

    private fun allSetsViewModel() =
        AllSetsViewModel(
            flatSetRepository = flatSets,
            workoutRepository = FakeWorkoutRepository(),
            appContext = context,
        )

    @Test
    fun `Dashboard-Ladefehler wird sichtbar und Retry laedt neu`() =
        runTest(dispatcher) {
            var fail = true
            flatSets.allSetsFlow =
                flow {
                    if (fail) throw IllegalStateException("db kaputt")
                    emit(emptyList())
                }
            val vm = dashboardViewModel()

            vm.screenState.test {
                assertEquals(ProgressDashboardScreenState.Loading, awaitItem())
                assertEquals(ProgressDashboardScreenState.Error, awaitItem())

                fail = false
                vm.retry()
                val ready = awaitItem()
                assertTrue(ready is ProgressDashboardScreenState.Ready)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `Dashboard ohne Saetze ist Ready mit Leerzustand`() =
        runTest(dispatcher) {
            flatSets.allSetsFlow = flowOf(emptyList())
            val vm = dashboardViewModel()

            vm.screenState.test {
                assertEquals(ProgressDashboardScreenState.Loading, awaitItem())
                val ready = awaitItem()
                assertTrue(ready is ProgressDashboardScreenState.Ready)
                assertEquals(
                    false,
                    (ready as ProgressDashboardScreenState.Ready).dashboard.progress.hasAnySets,
                )
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `Satz-Log zeigt Fehler und Retry`() =
        runTest(dispatcher) {
            var fail = true
            flatSets.allSetsFlow =
                flow {
                    if (fail) throw IllegalStateException("db kaputt")
                    emit(emptyList())
                }
            val vm = allSetsViewModel()

            vm.screenState.test {
                assertEquals(AllSetsScreenState.Loading, awaitItem())
                assertEquals(AllSetsScreenState.Error, awaitItem())

                fail = false
                vm.retry()
                assertTrue(awaitItem() is AllSetsScreenState.Ready)
                cancelAndIgnoreRemainingEvents()
            }
        }
}
