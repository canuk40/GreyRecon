package com.greyrecon.app.engine.tools

data class SubnetCalculation(
    val networkAddress: String,
    val broadcastAddress: String,
    val firstUsable: String,
    val lastUsable: String,
    val usableHostCount: Long,
    val netmask: String,
    val prefixLength: Int,
)

object SubnetCalculator {

    fun calculate(ipAddress: String, prefixLength: Int): Result<SubnetCalculation> = runCatching {
        require(prefixLength in 0..32) { "Prefix length must be between 0 and 32" }
        val ipInt = ipToInt(ipAddress)
        val hostBits = 32 - prefixLength
        val mask = if (hostBits == 32) 0 else (-1 shl hostBits)
        val networkInt = ipInt and mask
        val broadcastInt = networkInt or mask.inv()
        val usableCount = if (hostBits <= 1) 0L else (1L shl hostBits) - 2

        SubnetCalculation(
            networkAddress = intToIp(networkInt),
            broadcastAddress = intToIp(broadcastInt),
            firstUsable = if (usableCount > 0) intToIp(networkInt + 1) else intToIp(networkInt),
            lastUsable = if (usableCount > 0) intToIp(broadcastInt - 1) else intToIp(broadcastInt),
            usableHostCount = usableCount,
            netmask = intToIp(mask),
            prefixLength = prefixLength,
        )
    }

    private fun ipToInt(ip: String): Int {
        val parts = ip.trim().split(".").map { it.toInt() }
        require(parts.size == 4 && parts.all { it in 0..255 }) { "Invalid IPv4 address: $ip" }
        return (parts[0] shl 24) or (parts[1] shl 16) or (parts[2] shl 8) or parts[3]
    }

    private fun intToIp(value: Int): String =
        "%d.%d.%d.%d".format((value shr 24) and 0xFF, (value shr 16) and 0xFF, (value shr 8) and 0xFF, value and 0xFF)
}
