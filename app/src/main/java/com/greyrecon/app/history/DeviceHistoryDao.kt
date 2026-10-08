package com.greyrecon.app.history

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface DeviceHistoryDao {

    @Query("SELECT * FROM device_history WHERE networkKey = :networkKey ORDER BY lastSeenAt DESC")
    fun observeForNetwork(networkKey: String): Flow<List<DeviceRecord>>

    @Query("SELECT id FROM device_history WHERE networkKey = :networkKey")
    suspend fun getIdsForNetwork(networkKey: String): List<String>

    @Query("SELECT * FROM device_history WHERE isOnline = 1 AND networkKey = :networkKey")
    suspend fun getOnlineRecords(networkKey: String): List<DeviceRecord>

    @Query("UPDATE device_history SET isOnline = :online WHERE id = :id")
    suspend fun setOnline(id: String, online: Boolean)

    @Query("SELECT * FROM device_history WHERE id = :id")
    suspend fun getById(id: String): DeviceRecord?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(record: DeviceRecord)

    @Query("UPDATE device_history SET customName = :name WHERE id = :id")
    suspend fun setCustomName(id: String, name: String?)

    @Query("UPDATE device_history SET notes = :notes WHERE id = :id")
    suspend fun setNotes(id: String, notes: String?)
}
