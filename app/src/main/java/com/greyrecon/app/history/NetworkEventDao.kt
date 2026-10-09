package com.greyrecon.app.history

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface NetworkEventDao {

    @Query("SELECT * FROM network_events WHERE networkKey = :networkKey ORDER BY timestamp DESC")
    fun observeForNetwork(networkKey: String): Flow<List<NetworkEvent>>

    @Query("UPDATE network_events SET networkKey = :newKey, deviceId = :newKey || '|' || substr(deviceId, length(:oldKey) + 2) WHERE networkKey = :oldKey")
    suspend fun repointNetwork(oldKey: String, newKey: String)

    @Insert
    suspend fun insert(event: NetworkEvent)
}
