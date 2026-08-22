package com.greyrecon.app.engine.discovery

import com.greyrecon.app.engine.model.Device
import com.greyrecon.app.engine.model.DeviceType

/**
 * Infers what kind of device this actually is -- router, camera, printer,
 * mobile, computer, TV/media, smart-home, game console -- the same "what is
 * this thing" job Fing's engine/model/catalog does. Signals are checked in
 * order of reliability, most confident first, and the first match wins:
 *
 * 1. Gateway IP match -- 100% reliable, no guessing needed.
 * 2. mDNS service type -- devices explicitly announce what they are
 *    (_googlecast._tcp, _airplay._tcp, _ipp._tcp, _homekit._tcp, ...).
 * 3. Open port signature -- RTSP (554) is a strong camera tell, printer
 *    protocol ports are unambiguous, RDP means Windows.
 * 4. Vendor keyword -- weakest signal (a NIC vendor doesn't always tell you
 *    the product), used only as a fallback and only for well-known,
 *    single-purpose device brands where the mapping is genuinely reliable.
 *
 * Deliberately conservative: returns UNKNOWN rather than guess when no
 * signal is confident, instead of forcing every device into a category.
 */
object DeviceClassifier {

    fun classify(device: Device): DeviceType {
        if (device.isGateway) return DeviceType.ROUTER

        classifyByMdnsServiceType(device.mdnsServiceTypes)?.let { return it }
        classifyByOpenPorts(device.openPorts.map { it.number }.toSet())?.let { return it }
        classifyByVendor(device.vendor)?.let { return it }

        return DeviceType.UNKNOWN
    }

    private fun classifyByMdnsServiceType(serviceTypes: Set<String>): DeviceType? {
        val lower = serviceTypes.map { it.lowercase() }
        return when {
            lower.any { it.contains("_googlecast") || it.contains("_airplay") || it.contains("_raop") } -> DeviceType.TV_OR_MEDIA
            lower.any { it.contains("_ipp") || it.contains("_printer") || it.contains("_pdl-datastream") } -> DeviceType.PRINTER
            lower.any { it.contains("_homekit") || it.contains("_hap") } -> DeviceType.SMART_HOME
            lower.any { it.contains("_spotify-connect") || it.contains("_sonos") } -> DeviceType.TV_OR_MEDIA
            else -> null
        }
    }

    private fun classifyByOpenPorts(ports: Set<Int>): DeviceType? = when {
        554 in ports -> DeviceType.CAMERA // RTSP -- IP cameras are the overwhelming majority of consumer RTSP servers
        9100 in ports || 631 in ports -> DeviceType.PRINTER // JetDirect / IPP
        3389 in ports -> DeviceType.COMPUTER // RDP -- Windows-specific
        else -> null
    }

    private fun classifyByVendor(vendor: String?): DeviceType? {
        if (vendor == null) return null
        val v = vendor.lowercase()
        return when {
            listOf("hikvision", "dahua", "axis communications", "reolink", "amcrest", "annke").any { v.contains(it) } -> DeviceType.CAMERA
            listOf("hewlett packard", "canon", "seiko epson", "brother industries", "lexmark").any { v.contains(it) } -> DeviceType.PRINTER
            listOf("tp-link", "netgear", "d-link", "asustek", "mikrotik", "ubiquiti networks").any { v.contains(it) } -> DeviceType.ROUTER
            listOf("nintendo", "sony interactive entertainment").any { v.contains(it) } -> DeviceType.GAME_CONSOLE
            listOf("sonos", "bose corporation").any { v.contains(it) } -> DeviceType.TV_OR_MEDIA
            listOf("intel corporate", "dell inc", "lenovo", "micro-star international", "gigabyte technology").any { v.contains(it) } -> DeviceType.COMPUTER
            // Phone/tablet vendors -- can't distinguish phone from tablet by MAC vendor alone, so both map to MOBILE.
            listOf("apple, inc", "samsung electronics", "google, inc", "xiaomi communications", "huawei technologies", "oneplus technology").any { v.contains(it) } -> DeviceType.MOBILE
            else -> null
        }
    }
}
