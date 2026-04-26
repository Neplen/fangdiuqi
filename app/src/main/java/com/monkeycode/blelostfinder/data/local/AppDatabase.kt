package com.monkeycode.blelostfinder.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.monkeycode.blelostfinder.data.model.BleDevice
import com.monkeycode.blelostfinder.data.model.LocationRecord
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Database(
    entities = [BleDevice::class, LocationRecord::class],
    version = 1,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun bleDeviceDao(): BleDeviceDao
    abstract fun locationRecordDao(): LocationRecordDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "ble_lost_finder_database"
                ).build()
                INSTANCE = instance
                instance
            }
        }
    }
}

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
}
