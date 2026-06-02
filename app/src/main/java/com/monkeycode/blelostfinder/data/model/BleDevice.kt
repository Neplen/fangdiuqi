package com.monkeycode.blelostfinder.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "ble_devices")
data class BleDevice(
    @PrimaryKey
    val macAddress: String,
    // 核心修复：移除默认名称"iTAG"，改为空字符串，由扫描时实际获取的设备名填充
    val name: String = "",
    val rssiThreshold: Int = -90,
    val alarmRingtonePath: String? = null,
    val isWifiDndEnabled: Boolean = true,
    val isScheduleDndEnabled: Boolean = true,
    val dndStartTime: String = "21:00",
    val dndEndTime: String = "08:00",
    val lastConnectedTime: Long? = null,
    val lastDisconnectedTime: Long? = null,
    val lastKnownLocation: Double? = null, // latitude
    val lastKnownLocationLongitude: Double? = null,
    val batteryLevel: Int = -1,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)