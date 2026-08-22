package com.greyrecon.app.engine.discovery

import com.greyrecon.app.engine.model.Device
import kotlinx.coroutines.flow.Flow

/**
 * How a device was found. ARP_TABLE and mDNS/UPnP are passive; ACTIVE_SCAN is
 * the only one that generates real traffic per host.
 *
 * ARP_TABLE was originally implemented by reading /proc/net/arp as a text
 * file, then removed after confirming on real hardware that regular apps get
 * "Permission denied" reading it -- Android's post-API-29 SELinux hardening
 * blocks that whole proc-net file family for non-privileged apps. Restored
 * via a native netlink route-socket query instead (see NativeArpScanner and
 * app/src/main/cpp/ipneigh.cpp) -- a different SELinux permission class,
 * commonly allowed for ordinary apps.
 */
enum class DiscoveryMethod {
    ARP_TABLE,
    MDNS,
    UPNP,
    ACTIVE_SCAN,
}

/**
 * One discovery strategy. Each implementation owns exactly one method from
 * [DiscoveryMethod] -- the engine layer merges results from all of them into
 * a single deduplicated device list, keyed by IP/MAC.
 */
interface DeviceDiscoveryService {
    val method: DiscoveryMethod

    /** Emits devices as they're found; does not complete until the scan window ends. */
    fun discover(): Flow<Device>
}
