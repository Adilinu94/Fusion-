package com.dropsync.data.sensor.di

import com.dropsync.data.sensor.BleSensorProvider
import com.dropsync.data.sensor.DataStoreCalibrationProfileRepository
import com.dropsync.data.sensor.FakeSensorProvider
import com.dropsync.data.sensor.SwitchingSensorProvider
import com.dropsync.domain.sensor.CalibrationProfileRepository
import com.dropsync.domain.sensor.SensorProvider
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Sensor wiring (Fusion Phase 4 steps 2–4).
 *
 * The FakeSensorProvider remains the bound SensorProvider as long as no
 * FlowRep chip has connected (manual +/- fallback). The real
 * BleSensorProvider is injected eagerly so a successful connect() can swap
 * the live binding at runtime without re-creating dependent ViewModels —
 * the module exposes it as the active provider once its connectionState
 * leaves DISCONNECTED.
 */
@Module
@InstallIn(SingletonComponent::class)
object SensorDataModule {
    @Provides
    @Singleton
    fun provideSensorProvider(
        ble: BleSensorProvider,
        fake: FakeSensorProvider,
    ): SensorProvider = SwitchingSensorProvider(ble, fake)

    @Provides
    @Singleton
    fun provideCalibrationProfileRepository(
        store: DataStoreCalibrationProfileRepository,
    ): CalibrationProfileRepository = store
}
