package com.dropsync.data.health

import com.dropsync.domain.health.HeartRateAvailabilityResolver.ProviderState
import com.dropsync.domain.health.HeartRateSample

/**
 * Schmale Abstraktion ueber den Health-Connect-Client (Herzfrequenz-Plan
 * Phase 1): kapselt alle SDK-Aufrufe, damit [HealthConnectHeartRateSource]
 * mit einem Fake rein auf der JVM getestet werden kann.
 */
internal interface HealthConnectGateway {
    /** Aus `getSdkStatus` abgeleiteter Providerzustand. */
    fun providerState(): ProviderState

    /** Ist die Herzfrequenz-Leseberechtigung erteilt? */
    suspend fun permissionGranted(): Boolean

    /** Initial-Read: Samples im Zeitfenster, chronologisch beliebig. */
    suspend fun readRecentSamples(
        sinceEpochMs: Long,
        untilEpochMs: Long,
    ): List<HeartRateSample>

    /** Frischer Changes-Token fuer HeartRateRecord (Plan 3.3/4). */
    suspend fun freshChangesToken(): String

    /** Changes seit dem Token; meldet abgelaufene Tokens explizit. */
    suspend fun changesSince(token: String): HealthChanges
}

/** Ergebnis eines Changes-Abrufs (Plan 3.3/4, Token-Ablauf ist Pflichtpfad). */
internal data class HealthChanges(
    val tokenExpired: Boolean,
    val nextToken: String?,
    val samples: List<HeartRateSample>,
)
