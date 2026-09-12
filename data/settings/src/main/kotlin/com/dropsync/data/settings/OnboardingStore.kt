package com.dropsync.data.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import com.dropsync.domain.settings.OnboardingRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.onboardingDataStore by preferencesDataStore(name = "onboarding")

/**
 * DataStore-Persistenz des First-Run-Flags (Ausbauplan B3). Fehlender Wert
 * heisst „noch nicht gesehen" — nach Loeschen der App-Daten erscheint das
 * Onboarding erneut, was zum frischen Installationszustand passt.
 */
class OnboardingStore(
    private val context: Context,
) : OnboardingRepository {
    override val onboardingSeen: Flow<Boolean> =
        context.onboardingDataStore.data.map { prefs ->
            prefs[KEY_SEEN] ?: false
        }

    override suspend fun markOnboardingSeen() {
        context.onboardingDataStore.edit { prefs ->
            prefs[KEY_SEEN] = true
        }
    }

    companion object {
        private val KEY_SEEN = booleanPreferencesKey("onboarding_seen")
    }
}
