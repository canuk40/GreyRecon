package com.greyrecon.app.engine.discovery

import com.greyrecon.app.engine.model.Device
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/**
 * Reads the kernel's ARP/neighbor table via [NativeArpScanner]'s native
 * netlink query -- near-instant, zero network traffic, since the OS already
 * knows which devices it's recently talked to. Covers both IPv4 (ARP) and
 * IPv6 (RFC 4861 Neighbor Discovery Protocol) entries as of SESSION 22 --
 * the native query used to request `AF_INET` only; `DiscoveryMethod.ARP_TABLE`
 * keeps its name (not renamed to avoid touching every call site for a label),
 * but a `Device` surfaced by this method may now carry an IPv6 address.
 *
 * Confirmed via live device testing that this is blocked by SELinux on at
 * least one real Android build (sendmsg() on the netlink socket returns
 * EPERM), the same way /proc/net/arp file reads are blocked -- but kept in
 * place since that policy varies by OEM/Android version and may not block
 * it everywhere. Degrades gracefully to zero results, same as before.
 *
 * Falls back to shelling out to the system `ip neigh show` binary if the
 * native query comes back empty. A forked `ip` process runs under its own
 * SELinux transition, potentially a different domain than the app's own
 * netlink socket call -- not guaranteed to bypass the same block, but cheap
 * to try as a last resort (confirmed as a real technique used by
 * `stealthcopter/AndroidNetworkTools`, MIT).
 */
class ArpTableDiscoveryService : DeviceDiscoveryService {

    override val method = DiscoveryMethod.ARP_TABLE

    override fun discover(): Flow<Device> = flow {
        val nativeResult = NativeArpScanner.queryNeighborTable()
        val entries = parseNativeOutput(nativeResult).ifEmpty { queryViaIpNeighShow() }
        entries.forEach { (ip, mac) ->
            emit(
                Device(
                    ipAddress = ip,
                    macAddress = mac,
                    hostname = null,
                    vendor = null,
                    discoveredBy = setOf(DiscoveryMethod.ARP_TABLE),
                )
            )
        }
    }.flowOn(Dispatchers.IO)

    private fun parseNativeOutput(raw: String): List<Pair<String, String>> =
        raw.lineSequence()
            .filter { it.isNotBlank() }
            .mapNotNull { line ->
                val parts = line.split(",")
                if (parts.size == 2) parts[0] to parts[1] else null
            }
            .toList()

    /** Parses `ip neigh show` lines shaped like "192.168.1.1 dev wlan0 lladdr aa:bb:cc:dd:ee:ff STALE". */
    private fun queryViaIpNeighShow(): List<Pair<String, String>> {
        return try {
            val process = ProcessBuilder("ip", "neigh", "show").redirectErrorStream(true).start()
            val finished = process.waitFor(2, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                return emptyList()
            }
            BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                reader.lineSequence()
                    .mapNotNull { line ->
                        val tokens = line.trim().split(Regex("\\s+"))
                        val ip = tokens.getOrNull(0) ?: return@mapNotNull null
                        val lladdrIndex = tokens.indexOf("lladdr")
                        val mac = if (lladdrIndex >= 0) tokens.getOrNull(lladdrIndex + 1) else null
                        if (ip.isNotBlank() && mac != null) ip to mac else null
                    }
                    .toList()
            }
        } catch (_: Exception) {
            emptyList()
        }
    }
}
