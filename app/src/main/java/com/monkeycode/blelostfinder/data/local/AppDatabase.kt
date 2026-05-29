package com.monkeycode.blelostfinder.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.monkeycode.blelostfinder.data.model.BleDevice
import com.monkeycode.blelostfinder.data.model.LocationRecord

@Database(
    entities = [BleDevice::class, LocationRecord::class],
    version = 2,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun bleDeviceDao(): BleDeviceDao
    abstract fun locationRecordDao(): LocationRecordDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // 创建新表（不含 alarmDelaySeconds）
                database.execSQL("""
                    CREATE TABLE ble_devices_new (
                        macAddress TEXT PRIMARY KEY NOT NULL,
                        name TEXT NOT NULL,
                        rssiThreshold INTEGER NOT NULL,
                        alarmRingtonePath TEXT,
                        isWifiDndEnabled INTEGER NOT NULL,
                        isScheduleDndEnabled INTEGER NOT NULL,
                        dndStartTime TEXT NOT NULL,
                        dndEndTime TEXT NOT NULL,
                        lastConnectedTime INTEGER,
                        lastDisconnectedTime INTEGER,
                        lastKnownLocation REAL,
                        lastKnownLocationLongitude REAL,
                        batteryLevel INTEGER NOT NULL,
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL
                    )
                """)
                // 复制数据
                database.execSQL("""
                    INSERT INTO ble_devices_new (
                        macAddress, name, rssiThreshold, alarmRingtonePath,
                        isWifiDndEnabled, isScheduleDndEnabled, dndStartTime, dndEndTime,
                        lastConnectedTime, lastDisconnectedTime, lastKnownLocation,
                        lastKnownLocationLongitude, batteryLevel, createdAt, updatedAt
                    ) SELECT 
                        macAddress, name, rssiThreshold, alarmRingtonePath,
                        isWifiDndEnabled, isScheduleDndEnabled, dndStartTime, dndEndTime,
                        lastConnectedTime, lastDisconnectedTime, lastKnownLocation,
                        lastKnownLocationLongitude, batteryLevel, createdAt, updatedAt
                    FROM ble_devices
                """)
                // 删除旧表
                database.execSQL("DROP TABLE ble_devices")
                // 重命名新表
                database.execSQL("ALTER TABLE ble_devices_new RENAME TO ble_devices")
            }
        }

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "ble_lost_finder_database"
                )
                .addMigrations(MIGRATION_1_2)
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}