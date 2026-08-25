package com.greyrecon.app.engine.discovery

import android.content.Context
import com.greyrecon.app.history.GreyReconDatabase
import com.greyrecon.app.history.TrackerSighting

/**
 * Records each detected [TrackerType] sighting keyed by BLE address, so a repeat sighting across
 * separate BLE scans (not necessarily the same physical tracker -- see [TrackerSighting]'s own
 * caveat about address rotation) can be surfaced as "seen N times since first noticed" rather than
 * just a one-off classification. Kept in `engine/discovery` alongside [BleScanner] rather than
 * folded into [com.greyrecon.app.history.DeviceHistoryStore] -- same reasoning as [BleDevice] itself
 * living apart from the WiFi-network `Device` model: a BLE address isn't that pipeline's IP-keyed
 * identity at all.
 */
class TrackerSightingStore(context: Context) {

    private val dao = GreyReconDatabase.get(context).trackerSightingDao()

    suspend fun recordSighting(address: String, trackerType: TrackerType): TrackerSighting {
        val now = System.currentTimeMillis()
        val existing = dao.getByAddress(address)
        val updated = existing?.copy(
            trackerType = trackerType.name,
            lastSeenAt = now,
            sightingCount = existing.sightingCount + 1,
        ) ?: TrackerSighting(
            bleAddress = address,
            trackerType = trackerType.name,
            firstSeenAt = now,
            lastSeenAt = now,
            sightingCount = 1,
        )
        dao.upsert(updated)
        return updated
    }
}
