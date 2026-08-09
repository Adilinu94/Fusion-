package com.dropsync.data.audio.di

import com.dropsync.core.common.DispatcherProvider
import com.dropsync.core.database.TransactionRunner
import com.dropsync.core.database.dao.EqPresetDao
import com.dropsync.core.database.dao.TrackAnalysisDao
import com.dropsync.data.audio.AudioEngineRepositoryImpl
import com.dropsync.data.audio.AudioPipeline
import com.dropsync.data.audio.BitPerfectGateway
import com.dropsync.data.audio.DeviceProfileStore
import com.dropsync.data.audio.DspSettingsStore
import com.dropsync.data.audio.OutputDeviceMonitor
import com.dropsync.data.audio.OutputProfileController
import com.dropsync.data.audio.TrackAnalysisRepositoryImpl
import com.dropsync.data.audio.TrackAnalyzerImpl
import com.dropsync.domain.audio.AudioEngineRepository
import com.dropsync.domain.audio.TrackAnalysisRepository
import com.dropsync.domain.audio.TrackAnalyzer
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import javax.inject.Singleton

/** Hilt-Anbindung der Audio-Engine (ADR-0005, Plan Phase 2). */
@Module
@InstallIn(SingletonComponent::class)
object AudioDataModule {
    @Provides
    @Singleton
    fun provideOutputProfileController(
        deviceMonitor: OutputDeviceMonitor,
        settingsStore: DspSettingsStore,
        profileStore: DeviceProfileStore,
        dispatchers: DispatcherProvider,
    ): OutputProfileController =
        OutputProfileController(
            deviceSnapshots = deviceMonitor.device,
            settingsStore = settingsStore,
            profileStore = profileStore,
            scope = CoroutineScope(SupervisorJob() + dispatchers.io),
        )

    @Provides
    @Singleton
    fun provideAudioEngineRepository(
        settingsStore: DspSettingsStore,
        pipeline: AudioPipeline,
        eqPresetDao: EqPresetDao,
        transactionRunner: TransactionRunner,
        dispatchers: DispatcherProvider,
        profileController: OutputProfileController,
        bitPerfectGateway: BitPerfectGateway,
    ): AudioEngineRepository =
        AudioEngineRepositoryImpl(
            settingsStore = settingsStore,
            pipeline = pipeline,
            eqPresetDao = eqPresetDao,
            transactionRunner = transactionRunner,
            dispatchers = dispatchers,
            profileController = profileController,
            bitPerfectGateway = bitPerfectGateway,
        )

    @Provides
    @Singleton
    fun provideTrackAnalyzer(
        @ApplicationContext context: android.content.Context,
        dispatchers: DispatcherProvider,
    ): TrackAnalyzer = TrackAnalyzerImpl(context = context, dispatchers = dispatchers)

    @Provides
    @Singleton
    fun provideTrackAnalysisRepository(
        @ApplicationContext context: android.content.Context,
        trackAnalysisDao: TrackAnalysisDao,
    ): TrackAnalysisRepository =
        TrackAnalysisRepositoryImpl(
            context = context,
            trackAnalysisDao = trackAnalysisDao,
        )
}
