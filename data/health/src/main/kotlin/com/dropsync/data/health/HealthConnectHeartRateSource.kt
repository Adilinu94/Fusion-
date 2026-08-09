package com.dropsync.data.health

import com.dropsync.core.common.AppError
import com.dropsync.core.common.AppResult
import com.dropsync.core.common.Clock
import com.dropsync.core.common.DispatcherProvider
import com.dropsync.domain.health.HeartRateAvailability
import com.dropsync.domain.health.HeartRateAvailabilityResolver
import com.dropsync.domain.health.HeartRateAvailabilityResolver.ProviderState
import com.dropsync.domain.health.HeartRateSample
import com.dropsync.domain.health.HeartRateSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * HeartRateSource ueber Health Connect (Herzfrequenz-Plan 3.3):
 * Initial-Read ueber ein 15-Minuten-Fenster, danach Changes-API mit
 * persistiertem Token; abgelaufene Tokens fallen auf einen frischen
 * Initial-Read zurueck (Pflichtpfad, Review-Punkt 5). Aufrufe laufen nur
 * im Foreground (Plan 3.4) — die Aufrufkadenz steuert die UI-Schicht.
 */
internal class HealthConnectHeartRateSource(
    private val gateway: HealthConnectGateway,
    private val tokenStore: ChangesTokenStore,
    private val clock: Clock,
    private val dispatchers: DispatcherProvider,
) : HeartRateSource {
    private val availabilityState = MutableStateFlow(HeartRateAvailability.HEALTH_CONNECT_NOT_AVAILABLE)
    private val latestState = MutableStateFlow<HeartRateSample?>(null)

    override val availability: Flow<HeartRateAvailability> = availabilityState.asStateFlow()

    override val latestSample: Flow<HeartRateSample?> = latestState.asStateFlow()

    override val requiredPermissions: Set<String> = setOf(READ_HEART_RATE_PERMISSION)

    override suspend fun refreshAvailability() {
        withContext(dispatchers.io) { updateAvailability() }
    }

    override suspend fun refresh(): AppResult<Unit> =
        withContext(dispatchers.io) {
            val availability = updateAvailability()
            when (availability) {
                HeartRateAvailability.HEALTH_CONNECT_NOT_AVAILABLE,
                HeartRateAvailability.UPDATE_REQUIRED,
                -> {
                    AppResult.success(Unit)
                }

                // Zustand ist ueber availability sichtbar.
                HeartRateAvailability.PERMISSION_REQUIRED -> {
                    AppResult.failure(AppError.PermissionDenied(READ_HEART_RATE_PERMISSION))
                }

                else -> {
                    try {
                        syncSamples()
                        updateAvailability()
                        AppResult.success(Unit)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        AppResult.failure(AppError.Unknown(e.message))
                    }
                }
            }
        }

    private suspend fun updateAvailability(): HeartRateAvailability {
        val provider = gateway.providerState()
        val granted = provider == ProviderState.AVAILABLE && gateway.permissionGranted()
        val resolved =
            HeartRateAvailabilityResolver.resolve(
                provider = provider,
                permissionGranted = granted,
                hasRecentSample = latestState.value != null,
            )
        availabilityState.value = resolved
        return resolved
    }

    private suspend fun syncSamples() {
        val token = tokenStore.changesToken()
        if (token == null) {
            initialRead()
            return
        }
        val changes = gateway.changesSince(token)
        if (changes.tokenExpired) {
            // Token verfallen (z. B. laengere App-Pause): Zustand verwerfen,
            // frisch lesen und neuen Token speichern (Plan 3.3/4).
            tokenStore.clearChangesToken()
            initialRead()
        } else {
            applySamples(changes.samples)
            changes.nextToken?.let { tokenStore.saveChangesToken(it) }
        }
    }

    private suspend fun initialRead() {
        val now = clock.epochMillis()
        applySamples(gateway.readRecentSamples(now - INITIAL_WINDOW_MS, now))
        tokenStore.saveChangesToken(gateway.freshChangesToken())
    }

    private fun applySamples(samples: List<HeartRateSample>) {
        val newest = samples.maxByOrNull { it.recordedAtEpochMs } ?: return
        val current = latestState.value
        if (current == null || newest.recordedAtEpochMs >= current.recordedAtEpochMs) {
            latestState.value = newest
        }
    }

    companion object {
        /** Deckungsgleich mit der Manifest-Permission (Plan Abschnitt 7). */
        const val READ_HEART_RATE_PERMISSION = "android.permission.health.READ_HEART_RATE"

        /** Initial-Read-Fenster (Plan 3.3/3). */
        const val INITIAL_WINDOW_MS: Long = 15L * 60L * 1000L
    }
}
