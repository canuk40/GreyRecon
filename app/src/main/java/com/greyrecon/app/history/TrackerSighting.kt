package com.greyrecon.app.history

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A Bluetooth item-finder tracker (see [com.greyrecon.app.engine.discovery.TrackerType]) seen
 * across one or more BLE scans, keyed by its advertised BLE address.
 *
 * Best-effort, not a full "is this following me" guarantee: privacy-respecting trackers (Apple's
 * AirTag/Find My accessories, Samsung's SmartTag) deliberately rotate their advertising address on
 * a timer specifically so a passive observer can't correlate sightings by address alone -- exactly
 * what this table does. A rotation means the *same physical tracker* shows up here as a brand-new
 * row, undercounting real repeat sightings for those types. Trackers with less aggressive rotation
 * (Tile, Chipolo, Pebblebee) are tracked more reliably by this same mechanism. This is deliberately
 * scoped to what's achievable from GreyRecon's existing on-demand BLE scan (no background location
 * tracking, no continuous scanning service) -- AirGuard's own cross-rotation correlation and
 * continuous background monitoring are a meaningfully bigger undertaking than this first pass.
 */
@Entity(tableName = "tracker_sightings")
data class TrackerSighting(
    @PrimaryKey val bleAddress: String,
    val trackerType: String,
    val firstSeenAt: Long,
    val lastSeenAt: Long,
    val sightingCount: Int,
)
