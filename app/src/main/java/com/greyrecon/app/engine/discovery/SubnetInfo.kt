package com.greyrecon.app.engine.discovery

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.NetworkCapabilities

/** The phone's own address + prefix on the current WiFi network. */
data class SubnetInfo(
    val localIpAddress: String,
    val prefixLength: Int,
    /** The default route's gateway IP -- lets classification mark that device as ROUTER with 100% confidence. */
    val gatewayAddress: String?,
) {
    /** All host addresses in this /prefixLength subnet, excluding network/broadcast and our own IP. */
    fun hostAddresses(): List<String> {
        val ipParts = localIpAddress.split(".").map { it.toInt() }
        val ipInt = (ipParts[0] shl 24) or (ipParts[1] shl 16) or (ipParts[2] shl 8) or ipParts[3]

        val hostBits = 32 - prefixLength
        if (hostBits <= 0 || hostBits > 16) return emptyList() // refuse to sweep huge/degenerate ranges

        val networkInt = ipInt and (-1 shl hostBits)
        val broadcastInt = networkInt or ((1 shl hostBits) - 1)

        return ((networkInt + 1) until broadcastInt).mapNotNull { addr ->
            if (addr == ipInt) return@mapNotNull null
            "%d.%d.%d.%d".format(
                (addr shr 24) and 0xFF,
                (addr shr 16) and 0xFF,
                (addr shr 8) and 0xFF,
                addr and 0xFF,
            )
        }
    }

    companion object {
        fun fromCurrentConnection(context: Context): SubnetInfo? {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = cm.activeNetwork ?: return null
            val capabilities = cm.getNetworkCapabilities(network) ?: return null
            if (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return null

            val linkProperties: LinkProperties = cm.getLinkProperties(network) ?: return null
            val linkAddress = linkProperties.linkAddresses.firstOrNull {
                it.address.hostAddress?.contains(":") == false // skip IPv6
            } ?: return null

            val gateway = linkProperties.routes.firstOrNull { it.isDefaultRoute }?.gateway?.hostAddress

            return SubnetInfo(
                localIpAddress = linkAddress.address.hostAddress ?: return null,
                prefixLength = linkAddress.prefixLength,
                gatewayAddress = gateway,
            )
        }
    }
}
