package com.dropsync.data.health.di

import android.content.Context
import androidx.activity.result.contract.ActivityResultContract
import androidx.health.connect.client.PermissionController
import com.dropsync.core.common.Clock
import com.dropsync.core.common.DispatcherProvider
import com.dropsync.data.health.HealthConnectGatewayImpl
import com.dropsync.data.health.HealthConnectHeartRateSource
import com.dropsync.data.health.HealthSettingsStore
import com.dropsync.domain.health.HealthPermissionContract
import com.dropsync.domain.health.HeartRateSource
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Verdrahtet die Health-Connect-Herzfrequenz (Herzfrequenz-Plan Phase 1). */
@Module
@InstallIn(SingletonComponent::class)
internal object HealthDataModule {
    @Provides
    @Singleton
    internal fun provideHeartRateSource(
        @ApplicationContext context: Context,
        settingsStore: HealthSettingsStore,
        clock: Clock,
        dispatchers: DispatcherProvider,
    ): HeartRateSource =
        HealthConnectHeartRateSource(
            gateway = HealthConnectGatewayImpl(context),
            tokenStore = settingsStore,
            clock = clock,
            dispatchers = dispatchers,
        )

    /**
     * Generischer Berechtigungs-Contract (Plan 3.2, Review-Punkt 2):
     * Features registrieren ihn per rememberLauncherForActivityResult,
     * ohne das Health-Connect-SDK zu kennen.
     */
    @Provides
    @HealthPermissionContract
    internal fun provideHealthPermissionContract(): ActivityResultContract<Set<String>, Set<String>> =
        PermissionController.createRequestPermissionResultContract()
}
