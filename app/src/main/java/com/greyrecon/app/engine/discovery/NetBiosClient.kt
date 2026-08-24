package com.greyrecon.app.engine.discovery

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

data class NetBiosInfo(
    val hostname: String?,
    val workgroup: String?,
    val macAddress: String?,
    val allNames: List<String>,
)

/**
 * NetBIOS node-status query over UDP 137 (RFC 1002) -- recovers a Windows/SMB host's NetBIOS name,
 * workgroup/domain, and (crucially) its MAC address, for devices that don't announce via mDNS or
 * UPnP. Both Fing and Port Authority do this. It matters more now that targetSdk 36 pushed the app
 * back into the strict SELinux domain where the native netlink ARP query is blocked (see GreyRecon.md)
 * -- NetBIOS is a keyless way to recover a device name *and* its MAC (hence vendor) that the fallback
 * discovery paths otherwise miss.
 *
 * Hand-rolled request/parse over a plain DatagramSocket, same "narrow need, no new library" approach
 * as SnmpClient. Sends the standard "*" wildcard NBSTAT query and reads the name list + adapter
 * address out of the reply.
 */
object NetBiosClient {

    private const val NETBIOS_PORT = 137
    private const val TIMEOUT_MS = 1500

    suspend fun query(ipAddress: String): Result<NetBiosInfo> = withContext(Dispatchers.IO) {
        try {
            DatagramSocket().use { socket ->
                socket.soTimeout = TIMEOUT_MS
                val request = buildNodeStatusRequest()
                socket.send(DatagramPacket(request, request.size, InetAddress.getByName(ipAddress), NETBIOS_PORT))

                val buffer = ByteArray(1024)
                val response = DatagramPacket(buffer, buffer.size)
                socket.receive(response)

                parseNodeStatus(buffer.copyOf(response.length))
                    ?.let { Result.success(it) }
                    ?: Result.failure(IOException("Malformed NetBIOS response from $ipAddress"))
            }
        } catch (e: Exception) {
            Result.failure(IOException("No NetBIOS response from $ipAddress (device may not run NetBIOS/SMB)", e))
        }
    }

    /** Standard NBSTAT query for the "*" wildcard name -- asks a host to list all its NetBIOS names + adapter status. */
    private fun buildNodeStatusRequest(): ByteArray {
        val out = ArrayList<Byte>(50)
        // Header: transaction id, flags=0x0000, QDCOUNT=1, others 0.
        out.add(0x13); out.add(0x37)
        out.add(0x00); out.add(0x00)
        out.add(0x00); out.add(0x01)
        out.add(0x00); out.add(0x00)
        out.add(0x00); out.add(0x00)
        out.add(0x00); out.add(0x00)
        // Question name: "*" (0x2A + 15 nulls) first-level encoded to 32 bytes, len-prefixed, null-terminated.
        val name = ByteArray(16).also { it[0] = 0x2A }
        out.add(0x20) // length of the encoded label (32)
        for (b in name) {
            val v = b.toInt() and 0xFF
            out.add(((v shr 4) + 0x41).toByte())
            out.add(((v and 0x0F) + 0x41).toByte())
        }
        out.add(0x00) // root label
        // QTYPE = NBSTAT (0x0021), QCLASS = IN (0x0001)
        out.add(0x00); out.add(0x21)
        out.add(0x00); out.add(0x01)
        return out.toByteArray()
    }

    private fun parseNodeStatus(resp: ByteArray): NetBiosInfo? {
        if (resp.size < 12) return null
        var pos = 12 // skip header

        // Skip the answer RR's name (labels until a 0x00 byte, or a compression pointer).
        while (pos < resp.size) {
            val len = resp[pos].toInt() and 0xFF
            if (len == 0) { pos += 1; break }
            if (len and 0xC0 == 0xC0) { pos += 2; break } // compression pointer
            pos += 1 + len
        }
        pos += 2 + 2 + 4 + 2 // TYPE + CLASS + TTL + RDLENGTH
        if (pos >= resp.size) return null

        val numNames = resp[pos].toInt() and 0xFF
        pos += 1

        val names = mutableListOf<String>()
        var hostname: String? = null
        var workgroup: String? = null
        repeat(numNames) {
            if (pos + 18 > resp.size) return@repeat
            val rawName = String(resp, pos, 15, Charsets.US_ASCII).trim()
            val suffix = resp[pos + 15].toInt() and 0xFF
            val flags = ((resp[pos + 16].toInt() and 0xFF) shl 8) or (resp[pos + 17].toInt() and 0xFF)
            val isGroup = flags and 0x8000 != 0
            if (rawName.isNotBlank()) {
                names.add("$rawName<0x${"%02X".format(suffix)}>${if (isGroup) " (group)" else ""}")
                // suffix 0x00 unique = the machine's own workstation name; group = the workgroup/domain.
                if (suffix == 0x00) {
                    if (isGroup && workgroup == null) workgroup = rawName
                    else if (!isGroup && hostname == null) hostname = rawName
                }
            }
            pos += 18
        }

        // The adapter (MAC) address is the 6 bytes immediately after the name list.
        val mac = if (pos + 6 <= resp.size) {
            (0 until 6).joinToString(":") { "%02X".format(resp[pos + it].toInt() and 0xFF) }
                .takeUnless { it == "00:00:00:00:00:00" }
        } else null

        return NetBiosInfo(hostname = hostname, workgroup = workgroup, macAddress = mac, allNames = names)
    }
}
