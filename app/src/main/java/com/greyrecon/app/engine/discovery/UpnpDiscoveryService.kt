package com.greyrecon.app.engine.discovery

import android.content.Context
import android.net.wifi.WifiManager
import com.greyrecon.app.engine.model.Device
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.MulticastSocket

/**
 * Finds devices that advertise themselves via UPnP/SSDP (routers, smart TVs,
 * media servers, some IoT gear) -- RoboShadow's third discovery protocol,
 * built there on the mm2d library. Implemented here as raw SSDP instead of
 * pulling in a full UPnP library: for discovery (not control), SSDP alone is
 * enough -- it's just an M-SEARCH multicast query and parsing the response
 * headers, no need for the full UPnP device-description/control-point stack.
 *
 * Self-bounded the same way MdnsDiscoveryService is (see that file for why):
 * SSDP responses trickle in over a listen window, there's no "all devices have
 * now responded" signal to wait for.
 */
class UpnpDiscoveryService(
    private val context: Context,
    private val listenWindowMs: Int = 4_000,
) : DeviceDiscoveryService {

    override val method = DiscoveryMethod.UPNP

    // flowOn(IO) below, not an inner withContext(IO) around the emit() calls -- flow's own context-
    // preservation check forbids emitting from a context other than the one the flow is collected
    // in, and throws IllegalStateException the first time emit() actually runs inside a withContext
    // block. Confirmed live: this crashed for real, but only on a network with an actual UPnP
    // responder (never on a network where every M-SEARCH just timed out with zero replies) -- the
    // buggy path only executes once emit() is reached at all. flowOn() is the correct way to move a
    // flow's upstream work onto a different dispatcher while keeping plain emit() calls valid.
    override fun discover(): Flow<Device> = flow {
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val multicastLock = wifiManager.createMulticastLock("greyrecon-ssdp")
        multicastLock.acquire()

        try {
            MulticastSocket().use { socket ->
                socket.soTimeout = listenWindowMs
                val groupAddress = InetAddress.getByName("239.255.255.250")

                val searchRequest = buildString {
                    append("M-SEARCH * HTTP/1.1\r\n")
                    append("HOST: 239.255.255.250:1900\r\n")
                    append("MAN: \"ssdp:discover\"\r\n")
                    append("MX: 3\r\n")
                    append("ST: ssdp:all\r\n")
                    append("\r\n")
                }.toByteArray()

                socket.send(DatagramPacket(searchRequest, searchRequest.size, groupAddress, 1900))

                val deadline = System.currentTimeMillis() + listenWindowMs
                val buffer = ByteArray(2048)

                while (System.currentTimeMillis() < deadline) {
                    try {
                        val packet = DatagramPacket(buffer, buffer.size)
                        socket.receive(packet) // throws SocketTimeoutException once soTimeout elapses
                        val response = String(packet.data, 0, packet.length)
                        parseSsdpResponse(response, packet.address.hostAddress)?.let { emit(it) }
                    } catch (_: java.net.SocketTimeoutException) {
                        break // listen window elapsed, natural completion
                    }
                }
            }
        } finally {
            multicastLock.release()
        }
    }.flowOn(Dispatchers.IO)

    private fun parseSsdpResponse(response: String, sourceIp: String?): Device? {
        if (sourceIp == null) return null
        val headers = response.lines()
            .mapNotNull { line ->
                val colonIndex = line.indexOf(':')
                if (colonIndex <= 0) return@mapNotNull null
                line.substring(0, colonIndex).trim().uppercase() to line.substring(colonIndex + 1).trim()
            }
            .toMap()

        val server = headers["SERVER"]
        val usn = headers["USN"]

        return Device(
            ipAddress = sourceIp,
            macAddress = null,
            hostname = server ?: usn, // best-effort identifier; SSDP doesn't give a real hostname
            vendor = null,
            discoveredBy = setOf(DiscoveryMethod.UPNP),
        )
    }
}
