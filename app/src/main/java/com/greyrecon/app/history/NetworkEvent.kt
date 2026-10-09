package com.greyrecon.app.history

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A recorded change on the network, distinct from [DeviceRecord] which only holds a device's
 * current known state. Lets the History screen answer "what changed since I last looked" instead
 * of just "what's here now" -- the gap every competitor scanner (Fing, PingTools) leaves open.
 */
@Entity(tableName = "network_events")
data class NetworkEvent(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Which network this event happened on -- see [com.greyrecon.app.engine.discovery.NetworkIdentity]. */
    val networkKey: String,
    val deviceId: String,
    val type: String,
    val timestamp: Long,
    val detail: String,
) {
    companion object {
        const val NEW_DEVICE = "NEW_DEVICE"
        const val WENT_OFFLINE = "WENT_OFFLINE"
        const val IP_CHANGED = "IP_CHANGED"
        const val RECLASSIFIED = "RECLASSIFIED"

        /** Emitted by the background watcher rather than a foreground scan. */
        const val WATCH_ALERT = "WATCH_ALERT"
    }
}
