package com.dropsync.feature.player.di

import com.dropsync.domain.timer.DropSyncStateSource
import com.dropsync.feature.player.DropSyncCoordinator
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Bindet den app-weiten DropSync-Zustand (P1-10) an den Domain-Port:
 * Der Train-Tab und der Mini-Player lesen `DropSyncStateSource`, ohne
 * das Player-Feature zu importieren.
 */
@Module
@InstallIn(SingletonComponent::class)
interface PlayerFeatureModule {
    @Binds
    @Singleton
    fun bindDropSyncStateSource(coordinator: DropSyncCoordinator): DropSyncStateSource
}
