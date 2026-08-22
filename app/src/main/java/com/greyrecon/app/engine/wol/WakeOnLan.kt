package com.greyrecon.app.engine.wol

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * Sends a Wake-on-LAN magic packet: 6 bytes of 0xFF followed by the target MAC repeated 16 times,
 * broadcast over UDP -- sent [repeatCount] times, not once. WoL is inherently unreliable over an
 * unacknowledged UDP broadcast (the target NIC may miss any single packet while still in a low-power
 * state); real-world tools resend by default for exactly this reason -- confirmed via
 * `stealthcopter/AndroidNetworkTools`'s `WakeOnLan.java`, which defaults to 5 sends.
 */
object WakeOnLan {

    suspend fun send(
        macAddress: String,
        broadcastAddress: String = "255.255.255.255",
        port: Int = 9,
        repeatCount: Int = 5,
    ): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val macBytes = macAddress.split(Regex("[:\\-]")).map { it.toInt(16).toByte() }
                require(macBytes.size == 6) { "Invalid MAC address: $macAddress" }

                val packet = ByteArray(6 + 16 * 6)
                for (i in 0 until 6) packet[i] = 0xFF.toByte()
                for (i in 0 until 16) {
                    for (j in 0 until 6) packet[6 + i * 6 + j] = macBytes[j]
                }

                repeat(repeatCount) {
                    DatagramSocket().use { socket ->
                        socket.broadcast = true
                        socket.send(DatagramPacket(packet, packet.size, InetAddress.getByName(broadcastAddress), port))
                    }
                }
            }
        }
}
