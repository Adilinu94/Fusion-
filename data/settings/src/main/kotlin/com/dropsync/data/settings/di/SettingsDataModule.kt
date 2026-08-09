package com.dropsync.data.settings.di

import android.content.Context
import com.dropsync.data.settings.AccentColorStore
import com.dropsync.data.settings.ThemeSettingsStore
import com.dropsync.domain.settings.AccentColorRepository
import com.dropsync.domain.settings.ThemeSettingsRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Verdrahtet die Einstellungs-Datenschicht: je ein Singleton fuer
 * [ThemeSettingsRepository] (App-Design) und [AccentColorRepository]
 * (Akzentfarbe), gemeinsam genutzt von App-Root und Einstellungen.
 */
@Module
@InstallIn(SingletonComponent::class)
object SettingsDataModule {
    @Provides
    @Singleton
    fun provideThemeSettingsRepository(
        @ApplicationContext context: Context,
    ): ThemeSettingsRepository = ThemeSettingsStore(context)

    @Provides
    @Singleton
    fun provideAccentColorRepository(
        @ApplicationContext context: Context,
    ): AccentColorRepository = AccentColorStore(context)
}
