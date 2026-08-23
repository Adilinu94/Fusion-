package com.dropsync.core.testing

import com.dropsync.core.common.AppResult
import com.dropsync.domain.health.HeartRateAvailability
import com.dropsync.domain.health.HeartRateSample
import com.dropsync.domain.health.HeartRateSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Steuerbarer [HeartRateSource]-Fake (Herzfrequenz-Plan Phase 2, Tests).
 * Rein JVM: Verfuegbarkeit und letzter Messwert sind direkt setzbar,
 * [refresh] zaehlt nur Aufrufe.
 */
class FakeHeartRateSource : HeartRateSource {
    private val _availability = MutableStateFlow(HeartRateAvailability.HEALTH_CONNECT_NOT_AVAILABLE)
    private val _latestSample = MutableStateFlow<HeartRateSample?>(null)

    override val availability: Flow<HeartRateAvailability> = _availability.asStateFlow()

    override val latestSample: Flow<HeartRateSample?> = _latestSample.asStateFlow()

    override val requiredPermissions: Set<String> = setOf("android.permission.health.READ_HEART_RATE")

    var refreshCalls = 0
        private set

    var refreshAvailabilityCalls = 0
        private set

    fun setAvailability(availability: HeartRateAvailability) {
        _availability.value = availability
    }

    fun setLatestSample(sample: HeartRateSample?) {
        _latestSample.value = sample
    }

    override suspend fun refreshAvailability() {
        refreshAvailabilityCalls++
    }

    override suspend fun refresh(): AppResult<Unit> {
        refreshCalls++
        return AppResult.Success(Unit)
    }
}
