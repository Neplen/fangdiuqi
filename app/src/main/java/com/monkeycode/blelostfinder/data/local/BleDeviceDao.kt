package com.monkeycode.blelostfinder.data.local

import androidx.room.*
import com.monkeycode.blelostfinder.data.model.BleDevice
import kotlinx.coroutines.flow.Flow

@Dao
interface BleDeviceDao {
    @Query("SELECT * FROM ble_devices ORDER BY updatedAt DESC")
    fun getAllDevices(): Flow<List<BleDevice>>

    @Query("SELECT * FROM ble_devices WHERE macAddress = :macAddress")
    suspend fun getDeviceByMac(macAddress: String): BleDevice?

    @Query("SELECT * FROM ble_devices WHERE macAddress = :macAddress")
    fun getDeviceByMacFlow(macAddress: String): Flow<BleDevice?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDevice(device: BleDevice)

    @Update
    suspend fun updateDevice(device: BleDevice)

    @Delete
    suspend fun deleteDevice(device: BleDevice)

    @Query("DELETE FROM ble_devices WHERE macAddress = :macAddress")
    suspend fun deleteDeviceByMac(macAddress: String)

    @Query("SELECT COUNT(*) FROM ble_devices")
    suspend fun getDeviceCount(): Int
}
