package com.monkeycode.blelostfinder.data.local

import androidx.room.*
import com.monkeycode.blelostfinder.data.model.LocationRecord
import kotlinx.coroutines.flow.Flow

@Dao
interface LocationRecordDao {
    @Query("SELECT * FROM location_records WHERE deviceMac = :deviceMac ORDER BY timestamp DESC")
    fun getRecordsByDevice(deviceMac: String): Flow<List<LocationRecord>>

    @Query("SELECT * FROM location_records WHERE deviceMac = :deviceMac ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLastRecord(deviceMac: String): LocationRecord?

    @Query("SELECT * FROM location_records WHERE eventType = 'DISCONNECTED' ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLastDisconnectedLocation(): LocationRecord?

    @Insert
    suspend fun insertRecord(record: LocationRecord)

    @Query("DELETE FROM location_records WHERE deviceMac = :deviceMac")
    suspend fun deleteRecordsByDevice(deviceMac: String)

    @Query("DELETE FROM location_records WHERE timestamp < :timestamp")
    suspend fun deleteOldRecords(timestamp: Long)
}
