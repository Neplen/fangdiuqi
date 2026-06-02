package com.monkeycode.blelostfinder.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.monkeycode.blelostfinder.data.model.BleDevice
import com.monkeycode.blelostfinder.data.model.LocationRecord

@Database(
    entities = [BleDevice::class, LocationRecord::class],
    // 核心修复：升级数据库版本到 3，因为 BleDevice 实体的默认值有变化
    version = 3,
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
                )
                // 核心修复：添加破坏性迁移策略，因为版本升级但无复杂迁移需求
                // 用户首次安装或清除数据后重装，数据库会重新创建
                .fallbackToDestructiveMigration()
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}