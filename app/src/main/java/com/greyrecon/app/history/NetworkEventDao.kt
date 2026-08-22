package com.greyrecon.app.history

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface NetworkEventDao {

    @Query("SELECT * FROM network_events ORDER BY timestamp DESC")
    fun observeAll(): Flow<List<NetworkEvent>>

    @Insert
    suspend fun insert(event: NetworkEvent)
}
