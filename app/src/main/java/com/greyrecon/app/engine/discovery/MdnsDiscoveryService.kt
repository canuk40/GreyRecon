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
 * [serviceType] defaults to "_services._dns-sd._udp" -- the meta-query that
 * asks "what service types exist on this network at all", which is broader
 * than querying one specific service type at a time.
 *
 * Self-bounded by [listenWindowMs]: mDNS has no natural "done" signal (devices
 * can announce themselves at any time), so left unbounded this flow would keep
 * DiscoveryEngine's combined scan "in progress" forever. Bug found by actually
 * running a scan on a real network -- the UI spinner never stopped even though
 * ARP + active scan had both long since finished.
 */
class MdnsDiscoveryService(
    private val context: Context,
    private val serviceType: String = "_services._dns-sd._udp.",
    private val listenWindowMs: Long = 8_000,
) : DeviceDiscoveryService {

    override val method = DiscoveryMethod.MDNS

    override fun discover(): Flow<Device> = callbackFlow {
        val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager

        val listener = object : NsdManager.DiscoveryListener {
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
                            )
                        )
                    }
                })
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {}
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) { close() }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
        }

        nsdManager.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, listener)

        launch {
            delay(listenWindowMs)
            close() // natural completion after the listen window -- not an error
        }

        awaitClose { nsdManager.stopServiceDiscovery(listener) }
    }
}
