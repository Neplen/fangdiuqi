package com.monkeycode.blelostfinder.data.repository

import com.monkeycode.blelostfinder.data.local.BleDeviceDao
import com.monkeycode.blelostfinder.data.local.LocationRecordDao
import com.monkeycode.blelostfinder.data.model.BleDevice
import com.monkeycode.blelostfinder.data.model.LocationRecord
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DeviceRepository @Inject constructor(
    private val bleDeviceDao: BleDeviceDao,
    private val locationRecordDao: LocationRecordDao
) {
    val allDevices: Flow<List<BleDevice>> = bleDeviceDao.getAllDevices()

    suspend fun getDeviceByMac(macAddress: String): BleDevice? {
        return bleDeviceDao.getDeviceByMac(macAddress)
    }

    fun getDeviceByMacFlow(macAddress: String): Flow<BleDevice?> {
        return bleDeviceDao.getDeviceByMacFlow(macAddress)
    }

    // 新增：获取第一个已保存的设备
    suspend fun getFirstDevice(): BleDevice? {
        return bleDeviceDao.getFirstDevice()
    }

    suspend fun insertDevice(device: BleDevice) {
        bleDeviceDao.insertDevice(device)
    }

    suspend fun updateDevice(device: BleDevice) {
        bleDeviceDao.updateDevice(device)
    }

    suspend fun deleteDevice(device: BleDevice) {
        bleDeviceDao.deleteDevice(device)
    }

    suspend fun saveLocationRecord(record: LocationRecord) {
        locationRecordDao.insertRecord(record)
    }

    suspend fun getLastDisconnectedLocation(): LocationRecord? {
        return locationRecordDao.getLastDisconnectedLocation()
    }

    fun getDeviceLocationRecords(deviceMac: String): Flow<List<LocationRecord>> {
        return locationRecordDao.getRecordsByDevice(deviceMac)
    }
}