package com.greyrecon.app.engine.model

import com.greyrecon.app.engine.discovery.DiscoveryMethod

/**
 * A device discovered on the local network, merged from whichever discovery
 * method(s) found it (ARP table, active scan, mDNS/DNS-SD, UPnP/SSDP).
 */
data class Device(
    val ipAddress: String,
    val macAddress: String?,
    val hostname: String?,
    val vendor: String?,
    val discoveredBy: Set<DiscoveryMethod>,
    val openPorts: List<Port> = emptyList(),
    val shodanFindings: List<ShodanFinding> = emptyList(),
    /** Raw mDNS service type strings this device advertised, e.g. "_googlecast._tcp" -- the strongest single signal for classifying smart-home/media devices, kept around for DeviceClassifier. */
    val mdnsServiceTypes: Set<String> = emptySet(),
    /** True if this IP matches the WiFi network's default gateway -- a 100% reliable "this is the router" signal, no heuristics needed. */
    val isGateway: Boolean = false,
    val deviceType: DeviceType = DeviceType.UNKNOWN,
)

enum class DeviceType {
    ROUTER,
    CAMERA,
    PRINTER,
    MOBILE,
    COMPUTER,
    TV_OR_MEDIA,
    SMART_HOME,
    GAME_CONSOLE,
    UNKNOWN,
}

data class Port(
    val number: Int,
    val protocol: String,
    val serviceName: String?,
)

/** A vulnerability/exposure finding pulled from Shodan for this device's IP (Pro tier). */
data class ShodanFinding(
    val cveId: String?,
    val summary: String,
    val severity: String?,
)
