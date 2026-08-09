package com.dropsync.data.library.di

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.preferencesDataStoreFile
import com.dropsync.core.common.Clock
import com.dropsync.core.common.DispatcherProvider
import com.dropsync.core.database.TransactionRunner
import com.dropsync.core.database.dao.CueTrackDao
import com.dropsync.core.database.dao.FavoriteDao
import com.dropsync.core.database.dao.LibraryBrowseDao
import com.dropsync.core.database.dao.MarkerDao
import com.dropsync.core.database.dao.PlayStatDao
import com.dropsync.core.database.dao.PlaylistDao
import com.dropsync.core.database.dao.SafFileDao
import com.dropsync.core.database.dao.SongDao
import com.dropsync.data.library.DataStoreScanStateStore
import com.dropsync.data.library.LibraryBrowseRepositoryImpl
import com.dropsync.data.library.LibraryRepositoryImpl
import com.dropsync.data.library.LibraryViewPreferencesStore
import com.dropsync.data.library.MarkerRepositoryImpl
import com.dropsync.data.library.MediaStoreGateway
import com.dropsync.data.library.MediaStoreGatewayImpl
import com.dropsync.data.library.MusicFolderFilterStore
import com.dropsync.data.library.SafFolderGateway
import com.dropsync.data.library.SafFolderGatewayImpl
import com.dropsync.data.library.ScanStateStore
import com.dropsync.domain.library.ImportValidator
import com.dropsync.domain.library.LibraryBrowseRepository
import com.dropsync.domain.library.LibraryRepository
import com.dropsync.domain.library.LibraryViewPreferencesRepository
import com.dropsync.domain.library.MarkerMatcher
import com.dropsync.domain.library.MarkerRepository
import com.dropsync.domain.library.MusicFolderFilterRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Verdrahtet die Bibliotheks-Datenschicht (Bauplan Schritt 4/6):
 * Features sehen nur die Domain-Interfaces aus :domain:library.
 */
@Module
@InstallIn(SingletonComponent::class)
object LibraryDataModule {
    @Provides
    @Singleton
    fun provideMediaStoreGateway(
        @ApplicationContext context: Context,
    ): MediaStoreGateway = MediaStoreGatewayImpl(context)

    @Provides
    @Singleton
    fun provideScanStateStore(
        @ApplicationContext context: Context,
    ): ScanStateStore =
        DataStoreScanStateStore(
            PreferenceDataStoreFactory.create {
                context.preferencesDataStoreFile(DataStoreScanStateStore.DATA_STORE_NAME)
            },
        )

    @Provides
    @Singleton
    fun provideSafFolderGateway(
        @ApplicationContext context: Context,
    ): SafFolderGateway = SafFolderGatewayImpl(context)

    @Provides
    @Singleton
    fun provideLibraryViewPreferences(
        @ApplicationContext context: Context,
    ): LibraryViewPreferencesRepository =
        LibraryViewPreferencesStore(
            PreferenceDataStoreFactory.create {
                context.preferencesDataStoreFile(LibraryViewPreferencesStore.DATA_STORE_NAME)
            },
        )

    @Provides
    @Singleton
    fun provideMusicFolderFilter(
        @ApplicationContext context: Context,
    ): MusicFolderFilterRepository =
        MusicFolderFilterStore(
            PreferenceDataStoreFactory.create {
                context.preferencesDataStoreFile(MusicFolderFilterStore.DATA_STORE_NAME)
            },
        )

    @Provides
    @Singleton
    fun provideLibraryRepository(
        gateway: MediaStoreGateway,
        songDao: SongDao,
        scanStateStore: ScanStateStore,
        transactionRunner: TransactionRunner,
        dispatchers: DispatcherProvider,
        cueTrackDao: CueTrackDao,
        safFileDao: SafFileDao,
        safGateway: SafFolderGateway,
        folderFilter: MusicFolderFilterRepository,
    ): LibraryRepository =
        LibraryRepositoryImpl(
            gateway = gateway,
            songDao = songDao,
            scanStateStore = scanStateStore,
            transactionRunner = transactionRunner,
            dispatchers = dispatchers,
            cueTrackDao = cueTrackDao,
            safFileDao = safFileDao,
            safGateway = safGateway,
            folderFilter = folderFilter,
        )

    @Provides
    @Singleton
    fun provideLibraryBrowseRepository(
        browseDao: LibraryBrowseDao,
        playStatDao: PlayStatDao,
        favoriteDao: FavoriteDao,
        playlistDao: PlaylistDao,
        songDao: SongDao,
        transactionRunner: TransactionRunner,
        dispatchers: DispatcherProvider,
    ): LibraryBrowseRepository =
        LibraryBrowseRepositoryImpl(
            browseDao = browseDao,
            playStatDao = playStatDao,
            favoriteDao = favoriteDao,
            playlistDao = playlistDao,
            songDao = songDao,
            transactionRunner = transactionRunner,
            dispatchers = dispatchers,
        )

    @Provides
    @Singleton
    fun provideMarkerRepository(
        markerDao: MarkerDao,
        songDao: SongDao,
        transactionRunner: TransactionRunner,
        clock: Clock,
        dispatchers: DispatcherProvider,
    ): MarkerRepository =
        MarkerRepositoryImpl(
            markerDao = markerDao,
            songDao = songDao,
            transactionRunner = transactionRunner,
            validator = ImportValidator(),
            matcher = MarkerMatcher(),
            clock = clock,
            dispatchers = dispatchers,
        )
}
