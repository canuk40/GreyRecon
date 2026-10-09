package com.greyrecon.app.engine.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import com.greyrecon.app.engine.model.Device
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch

/**
 * Finds devices advertising themselves over mDNS/DNS-SD (printers, Chromecasts,
 * smart-home gear, AirPlay, etc.) via Android's built-in NsdManager -- no need
 * to hand-roll multicast socket handling the way RoboShadow's DiscoverDnsSdService
 * does natively; NsdManager wraps the same protocol properly.
 *
 * Queries a fixed list of concrete service types rather than the DNS-SD meta-query.
 *
 * This used to pass "_services._dns-sd._udp." -- the "what service types exist at all" query,
 * which is the right question to ask in DNS-SD and the wrong one to ask NsdManager.
 * NsdManager does not implement the meta-query; it took the string literally and registered a
 * listener for a service type named `_dns-sd._udp`, which nothing on earth advertises. Confirmed
 * on a real device: on a network with 46 live hosts, logcat showed
 * `Registering listener for serviceType: _dns-sd._udp.local` followed eight seconds later by
 * `Unregistering`, and not one service was ever found.
 *
 * So mDNS discovery had silently returned nothing for every scan -- which also meant
 * [DeviceClassifier]'s strongest signal, the mDNS service type, never once fired, and
 * [Device.mdnsServiceTypes] was always empty. Enumerating concrete types is what every other
 * Android scanner does, and is the only thing NsdManager actually supports.
 *
 * Self-bounded by [listenWindowMs]: mDNS has no natural "done" signal (devices
 * can announce themselves at any time), so left unbounded this flow would keep
 * DiscoveryEngine's combined scan "in progress" forever. Bug found by actually
 * running a scan on a real network -- the UI spinner never stopped even though
 * ARP + active scan had both long since finished.
 */
class MdnsDiscoveryService(
    private val context: Context,
    private val serviceTypes: List<String> = DEFAULT_SERVICE_TYPES,
    private val listenWindowMs: Long = 8_000,
) : DeviceDiscoveryService {

    override val method = DiscoveryMethod.MDNS

    override fun discover(): Flow<Device> = callbackFlow {
        val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager

        fun newListener() = object : NsdManager.DiscoveryListener {
            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                nsdManager.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) { /* skip unresolvable entries */ }

                    override fun onServiceResolved(info: NsdServiceInfo) {
                        val ip = info.host?.hostAddress ?: return
                        trySend(
                            Device(
                                ipAddress = ip,
                                macAddress = null,
                                hostname = info.serviceName,
                                vendor = null,
                                discoveredBy = setOf(DiscoveryMethod.MDNS),
                                mdnsServiceTypes = setOfNotNull(info.serviceType),
                                // The TXT record came back with this resolve and was being
                                // discarded; it usually carries the actual model and firmware.
                                modelInfo = MdnsTxtFacts.describe(info.attributes),
                            )
                        )
                    }
                })
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {}
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            // Deliberately does NOT close the flow. With many listeners running, one
            // unsupported or rate-limited service type must not abort the other twenty.
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
        }

        // One listener per type, all running inside the same listen window. Registering several
        // is cheap -- each is a multicast query -- and a single shared window keeps the scan's
        // overall duration unchanged.
        val listeners = serviceTypes.map { type ->
            val listener = newListener()
            runCatching { nsdManager.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, listener) }
                .map { listener }
                .getOrNull()
        }

        launch {
            delay(listenWindowMs)
            close() // natural completion after the listen window -- not an error
        }

        awaitClose {
            listeners.filterNotNull().forEach { runCatching { nsdManager.stopServiceDiscovery(it) } }
        }
    }

    companion object {
        /**
         * The service types worth asking for on a home or small-office network: the ones that
         * identify a device class (cast/AirPlay/print/HomeKit) or carry a useful TXT record.
         */
        val DEFAULT_SERVICE_TYPES = listOf(
            "_googlecast._tcp.",
            "_airplay._tcp.",
            "_raop._tcp.",
            "_spotify-connect._tcp.",
            "_sonos._tcp.",
            "_hap._tcp.",
            "_homekit._tcp.",
            "_matter._tcp.",
            "_ipp._tcp.",
            "_ipps._tcp.",
            "_printer._tcp.",
            "_pdl-datastream._tcp.",
            "_scanner._tcp.",
            "_http._tcp.",
            "_https._tcp.",
            "_workstation._tcp.",
            "_smb._tcp.",
            "_afpovertcp._tcp.",
            "_ssh._tcp.",
            "_sftp-ssh._tcp.",
            "_rfb._tcp.",
            "_daap._tcp.",
            "_device-info._tcp.",
        )
    }
}
