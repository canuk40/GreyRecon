package com.greyrecon.app.history

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface DeviceHistoryDao {

    @Query("SELECT * FROM device_history ORDER BY lastSeenAt DESC")
    fun observeAll(): Flow<List<DeviceRecord>>

    @Query("SELECT id FROM device_history")
    suspend fun getAllIds(): List<String>

    @Query("SELECT * FROM device_history WHERE isOnline = 1")
    suspend fun getOnlineRecords(): List<DeviceRecord>

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
