package com.dropsync.data.playback.di

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.preferencesDataStoreFile
import com.dropsync.core.common.DispatcherProvider
import com.dropsync.data.audio.AudioPipeline
import com.dropsync.data.audio.OutputDeviceMonitor
import com.dropsync.data.playback.DataStorePlayerStateStore
import com.dropsync.data.playback.Media3AudioClock
import com.dropsync.data.playback.MediaControllerConnection
import com.dropsync.data.playback.PlaybackRepositoryImpl
import com.dropsync.data.playback.PlayerConnection
import com.dropsync.data.playback.PlayerStateStore
import com.dropsync.data.playback.PlayerVolumeGateImpl
import com.dropsync.data.playback.RestDuckingGateImpl
import com.dropsync.data.playback.RestMusicSettingsStore
import com.dropsync.data.playback.RouteProfileStore
import com.dropsync.domain.playback.PlaybackRepository
import com.dropsync.domain.playback.PlayerVolumeGate
import com.dropsync.domain.playback.RestDuckingGate
import com.dropsync.domain.playback.RestMusicSettingsRepository
import com.dropsync.domain.playback.RouteProfileRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Verdrahtet die Wiedergabe-Datenschicht (Bauplan Schritt 5):
 * genau ein PlayerConnection-Singleton fuer alle Screens.
 */
@Module
@InstallIn(SingletonComponent::class)
object PlaybackDataModule {
    @Provides
    @Singleton
    fun providePlayerConnection(
        @ApplicationContext context: Context,
    ): PlayerConnection = MediaControllerConnection(context)

    @Provides
    @Singleton
    fun providePlayerStateStore(
        @ApplicationContext context: Context,
    ): PlayerStateStore =
        DataStorePlayerStateStore(
            PreferenceDataStoreFactory.create {
                context.preferencesDataStoreFile(DataStorePlayerStateStore.DATA_STORE_NAME)
            },
        )

    @Provides
    @Singleton
    fun providePlaybackRepository(
        connection: PlayerConnection,
        stateStore: PlayerStateStore,
        dispatchers: DispatcherProvider,
    ): PlaybackRepository = PlaybackRepositoryImpl(connection, stateStore, dispatchers)

    @Provides
    @Singleton
    fun providePlayerVolumeGate(pipeline: AudioPipeline): PlayerVolumeGate = PlayerVolumeGateImpl(pipeline)

    @Provides
    @Singleton
    fun provideRestMusicSettingsRepository(
        @ApplicationContext context: Context,
    ): RestMusicSettingsRepository = RestMusicSettingsStore(context)

    @Provides
    @Singleton
    fun provideRouteProfileRepository(
        @ApplicationContext context: Context,
        deviceMonitor: OutputDeviceMonitor,
    ): RouteProfileRepository = RouteProfileStore(context, deviceMonitor)

    /**
     * MVP-AudioClock (Design Phase 6): Der Service bindet den Player
     * per [Media3AudioClock.attach]; der Rest der App liest nur.
     */
    @Provides
    @Singleton
    fun provideAudioClock(): Media3AudioClock = Media3AudioClock()

    /** Rest-Ducking auf dem Preamp-Knoten (Design Phase 7). */
    @Provides
    @Singleton
    fun provideRestDuckingGate(pipeline: AudioPipeline): RestDuckingGate = RestDuckingGateImpl(pipeline)
}
