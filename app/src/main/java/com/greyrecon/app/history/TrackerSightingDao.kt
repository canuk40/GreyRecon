package com.greyrecon.app.history

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface TrackerSightingDao {

    @Query("SELECT * FROM tracker_sightings WHERE bleAddress = :address")
    suspend fun getByAddress(address: String): TrackerSighting?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(sighting: TrackerSighting)

    @Query("SELECT * FROM tracker_sightings ORDER BY lastSeenAt DESC")
    suspend fun getAll(): List<TrackerSighting>
}
