package com.greyrecon.app.engine.scan

import android.content.Context

/**
 * Port+protocol to service name lookup, backed by IANA's real Service Name and
 * Transport Protocol Port Number Registry (https://www.iana.org/assignments/
 * service-names-port-numbers/), not a sampled/fabricated subset -- same sourcing
 * standard as [com.greyrecon.app.engine.discovery.VendorLookup]'s OUI database,
 * and the direct fix for a real competitor-research finding (Network Analyzer
 * Lite bundles a comprehensive offline port database; GreyRecon's old
 * the old `WellKnownPorts` was a ~20-entry hardcoded map).
 *
 * ~11,400 real port/protocol/service-name rows trimmed from the full IANA CSV
 * (Service Name, Port Number, Transport Protocol columns only).
 */
class PortServiceLookup(private val context: Context) {

    private val namesByPortProtocol: Map<String, String> by lazy { loadAsset() }

    fun lookup(port: Int, protocol: String): String? = namesByPortProtocol[key(port, protocol)]

    private fun key(port: Int, protocol: String) = "$port/${protocol.lowercase()}"

    private fun loadAsset(): Map<String, String> {
        val map = HashMap<String, String>(12_000)
        context.assets.open("port_services.csv").bufferedReader().useLines { lines ->
            lines.forEach { line ->
                val parts = line.split(',', limit = 3)
                if (parts.size != 3) return@forEach
                val (port, protocol, name) = parts
                map[key(port.toIntOrNull() ?: return@forEach, protocol)] = name
            }
        }
        return map
    }
}
