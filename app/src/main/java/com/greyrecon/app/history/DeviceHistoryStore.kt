package com.greyrecon.app.history

import android.content.Context
import com.greyrecon.app.engine.discovery.NetworkIdentity
import com.greyrecon.app.engine.model.Device
import kotlinx.coroutines.flow.Flow

/**
 * Shared by ScanViewModel (UI scans), the MCP server's ScanDataSource (nanobot-triggered scans)
 * and NetworkWatchWorker (background scans) so a device's history is the same regardless of which
 * surface found it. Owns the "is this device genuinely new" logic and the notification that
 * follows from it.
 *
 * Everything here is scoped to a [NetworkIdentity] key. Before that scoping existed, history was
 * one global pool: scanning a second network merged its devices into the first network's baseline
 * and reported every one of them as a new device that had just joined. That was survivable while
 * scans were manual and foreground; it makes background monitoring impossible, because walking
 * into a cafe would fire a wave of intruder alerts about the cafe's own equipment.
 */
class DeviceHistoryStore(context: Context) {

    private val dao = GreyReconDatabase.get(context).deviceHistoryDao()
    private val eventDao = GreyReconDatabase.get(context).networkEventDao()
    private val profileDao = GreyReconDatabase.get(context).networkProfileDao()
    private val notifier = NewDeviceNotifier(context)

    fun history(networkKey: String): Flow<List<DeviceRecord>> = dao.observeForNetwork(networkKey)
    fun events(networkKey: String): Flow<List<NetworkEvent>> = eventDao.observeForNetwork(networkKey)
    val profiles: Flow<List<NetworkProfile>> = profileDao.observeAll()

    /**
     * Ensures a [NetworkProfile] row exists for this network and marks it seen, so the History
     * screen can name a network the first time it is scanned.
     */
    suspend fun registerNetwork(identity: NetworkIdentity): NetworkProfile {
        val now = System.currentTimeMillis()
        adoptWeakProfileIfUpgraded(identity)
        val existing = profileDao.getByKey(identity.key)
        if (existing != null) {
            profileDao.touch(identity.key, now)
            return existing.copy(lastSeenAt = now)
        }
        val created = NetworkProfile(
            networkKey = identity.key,
            label = identity.defaultLabel,
            isStrongKey = identity.isStrong,
            firstSeenAt = now,
            lastSeenAt = now,
            watchEnabled = false,
        )
        profileDao.upsert(created)
        return created
    }

    /**
     * Carries a network's history forward when its key gets *better*.
     *
     * [NetworkIdentity] falls back to a gateway-IP key when it can read neither the neighbour
     * table nor the router's UPnP description. Either can start working later -- UPnP gets
     * switched on, the user changes ROM, the router is replaced -- and the key then changes from
     * `net:192.168.3.1/24` to a strong one. Without this, that silently orphans the entire
     * baseline: the History screen goes empty and the next background scan reports every device
     * in the house as a new arrival. That flood is precisely the failure per-network scoping
     * exists to prevent, so the upgrade must not cause it.
     *
     * Only runs in the safe direction and only when unambiguous: the new key must be strong, the
     * old one must be the weak key for this same gateway, and the destination must have no rows
     * of its own. Where both already hold data this does nothing rather than attempting a merge,
     * because silently combining two networks' histories is worse than leaving two profiles.
     */
    private suspend fun adoptWeakProfileIfUpgraded(identity: NetworkIdentity) {
        if (!identity.isStrong) return
        val weakKey = identity.weakKeyForSameGateway ?: return
        if (weakKey == identity.key) return
        if (profileDao.getByKey(weakKey) == null) return
        if (dao.countForNetwork(identity.key) > 0) return

        dao.repointNetwork(oldKey = weakKey, newKey = identity.key)
        eventDao.repointNetwork(oldKey = weakKey, newKey = identity.key)
        profileDao.delete(weakKey)
    }

    suspend fun setNetworkLabel(key: String, label: String) = profileDao.setLabel(key, label)
    suspend fun setWatchEnabled(key: String, enabled: Boolean) = profileDao.setWatchEnabled(key, enabled)
    suspend fun watchedNetworks(): List<NetworkProfile> = profileDao.getWatched()

    /**
     * Records every device from a completed scan, diffing against what was already known *on this
     * network* so real changes -- not just current state -- get surfaced as [NetworkEvent]s: new
     * devices, devices with an established presence that dropped off this scan, IP changes on a
     * MAC-identified device (DHCP renewal, or something new answering for a known MAC), and
     * reclassification. Notifies for genuinely new devices -- unless this is the first scan ever
     * run on this network, so arriving somewhere new does not fire N notifications for devices
     * that were already there.
     *
     * @param silent suppresses per-device notifications without suppressing events. Used by the
     *   background watcher, which posts one grouped alert itself rather than N individual ones.
     * @return the devices that were genuinely new on this network.
     */
    suspend fun recordScanResults(
        networkKey: String,
        devices: List<Device>,
        silent: Boolean = false,
    ): List<Device> {
        val existingIds = dao.getIdsForNetwork(networkKey).toSet()
        val isFirstScanOnThisNetwork = existingIds.isEmpty()
        val now = System.currentTimeMillis()
        val seenThisScan = mutableSetOf<String>()
        val newDevices = mutableListOf<Device>()

        devices.forEach { device ->
            val id = identityFor(networkKey, device)
            seenThisScan += id
            val existing = if (id in existingIds) dao.getById(id) else null

            if (existing == null) {
                dao.upsert(
                    DeviceRecord(
                        id = id,
                        networkKey = networkKey,
                        macAddress = device.macAddress,
                        lastKnownIp = device.ipAddress,
                        vendor = device.vendor,
                        hostname = device.hostname,
                        deviceType = device.deviceType.name,
                        customName = null,
                        notes = null,
                        firstSeenAt = now,
                        lastSeenAt = now,
                        isOnline = true,
                    )
                )
                if (!isFirstScanOnThisNetwork) {
                    newDevices += device
                    if (!silent) notifier.notifyNewDevice(device)
                    eventDao.insert(
                        NetworkEvent(
                            networkKey = networkKey,
                            deviceId = id,
                            type = NetworkEvent.NEW_DEVICE,
                            timestamp = now,
                            detail = "${device.vendor ?: device.deviceType.name} joined at ${device.ipAddress}",
                        )
                    )
                }
            } else {
                if (device.macAddress != null && existing.lastKnownIp != device.ipAddress) {
                    eventDao.insert(
                        NetworkEvent(
                            networkKey = networkKey,
                            deviceId = id,
                            type = NetworkEvent.IP_CHANGED,
                            timestamp = now,
                            detail = "${existing.lastKnownIp} -> ${device.ipAddress}",
                        )
                    )
                }
                if (existing.deviceType != device.deviceType.name) {
                    eventDao.insert(
                        NetworkEvent(
                            networkKey = networkKey,
                            deviceId = id,
                            type = NetworkEvent.RECLASSIFIED,
                            timestamp = now,
                            detail = "${existing.deviceType} -> ${device.deviceType.name}",
                        )
                    )
                }
                dao.upsert(
                    existing.copy(
                        lastKnownIp = device.ipAddress,
                        vendor = device.vendor ?: existing.vendor,
                        hostname = device.hostname ?: existing.hostname,
                        deviceType = device.deviceType.name,
                        lastSeenAt = now,
                        isOnline = true,
                    )
                )
            }
        }

        if (!isFirstScanOnThisNetwork) {
            // Only devices seen in more than one prior scan (firstSeenAt != lastSeenAt) count as
            // having an "established presence" -- a device that only ever appeared once via a
            // flaky ARP entry should not immediately generate a WENT_OFFLINE event the moment it
            // does not show up again.
            dao.getOnlineRecords(networkKey)
                .filter { it.id !in seenThisScan && it.firstSeenAt != it.lastSeenAt }
                .forEach { record ->
                    dao.setOnline(record.id, false)
                    eventDao.insert(
                        NetworkEvent(
                            networkKey = networkKey,
                            deviceId = record.id,
                            type = NetworkEvent.WENT_OFFLINE,
                            timestamp = now,
                            detail = "${record.customName ?: record.vendor ?: record.lastKnownIp} not seen in this scan",
                        )
                    )
                }
        }

        return newDevices
    }

    suspend fun setCustomName(id: String, name: String?) = dao.setCustomName(id, name?.takeIf { it.isNotBlank() })
    suspend fun setNotes(id: String, notes: String?) = dao.setNotes(id, notes?.takeIf { it.isNotBlank() })

    companion object {
        /**
         * Scoped identity: network key, then the MAC when known (the one identity that survives a
         * DHCP lease change) else the IP, with the real limitation that implies (see DeviceRecord
         * doc). The "|" separator cannot appear in either half, so scoped ids can never collide
         * with the unscoped ids written before schema v4.
         */
        fun identityFor(networkKey: String, device: Device): String =
            "$networkKey|" + (device.macAddress?.lowercase() ?: "ip:${device.ipAddress}")
    }
}
