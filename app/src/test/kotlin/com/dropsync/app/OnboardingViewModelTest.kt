package com.dropsync.app

import com.dropsync.domain.settings.OnboardingRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * B3 (Ausbauplan): Der First-Run-Zustand steuert, ob die App Onboarding,
 * nichts (laden) oder den Inhalt zeigt. Das war bisher ungetestet; die
 * Reihenfolge null -> false/true ist der Kern gegen das Flackern bei
 * Bestandsnutzern.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OnboardingViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `seen startet null und wird nach dem Laden false`() =
        runTest {
            val repository = FakeOnboardingRepository(seen = false)
            val viewModel = OnboardingViewModel(repository)

            // Vor dem ersten Flow-Wert: null (Shell zeigt weder Onboarding
            // noch Inhalt, kein Flackern).
            assertNull(viewModel.seen.value)
            runCurrent()
            assertEquals(false, viewModel.seen.value)
        }

    @Test
    fun `markSeen persistiert und seen wird true`() =
        runTest {
            val repository = FakeOnboardingRepository(seen = false)
            val viewModel = OnboardingViewModel(repository)
            runCurrent()

            viewModel.markSeen()
            runCurrent()

            assertTrue(repository.seenFlow.value)
            assertEquals(true, viewModel.seen.value)
            assertEquals(1, repository.markCalls)
        }

    @Test
    fun `bereits gesehener Nutzer landet direkt im Inhalt`() =
        runTest {
            val repository = FakeOnboardingRepository(seen = true)
            val viewModel = OnboardingViewModel(repository)
            runCurrent()

            assertEquals(true, viewModel.seen.value)
            assertFalse(repository.markCalls > 0)
        }
}

/** Minimaler Fake ohne core:testing-Abhaengigkeit (App-Modul, Paket 1.6). */
private class FakeOnboardingRepository(
    seen: Boolean,
) : OnboardingRepository {
    val seenFlow = MutableStateFlow(seen)
    var markCalls = 0
        private set

    override val onboardingSeen: Flow<Boolean> = seenFlow

    override suspend fun markOnboardingSeen() {
        markCalls++
        seenFlow.value = true
    }
}
