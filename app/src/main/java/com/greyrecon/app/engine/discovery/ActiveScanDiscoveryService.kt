package com.greyrecon.app.engine.discovery

import com.greyrecon.app.engine.model.Device
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Sweeps every host address in the local subnet with a bounded-concurrency
 * reachability check -- the primary discovery method now that ARP reads are
 * blocked on real hardware (see [DiscoveryMethod] doc). This is the expensive,
 * noisy one, but also the only one guaranteed to run regardless of platform
 * restrictions.
 *
 * Reachability check tries a real TCP connect on a handful of commonly-open
 * ports first -- an in-process raw ICMP socket needs privileges Android apps
 * don't have without root, but the *system* `ping` binary is itself setcap'd
 * with CAP_NET_RAW (confirmed technique via `stealthcopter/AndroidNetworkTools`,
 * MIT), so shelling out to it gets a real ICMP echo without needing the app's
 * own process to hold that capability. Used only as a last-resort third signal
 * for hosts that answered none of the probed TCP ports -- e.g. a host with
 * every one of those specific ports firewalled but ICMP still allowed. Port
 * list also doubles as a classification signal (554/9100/631/3389), so it's
 * wider than the bare minimum needed just to prove a host is alive.
 */
class ActiveScanDiscoveryService(
    private val subnet: SubnetInfo,
    private val maxConcurrent: Int = 16,
    private val connectTimeoutMs: Int = 300,
    private val probePorts: List<Int> = listOf(80, 443, 22, 8080, 445, 139, 554, 631, 9100, 3389, 23, 53),
) : DeviceDiscoveryService {

    override val method = DiscoveryMethod.ACTIVE_SCAN

    override fun discover(): Flow<Device> = channelFlow {
        val hosts = subnet.hostAddresses()
        val semaphore = Semaphore(maxConcurrent)

        coroutineScope {
            hosts.forEach { ip ->
                launch(Dispatchers.IO) {
                    semaphore.withPermit {
                        if (isReachable(ip)) {
                            send(
                                Device(
                                    ipAddress = ip,
                                    macAddress = null, // active scan alone can't see MACs off-subnet-broadcast
                                    hostname = null,
                                    vendor = null,
                                    discoveredBy = setOf(DiscoveryMethod.ACTIVE_SCAN),
                                )
                            )
                        }
                    }
                }
            }
        }
    }

    /**
     * Requires a port to look reachable on TWO independent connect attempts before
     * accepting it -- found via live testing that under this scan's own concurrency
     * (dozens of simultaneous sockets), a single connect attempt can spuriously look
     * "reachable" for addresses that don't actually have anything listening (confirmed
     * by re-probing serially afterward and getting no response at all). A one-off hit
     * doesn't get the benefit of the doubt; a repeatable one does.
     */
    private suspend fun isReachable(ip: String): Boolean = withContext(Dispatchers.IO) {
        for (port in probePorts) {
            if (probeOnce(ip, port) && probeOnce(ip, port)) return@withContext true
        }
        pingReachable(ip)
    }

    private fun probeOnce(ip: String, port: Int): Boolean = try {
        Socket().use { socket -> socket.connect(InetSocketAddress(ip, port), connectTimeoutMs) }
        true // open port: host is definitely alive
    } catch (_: ConnectException) {
        true // fast RST from the target -- something answered, so the host is alive
    } catch (_: Exception) {
        false // timeout / no route to host: no evidence either way
    }

    private fun pingReachable(ip: String): Boolean = try {
        val process = ProcessBuilder("/system/bin/ping", "-c", "1", "-W", "1", ip)
            .redirectErrorStream(true)
            .start()
        val finished = process.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            false
        } else {
            process.exitValue() == 0
        }
    } catch (_: Exception) {
        false
    }
}
