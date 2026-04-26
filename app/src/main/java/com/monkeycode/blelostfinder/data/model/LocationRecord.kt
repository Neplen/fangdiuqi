package com.monkeycode.blelostfinder.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "location_records")
data class LocationRecord(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val deviceMac: String,
    val latitude: Double,
    val longitude: Double,
    val address: String? = null,
    val timestamp: Long = System.currentTimeMillis(),
    val rssi: Int? = null,
    val eventType: String // "DISCONNECTED", "ALARM", "LOW_BATTERY"
)
