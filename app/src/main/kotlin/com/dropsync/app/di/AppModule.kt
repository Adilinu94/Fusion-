package com.dropsync.app.di

import android.os.SystemClock
import com.dropsync.core.common.Clock
import com.dropsync.core.common.DispatcherProvider
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import javax.inject.Singleton

/**
 * Produktionszeitquelle: monotone Uhr fuer Timer, Epoch fuer Persistenz
 * (Bauplan Schritt 2.5).
 */
private class AndroidClock : Clock {
    override fun elapsedRealtimeMs(): Long = SystemClock.elapsedRealtime()

    override fun epochMillis(): Long = System.currentTimeMillis()
}

private class DefaultDispatcherProvider : DispatcherProvider {
    override val io: CoroutineDispatcher = Dispatchers.IO
    override val default: CoroutineDispatcher = Dispatchers.Default
    override val main: CoroutineDispatcher = Dispatchers.Main
}

/**
 * Hilt-Singletons gemaess Bauplan Schritt 2.4: nur Datenbank,
 * MediaStore-Client, Playback-Controller-Connector, Clock und
 * Dispatcher-Provider. Datenbank und Media-Objekte folgen in den
 * Schritten 3 bis 5. ViewModels werden nie als Singleton gebunden.
 */
@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides
    @Singleton
    fun provideClock(): Clock = AndroidClock()

    @Provides
    @Singleton
    fun provideDispatcherProvider(): DispatcherProvider = DefaultDispatcherProvider()
}
