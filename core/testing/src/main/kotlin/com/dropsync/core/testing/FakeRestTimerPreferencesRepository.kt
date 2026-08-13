package com.dropsync.core.testing

import com.dropsync.domain.timer.RestTimerPreferencesRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * [RestTimerPreferencesRepository]-Fake: Werkseinstellungen, konfigurierbar
 * über Constructor-Parameter. Rein JVM.
 */
class FakeRestTimerPreferencesRepository(
    presets: List<Int> = RestTimerPreferencesRepository.DEFAULT_PRESETS_SECONDS,
    readyEnabled: Boolean = false,
    readySeconds: Int = RestTimerPreferencesRepository.DEFAULT_GET_READY_SECONDS,
) : RestTimerPreferencesRepository {
    override val restPresetsSeconds: Flow<List<Int>> = flowOf(presets)

    override suspend fun setRestPresetsSeconds(seconds: List<Int>) = Unit

    override val getReadyEnabled: Flow<Boolean> = flowOf(readyEnabled)
    override val getReadySeconds: Flow<Int> = flowOf(readySeconds)

    override suspend fun setGetReady(
        enabled: Boolean,
        seconds: Int,
    ) = Unit
}
