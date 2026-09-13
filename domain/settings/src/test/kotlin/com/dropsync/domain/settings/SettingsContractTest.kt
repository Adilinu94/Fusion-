package com.dropsync.domain.settings

import com.dropsync.core.model.AccentColor
import com.dropsync.core.model.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * C4: Vertragstest der Settings-Schnittstellen. `:domain:settings` enthaelt nur
 * Vertraege; dieser Test pinnt die dokumentierten Defaults (SYSTEM / LIME /
 * false) und das "schreiben, dann lesen"-Verhalten, das jede Implementierung
 * (data/settings-Stores) und jeder Fake erfuellen muss.
 */
class SettingsContractTest {
    private class InMemoryThemeSettings : ThemeSettingsRepository {
        private val state = MutableStateFlow(ThemeMode.SYSTEM)

        override val themeMode: Flow<ThemeMode> = state

        override suspend fun setThemeMode(mode: ThemeMode) {
            state.value = mode
        }
    }

    private class InMemoryAccentColor : AccentColorRepository {
        private val state = MutableStateFlow(AccentColor.LIME)

        override val accentColor: Flow<AccentColor> = state

        override suspend fun setAccentColor(color: AccentColor) {
            state.value = color
        }
    }

    private class InMemoryOnboarding : OnboardingRepository {
        private val state = MutableStateFlow(false)

        override val onboardingSeen: Flow<Boolean> = state

        override suspend fun markOnboardingSeen() {
            state.value = true
        }
    }

    @Test
    fun `ThemeSetting startet auf SYSTEM und schreibt den Modus`() =
        runTest {
            val repository = InMemoryThemeSettings()
            assertEquals(ThemeMode.SYSTEM, repository.themeMode.first())
            repository.setThemeMode(ThemeMode.DARK)
            assertEquals(ThemeMode.DARK, repository.themeMode.first())
        }

    @Test
    fun `Akzentfarbe startet auf LIME und schreibt die Auswahl`() =
        runTest {
            val repository = InMemoryAccentColor()
            assertEquals(AccentColor.LIME, repository.accentColor.first())
            repository.setAccentColor(AccentColor.BLUE)
            assertEquals(AccentColor.BLUE, repository.accentColor.first())
        }

    @Test
    fun `Onboarding startet auf false und bleibt danach true`() =
        runTest {
            val repository = InMemoryOnboarding()
            assertEquals(false, repository.onboardingSeen.first())
            repository.markOnboardingSeen()
            assertEquals(true, repository.onboardingSeen.first())
        }
}
