package com.greyrecon.app.engine.discovery

import android.content.Context

/**
 * MAC-address-prefix (IEEE OUI) to vendor name lookup. Turns "AA:BB:CC:11:22:33"
 * into "Espressif Inc." (i.e. "this is probably an ESP32-based IoT device") --
 * the same technique Fing's engine/model/catalog does, just backed by the raw
 * public IEEE registry instead of a curated device-type database.
 *
 * Bundled asset (`assets/oui_vendors.csv`) is the real IEEE OUI registry
 * (https://standards-oui.ieee.org/oui/oui.csv), ~40k entries, trimmed to just
 * prefix+vendor -- not a fabricated or sampled subset.
 */
class VendorLookup(private val context: Context) {

    private val vendorsByPrefix: Map<String, String> by lazy { loadAsset() }

    fun lookup(macAddress: String): String? {
        val prefix = macAddress
            .replace(":", "")
            .replace("-", "")
            .uppercase()
            .take(6)
        if (prefix.length != 6) return null
        return vendorsByPrefix[prefix]
    }

    private fun loadAsset(): Map<String, String> {
        val map = HashMap<String, String>(40_000)
        context.assets.open("oui_vendors.csv").bufferedReader().useLines { lines ->
            lines.forEach { line ->
                val commaIndex = line.indexOf(',')
                if (commaIndex != 6) return@forEach // malformed line, prefix must be exactly 6 chars
                val prefix = line.substring(0, commaIndex)
                val vendor = line.substring(commaIndex + 1)
                map[prefix] = vendor
            }
        }
        return map
    }
}
