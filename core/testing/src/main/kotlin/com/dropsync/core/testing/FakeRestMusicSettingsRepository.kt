package com.dropsync.core.testing

import com.dropsync.core.model.RestMusicBehavior
import com.dropsync.domain.playback.RestMusicSettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Fake des Pausen-Musik-Verhaltens inkl. Drop-Auto (MP-13).
 * Die Setter halten den Wert in einem `MutableStateFlow`, damit Tests
 * "geschrieben und wieder gelesen" pruefen koennen.
 */
class FakeRestMusicSettingsRepository(
    initialBehavior: RestMusicBehavior = RestMusicBehavior.NORMAL,
    initialDropAuto: Boolean = RestMusicSettingsRepository.DEFAULT_DROP_AUTO_ENABLED,
) : RestMusicSettingsRepository {
    private val behaviorState = MutableStateFlow(initialBehavior)
    override val behavior: Flow<RestMusicBehavior> = behaviorState

    private val dropAutoState = MutableStateFlow(initialDropAuto)
    override val dropAutoEnabled: Flow<Boolean> = dropAutoState

    /** Zuletzt gesetzter Wert; null, wenn nie geschrieben wurde. */
    var lastWritten: RestMusicBehavior? = null
        private set

    var lastDropAutoWritten: Boolean? = null
        private set

    override suspend fun setBehavior(behavior: RestMusicBehavior) {
        lastWritten = behavior
        behaviorState.value = behavior
    }

    override suspend fun setDropAutoEnabled(enabled: Boolean) {
        lastDropAutoWritten = enabled
        dropAutoState.value = enabled
    }
}
