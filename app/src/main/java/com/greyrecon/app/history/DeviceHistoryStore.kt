package com.greyrecon.app.history

import android.content.Context
import com.greyrecon.app.engine.model.Device
import kotlinx.coroutines.flow.Flow

/**
 * Shared by both ScanViewModel (UI scans) and the MCP server's ScanDataSource
 * (nanobot-triggered scans) so a device's history is the same regardless of
 * which surface found it. Owns the "is this device genuinely new" logic and
 * the notification that follows from it.
 */
class DeviceHistoryStore(context: Context) {

    private val dao = GreyReconDatabase.get(context).deviceHistoryDao()
    private val eventDao = GreyReconDatabase.get(context).networkEventDao()
    private val notifier = NewDeviceNotifier(context)

    val history: Flow<List<DeviceRecord>> = dao.observeAll()
    val events: Flow<List<NetworkEvent>> = eventDao.observeAll()

    /**
     * Records every device from a completed scan, diffing against what was already known so real
     * changes -- not just current state -- get surfaced as [NetworkEvent]s: new devices, devices
     * with an established presence that dropped off this scan, IP changes on a MAC-identified
     * device (DHCP renewal, or something new answering for a known MAC), and reclassification.
     * Notifies for genuinely new devices -- unless this is the very first scan ever run (checked
     * once, up front, so a fresh install doesn't fire N notifications for every device already on
     * the network).
     */
    suspend fun recordScanResults(devices: List<Device>) {
        val existingIds = dao.getAllIds().toSet()
        val isFirstEverScan = existingIds.isEmpty()
        val now = System.currentTimeMillis()
        val seenThisScan = mutableSetOf<String>()

        devices.forEach { device ->
            val id = identityFor(device)
            seenThisScan += id
            val existing = if (id in existingIds) dao.getById(id) else null

            if (existing == null) {
                dao.upsert(
                    DeviceRecord(
                        id = id,
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
                if (!isFirstEverScan) {
                    notifier.notifyNewDevice(device)
                    eventDao.insert(
                        NetworkEvent(
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

        if (!isFirstEverScan) {
            // Only devices seen in more than one prior scan (firstSeenAt != lastSeenAt) count as
            // having an "established presence" -- a device that only ever appeared once via a
            // flaky ARP entry shouldn't immediately generate a WENT_OFFLINE event the moment it
            // doesn't show up again.
            dao.getOnlineRecords()
                .filter { it.id !in seenThisScan && it.firstSeenAt != it.lastSeenAt }
                .forEach { record ->
                    dao.setOnline(record.id, false)
                    eventDao.insert(
                        NetworkEvent(
                            deviceId = record.id,
                            type = NetworkEvent.WENT_OFFLINE,
                            timestamp = now,
                            detail = "${record.customName ?: record.vendor ?: record.lastKnownIp} not seen in this scan",
                        )
                    )
                }
        }
    }

    suspend fun setCustomName(id: String, name: String?) = dao.setCustomName(id, name?.takeIf { it.isNotBlank() })
    suspend fun setNotes(id: String, notes: String?) = dao.setNotes(id, notes?.takeIf { it.isNotBlank() })

    companion object {
        /** MAC when known (the one identity that survives a DHCP lease change) -- else the IP, with the real limitation that implies (see DeviceRecord doc). */
        fun identityFor(device: Device): String = device.macAddress?.lowercase() ?: "ip:${device.ipAddress}"
    }
}
