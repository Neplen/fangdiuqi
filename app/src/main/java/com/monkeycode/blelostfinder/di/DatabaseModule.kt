package com.monkeycode.blelostfinder.di

import android.content.Context
import com.monkeycode.blelostfinder.data.local.AppDatabase
import com.monkeycode.blelostfinder.data.local.BleDeviceDao
import com.monkeycode.blelostfinder.data.local.LocationRecordDao
import com.monkeycode.blelostfinder.data.repository.DeviceRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    
    @Provides
    @Singleton
    fun provideDatabase(
        @ApplicationContext context: Context
    ): AppDatabase {
        return AppDatabase.getDatabase(context)
    }

    @Provides
    @Singleton
    fun provideBleDeviceDao(database: AppDatabase): BleDeviceDao {
        return database.bleDeviceDao()
    }

    @Provides
    @Singleton
    fun provideLocationRecordDao(database: AppDatabase): LocationRecordDao {
        return database.locationRecordDao()
    }
    
    @Provides
    @Singleton
    fun provideDeviceRepository(
        bleDeviceDao: BleDeviceDao,
        locationRecordDao: LocationRecordDao
    ): DeviceRepository {
        return DeviceRepository(bleDeviceDao, locationRecordDao)
    }
}
