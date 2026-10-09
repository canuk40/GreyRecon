package com.greyrecon.app.history

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A device's persisted identity across scans, distinct from [com.greyrecon.app.engine.model.Device]
 * (which is a single scan's snapshot). Identity is the MAC address when known -- the one thing that
 * survives a DHCP lease change -- falling back to "ip:<address>" when no discovery method supplied a
 * MAC. IP-only identities are a real limitation on networks where ARP is blocked (see GreyRecon.md
 * SESSION 2/5): a different physical device could get reassigned that same IP later and be merged
 * into this record's history, since there's no more reliable identity available without a MAC.
 */
@Entity(tableName = "device_history")
data class DeviceRecord(
    /**
     * Scoped identity: "<networkKey>|<mac-or-ip>". Scoping matters because the same MAC is a
     * different thing to the user on a different network -- and more importantly an unscoped
     * history makes every device at a second site look like an intruder at the first.
     */
    @PrimaryKey val id: String,
    /** Which network this record belongs to -- see [com.greyrecon.app.engine.discovery.NetworkIdentity]. */
    val networkKey: String,
    val macAddress: String?,
    val lastKnownIp: String,
    val vendor: String?,
    val hostname: String?,
    val deviceType: String,
    val customName: String?,
    val notes: String?,
    val firstSeenAt: Long,
    val lastSeenAt: Long,
    /** False once a device with an established presence (seen in more than one prior scan) is absent from a scan -- flips back to true the moment it reappears. Drives WENT_OFFLINE events without re-firing one every scan it stays gone. */
    val isOnline: Boolean = true,
)
