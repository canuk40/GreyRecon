package com.greyrecon.app.engine.discovery

import android.content.Context

/**
 * A stable fingerprint for "which network am I on", so device history, events and alerts can be
 * scoped per-network instead of pooled into one global list. Without this, scanning home and then
 * an office merges both into a single history and every device at the second site reads as a new
 * device that just joined your network -- which also makes background monitoring unusable, since
 * every change of location would fire a wave of false "unknown device" alerts.
 *
 * Deliberately avoids SSID and BSSID. Both are the obvious choice and both require
 * ACCESS_FINE_LOCATION on Android 8+ (without it `WifiInfo.getBSSID()` returns the placeholder
 * 02:00:00:00:00:00), and the whole point of the current permission work is that a LAN scan should
 * need no runtime permission at all. The default gateway's MAC address is just as unique per
 * network, is already sitting in the kernel's neighbour table, and costs nothing to read.
 *
 * Resolution order, strongest first:
 *
 *  1. `gw:<gateway MAC>` -- the router's physical address. Survives the phone's IP changing, the
 *     subnet being renumbered, and the SSID being renamed.
 *  2. `net:<gateway IP>/<prefix>` -- used when the neighbour table is unreadable, which genuinely
 *     happens: SELinux blocks the netlink query outright on some OEM builds (see
 *     [ArpTableDiscoveryService]). Weaker, because 192.168.1.1/24 describes a large share of the
 *     home routers on earth, so two different networks can collide onto one profile. Accepted as a
 *     degraded mode rather than refusing to record history at all.
 *  3. `null` -- not on a WiFi network. Callers should decline to record rather than invent a key.
 */
data class NetworkIdentity(
    val key: String,
    /** Default label for a freshly-seen network. The user can rename it; this is only the seed. */
    val defaultLabel: String,
    /** True when [key] came from the gateway MAC, i.e. the collision-free path. */
    val isStrong: Boolean,
) {
    companion object {

        fun resolve(context: Context): NetworkIdentity? {
            val subnet = SubnetInfo.fromCurrentConnection(context) ?: return null
            val gatewayIp = subnet.gatewayAddress

            if (gatewayIp != null) {
                val gatewayMac = gatewayMacFor(gatewayIp)
                if (gatewayMac != null) {
                    return NetworkIdentity(
                        key = "gw:${gatewayMac.lowercase()}",
                        defaultLabel = "Network at $gatewayIp",
                        isStrong = true,
                    )
                }
                return NetworkIdentity(
                    key = "net:$gatewayIp/${subnet.prefixLength}",
                    defaultLabel = "Network at $gatewayIp",
                    isStrong = false,
                )
            }

            // No default route (captive portal, oddly configured AP). Fall back to the subnet the
            // phone itself sits on, which is still better than pooling with every other network.
            return NetworkIdentity(
                key = "net:${subnet.localIpAddress}/${subnet.prefixLength}",
                defaultLabel = "Network ${subnet.localIpAddress}/${subnet.prefixLength}",
                isStrong = false,
            )
        }

        /** The gateway's MAC from the kernel neighbour table, or null if it isn't there yet. */
        private fun gatewayMacFor(gatewayIp: String): String? =
            ArpTableDiscoveryService.neighborTable()
                .firstOrNull { (ip, _) -> ip == gatewayIp }
                ?.second
                ?.takeIf { it.isNotBlank() && it != "00:00:00:00:00:00" }
    }
}
