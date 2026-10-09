package com.greyrecon.app.history

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * One row per network the user has ever scanned, keyed by [com.greyrecon.app.engine.discovery.NetworkIdentity.key].
 * Exists so the History and Network Watch screens can say "Home" rather than "gw:a4:2b:8c:11:92:03",
 * and so a user can see at a glance which sites they have baselines for.
 */
@Entity(tableName = "network_profiles")
data class NetworkProfile(
    @PrimaryKey val networkKey: String,
    /** User-facing name. Seeded from NetworkIdentity.defaultLabel, renameable. */
    val label: String,
    /** False when the key came from the weaker gateway-IP fallback, so the UI can warn that two sites may collide. */
    val isStrongKey: Boolean,
    val firstSeenAt: Long,
    val lastSeenAt: Long,
    /** Per-network switch for background monitoring -- watching your home is wanted, watching an airport is not. */
    val watchEnabled: Boolean = false,
)

@Dao
interface NetworkProfileDao {

    @Query("SELECT * FROM network_profiles ORDER BY lastSeenAt DESC")
    fun observeAll(): Flow<List<NetworkProfile>>

    @Query("SELECT * FROM network_profiles WHERE networkKey = :key")
    suspend fun getByKey(key: String): NetworkProfile?

    @Query("SELECT * FROM network_profiles WHERE watchEnabled = 1")
    suspend fun getWatched(): List<NetworkProfile>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(profile: NetworkProfile)

    @Query("UPDATE network_profiles SET lastSeenAt = :seenAt WHERE networkKey = :key")
    suspend fun touch(key: String, seenAt: Long)

    @Query("UPDATE network_profiles SET label = :label WHERE networkKey = :key")
    suspend fun setLabel(key: String, label: String)

    @Query("UPDATE network_profiles SET watchEnabled = :enabled WHERE networkKey = :key")
    suspend fun setWatchEnabled(key: String, enabled: Boolean)

    @Query("DELETE FROM network_profiles WHERE networkKey = :key")
    suspend fun delete(key: String)
}
