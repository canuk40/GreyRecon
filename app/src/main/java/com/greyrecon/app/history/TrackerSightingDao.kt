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

    @Query("UPDATE tracker_sightings SET alertedAt = :at WHERE bleAddress = :address")
    suspend fun setAlerted(address: String, at: Long)

    /**
     * Trackers seen in at least [minSessions] separate scan sessions spanning at least
     * [minSpanMs], that have not been alerted since [alertCutoff].
     */
    @Query(
        "SELECT * FROM tracker_sightings WHERE sightingCount >= :minSessions " +
            "AND (lastSeenAt - firstSeenAt) >= :minSpanMs " +
            "AND (alertedAt IS NULL OR alertedAt < :alertCutoff) " +
            "ORDER BY lastSeenAt DESC"
    )
    suspend fun getFollowCandidates(minSessions: Int, minSpanMs: Long, alertCutoff: Long): List<TrackerSighting>
}
