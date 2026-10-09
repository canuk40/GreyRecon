package com.greyrecon.app.engine.discovery

import android.content.Context
import android.net.ConnectivityManager
import com.greyrecon.app.engine.tools.UpnpIgdClient
import java.util.concurrent.ConcurrentHashMap

/**
 * A stable fingerprint for "which network am I on", so device history, events and alerts can be
 * scoped per-network instead of pooled into one global list. Without this, scanning home and then
 * an office merges both into a single history and every device at the second site reads as a new
 * device that just joined -- which also makes background monitoring unusable, since every change
 * of location would fire a wave of false "unknown device" alerts.
 *
 * Deliberately avoids SSID and BSSID. Both are the obvious choice and both require
 * ACCESS_FINE_LOCATION on Android 8+ (without it `WifiInfo.getBSSID()` returns the placeholder
 * 02:00:00:00:00:00), and a LAN scan should need no runtime permission at all.
 *
 * Resolution order, strongest first:
 *
 *  1. `gw:<gateway MAC>` -- the router's physical address, read from the kernel neighbour table.
 *     Free when it works, and it does on older or more permissive builds.
 *  2. `upnp:<router UDN>` -- the gateway's own UPnP unique device name. Needed because path 1
 *     fails on a lot of modern hardware: SELinux blocks the netlink neighbour query and
 *     /proc/net/arp is denied to apps, so the table is simply unreadable. Measured on the primary
 *     test device (Android 15): both denied, every time. A UDN is unique per router, survives
 *     reboots and IP changes, and costs no permission -- it is just an HTTP GET to the gateway.
 *  3. `net:<gateway IP>/<prefix>` -- last resort, when the router has UPnP disabled too. Weak,
 *     because 192.168.1.1/24 describes a large share of the home routers on earth, so two
 *     different networks can collide onto one profile. Surfaced to the user as such rather than
 *     silently trusted.
 *  4. `null` -- not on a WiFi network. Callers should decline to record rather than invent a key.
 *
 * Results are cached against the active [android.net.Network]'s handle. That is the right cache
 * key rather than the gateway IP: the handle is unique to one connection, so it cannot serve a
 * stale answer for a *different* network that happens to use the same address range -- which is
 * precisely the collision tier 3 exists to work around. Switching networks, or even reconnecting
 * to the same AP, issues a new handle and re-resolves.
 */
data class NetworkIdentity(
    val key: String,
    /** Default label for a freshly-seen network. The user can rename it; this is only the seed. */
    val defaultLabel: String,
    /** False only for the gateway-IP fallback, i.e. the tier that can collide between networks. */
    val isStrong: Boolean,
    /**
     * The key tier 3 would have produced for this same network. Carried even on a strong identity
     * so [com.greyrecon.app.history.DeviceHistoryStore] can find and adopt a baseline recorded
     * earlier, back when only the weak key was obtainable. Null when there is no gateway to key on.
     */
    val weakKeyForSameGateway: String?,
) {
    companion object {

        private val cache = ConcurrentHashMap<Long, NetworkIdentity>()

        /**
         * Suspending because tier 2 performs an SSDP round trip and an HTTP GET. Every caller
         * already runs off the main thread; the cache means the cost is paid at most once per
         * network connection.
         */
        suspend fun resolve(context: Context): NetworkIdentity? {
            val subnet = SubnetInfo.fromCurrentConnection(context) ?: return null
            val handle = activeNetworkHandle(context)

            handle?.let { cache[it] }?.let { return it }

            val identity = resolveUncached(context, subnet)
            if (handle != null) cache[handle] = identity
            return identity
        }

        /** Forgets cached identities. Only needed by tests and by a deliberate "rescan" action. */
        fun clearCache() = cache.clear()

        private suspend fun resolveUncached(context: Context, subnet: SubnetInfo): NetworkIdentity {
            val gatewayIp = subnet.gatewayAddress
            val label = gatewayIp?.let { "Network at $it" }
                ?: "Network ${subnet.localIpAddress}/${subnet.prefixLength}"

            val weakKey = if (gatewayIp != null) {
                "net:$gatewayIp/${subnet.prefixLength}"
            } else {
                // No default route (captive portal, oddly configured AP). The phone's own subnet
                // is still better than pooling with every other network.
                "net:${subnet.localIpAddress}/${subnet.prefixLength}"
            }

            if (gatewayIp != null) {
                gatewayMacFor(gatewayIp)?.let { mac ->
                    return NetworkIdentity("gw:${mac.lowercase()}", label, isStrong = true, weakKeyForSameGateway = weakKey)
                }
            }

            UpnpIgdClient(context).routerUdn()?.let { udn ->
                return NetworkIdentity("upnp:$udn", label, isStrong = true, weakKeyForSameGateway = weakKey)
            }

            return NetworkIdentity(weakKey, label, isStrong = false, weakKeyForSameGateway = weakKey)
        }

        private fun activeNetworkHandle(context: Context): Long? = runCatching {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            cm.activeNetwork?.networkHandle
        }.getOrNull()

        /** The gateway's MAC from the kernel neighbour table, or null if it is unreadable. */
        private fun gatewayMacFor(gatewayIp: String): String? =
            ArpTableDiscoveryService.neighborTable()
                .firstOrNull { (ip, _) -> ip == gatewayIp }
                ?.second
                ?.takeIf { it.isNotBlank() && it != "00:00:00:00:00:00" }
    }
}
