package com.dropsync.data.health

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.changes.UpsertionChange
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.request.ChangesTokenRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import com.dropsync.domain.health.HeartRateAvailabilityResolver.ProviderState
import com.dropsync.domain.health.HeartRateSample
import java.time.Instant

/**
 * Echter SDK-Wrapper (Plan 3.3). Der Client wird erst erzeugt, wenn
 * `getSdkStatus` Verfuegbarkeit gemeldet hat — auf API 26/27 oder ohne
 * Provider bleibt jeder Aufruf beim Providerstatus stehen (Plan Abschnitt 2).
 */
internal class HealthConnectGatewayImpl(
    private val context: Context,
) : HealthConnectGateway {
    private val readPermission = HealthPermission.getReadPermission(HeartRateRecord::class)

    private fun client(): HealthConnectClient = HealthConnectClient.getOrCreate(context)

    override fun providerState(): ProviderState =
        when (HealthConnectClient.getSdkStatus(context)) {
            HealthConnectClient.SDK_AVAILABLE -> ProviderState.AVAILABLE
            HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> ProviderState.UPDATE_REQUIRED
            else -> ProviderState.NOT_AVAILABLE
        }

    override suspend fun permissionGranted(): Boolean =
        client()
            .permissionController
            .getGrantedPermissions()
            .contains(readPermission)

    override suspend fun readRecentSamples(
        sinceEpochMs: Long,
        untilEpochMs: Long,
    ): List<HeartRateSample> {
        val response =
            client().readRecords(
                ReadRecordsRequest(
                    recordType = HeartRateRecord::class,
                    timeRangeFilter =
                        TimeRangeFilter.between(
                            Instant.ofEpochMilli(sinceEpochMs),
                            Instant.ofEpochMilli(untilEpochMs),
                        ),
                ),
            )
        return HeartRateSampleMapping.toSamples(response.records)
    }

    override suspend fun freshChangesToken(): String =
        client().getChangesToken(ChangesTokenRequest(setOf(HeartRateRecord::class)))

    override suspend fun changesSince(token: String): HealthChanges {
        val samples = mutableListOf<HeartRateSample>()
        var next = token
        while (true) {
            val response = client().getChanges(next)
            if (response.changesTokenExpired) {
                return HealthChanges(tokenExpired = true, nextToken = null, samples = emptyList())
            }
            val records =
                response.changes
                    .filterIsInstance<UpsertionChange>()
                    .map { it.record }
                    .filterIsInstance<HeartRateRecord>()
            samples += HeartRateSampleMapping.toSamples(records)
            next = response.nextChangesToken
            if (!response.hasMore) break
        }
        return HealthChanges(tokenExpired = false, nextToken = next, samples = samples)
    }
}
