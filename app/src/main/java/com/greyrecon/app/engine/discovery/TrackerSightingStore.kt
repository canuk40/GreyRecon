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

    /**
     * Records one address as seen in the current scan session. Callers must call this at most once
     * per address per session -- [com.greyrecon.app.ui.tools.BleScanScreen] and the background
     * watcher both deduplicate before calling, because BLE advertisements arrive many times a
     * second and counting packets instead of sessions makes the number useless for follow
     * detection.
     */
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

    /**
     * Trackers that look like they may be travelling with the user: seen across several separate
     * scan sessions spread over a meaningful stretch of time.
     *
     * This is deliberately weaker than AirGuard's test, which correlates sightings against
     * *location* changes. GreyRecon does not hold a location permission and is not going to
     * acquire one for this, so it cannot tell "followed me across town" from "I sat next to the
     * same backpack all afternoon". The wording of the alert has to stay honest about that.
     */
    suspend fun followCandidates(
        minSessions: Int = MIN_SESSIONS,
        minSpanMs: Long = MIN_SPAN_MS,
        reAlertAfterMs: Long = RE_ALERT_AFTER_MS,
    ): List<TrackerSighting> = dao.getFollowCandidates(
        minSessions = minSessions,
        minSpanMs = minSpanMs,
        alertCutoff = System.currentTimeMillis() - reAlertAfterMs,
    )

    suspend fun markAlerted(address: String) = dao.setAlerted(address, System.currentTimeMillis())

    companion object {
        /** Four separate sessions: enough that a single passing stranger does not trigger it. */
        const val MIN_SESSIONS = 4
        /** Spread over at least half an hour, so four scans in one burst do not count. */
        const val MIN_SPAN_MS = 30L * 60L * 1000L
        /** Do not re-warn about the same address more than once a day. */
        const val RE_ALERT_AFTER_MS = 24L * 60L * 60L * 1000L
    }
}
