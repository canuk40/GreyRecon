package com.greyrecon.app.engine.discovery

import com.greyrecon.app.engine.model.Device
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch

/**
 * Runs every registered [DeviceDiscoveryService] concurrently and merges their
 * results into a single deduplicated stream, keyed by IP address. A device
 * found by multiple methods gets its [Device.discoveredBy] set merged rather
 * than appearing twice -- e.g. a device ARP already found that later responds
 * to mDNS gets its hostname filled in, not a duplicate row.
 *
 * Order matters for cost, not correctness: register cheap/passive methods
 * (ARP, mDNS, UPnP) before the expensive one (active scan) so the UI shows
 * results fast, but every method still runs concurrently regardless of order.
 *
 * Every [DeviceDiscoveryService] is responsible for completing its own flow --
 * ARP finishes as soon as the native query returns, the active scan finishes
 * when the subnet sweep completes, and mDNS self-bounds with an internal
 * listen window (see
 * [MdnsDiscoveryService]) since the protocol itself has no natural end. This
 * engine deliberately does NOT impose a blanket timeout of its own: a uniform
 * cutoff would risk truncating the active scan mid-sweep on a large subnet
 * just to bound an unrelated service.
 */
class DiscoveryEngine(private val services: List<DeviceDiscoveryService>) {

    fun discover(): Flow<Device> = channelFlow {
        val merged = LinkedHashMap<String, Device>() // IP -> merged device, insertion order preserved for UI stability

        services.forEach { service ->
            launch {
                service.discover().collect { found ->
                    val existing = merged[found.ipAddress]
                    val mergedDevice = if (existing == null) {
                        found
                    } else {
                        existing.copy(
                            macAddress = existing.macAddress ?: found.macAddress,
                            hostname = existing.hostname ?: found.hostname,
                            vendor = existing.vendor ?: found.vendor,
                            discoveredBy = existing.discoveredBy + found.discoveredBy,
                            mdnsServiceTypes = existing.mdnsServiceTypes + found.mdnsServiceTypes,
                        )
                    }
                    merged[found.ipAddress] = mergedDevice
                    send(mergedDevice)
                }
            }
        }
    }
}
