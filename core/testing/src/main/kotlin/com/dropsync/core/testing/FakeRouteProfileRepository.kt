package com.dropsync.core.testing

import com.dropsync.domain.playback.AudioRouteProfile
import com.dropsync.domain.playback.RouteProfileRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Fake des Latenzprofils der aktuellen Route. [profile] ist der Zustand, den
 * `currentProfile` liefert; [upsert] ersetzt ihn (wie der echte Store, der pro Route
 * speichert) und merkt sich den letzten geschriebenen Wert in [lastUpserted].
 */
class FakeRouteProfileRepository(
    initial: AudioRouteProfile? =
        AudioRouteProfile(
            routeKey = "fake",
            sampleRate = 48_000,
            channels = 2,
            estimatedLatencyMs = 120L,
            confidence = AudioRouteProfile.Confidence.ESTIMATED,
        ),
) : RouteProfileRepository {
    private val state = MutableStateFlow(initial)
    override val currentProfile: Flow<AudioRouteProfile?> = state

    var lastUpserted: AudioRouteProfile? = null
        private set

    var staleMarks: Int = 0
        private set

    override suspend fun currentLatencyMs(): Long? = state.value?.estimatedLatencyMs

    override suspend fun markStale() {
        staleMarks++
    }

    override suspend fun upsert(profile: AudioRouteProfile) {
        lastUpserted = profile
        state.value = profile
    }

    var clearCalls: Int = 0
        private set

    override suspend fun clearCalibration() {
        clearCalls++
        state.value = state.value?.copy(confidence = AudioRouteProfile.Confidence.ESTIMATED)
    }
}
