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
    /**
     * True when [macAddress] has the locally-administered bit set, i.e. it is a randomised
     * privacy address rather than a real burned-in one. Every current phone and laptop randomises
     * per network by default, so these are common -- and because a randomised MAC has no OUI, the
     * vendor lookup returns nothing and the device would otherwise show up as an anonymous blank
     * row. Saying "randomised MAC" is both more accurate and more useful than saying nothing.
     */
    val hasRandomizedMac: Boolean = false,
    /** Model/firmware details parsed out of mDNS TXT records, e.g. "Philips hue bridge 2.1". */
    val modelInfo: String? = null,
    /** Server header or page title from an open HTTP(S) port, e.g. "nginx/1.24.0" or "Synology DSM". */
    val httpBanner: String? = null,
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
