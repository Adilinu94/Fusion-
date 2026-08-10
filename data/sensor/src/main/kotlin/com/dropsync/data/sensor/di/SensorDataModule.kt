package com.dropsync.data.sensor.di

import com.dropsync.data.sensor.FakeSensorProvider
import com.dropsync.domain.sensor.SensorProvider
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Sensor wiring (Fusion Phase 4 step 4): the FakeSensorProvider is bound as
 * long as no FlowRep chip is connected (manual +/- fallback). The real
 * BleSensorProvider replaces this binding in a follow-up step once the BLE
 * connect flow (scan, MTU 185, ControlPoint) is ported.
 */
@Module
@InstallIn(SingletonComponent::class)
object SensorDataModule {
    @Provides
    @Singleton
    fun provideSensorProvider(fake: FakeSensorProvider): SensorProvider = fake
}
