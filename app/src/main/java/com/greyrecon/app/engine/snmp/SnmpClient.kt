package com.greyrecon.app.engine.snmp

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

data class SnmpInfo(
    val sysName: String?,
    val sysDescr: String?,
)

/**
 * Hand-rolled SNMP v2c GET client -- no third-party SNMP library, since the actual need (read two
 * well-known OIDs) is narrow enough that a minimal BER/ASN.1 encoder is simpler and lighter than a
 * general-purpose SNMP dependency. Same "boring, no new library unless truly needed" bias as the
 * rest of this codebase.
 *
 * Real discovery gap this closes: ARP/mDNS/UPnP/active-TCP-probe all miss devices that don't
 * broadcast on those specific protocols but do answer SNMP -- managed switches, network printers,
 * and some IoT gear commonly fall in that category. "public" is the de facto default read-only
 * community string; a network that changed it simply won't respond here, which degrades to "no
 * SNMP data" rather than a crash -- this is an opportunistic identification aid, not a guaranteed one.
 *
 * Scoped as an on-demand per-device action (mirrors Check Shodan/NVD/Security), not folded into the
 * automatic full-network scan -- querying an unresponsive device costs a full socket-timeout wait,
 * and most devices on a given network won't answer SNMP at all, so baking this into every scan would
 * add real latency for little gain across most of a typical device list.
 */
object SnmpClient {

    private const val SYS_DESCR_OID = "1.3.6.1.2.1.1.1.0"
    private const val SYS_NAME_OID = "1.3.6.1.2.1.1.5.0"
    private const val SNMP_PORT = 161
    private const val TIMEOUT_MS = 1500

    /** Standard ifDescr column (RFC 1213 `ifTable`) -- the most broadly useful single subtree to walk on unknown devices. */
    const val IF_DESCR_OID = "1.3.6.1.2.1.2.2.1.2"

    /**
     * Tries each candidate community string against [ipAddress] until one gets a real response,
     * for the explicit "device didn't answer 'public'" case -- an on-demand pentest action, not
     * something the automatic scan ever does (see [SnmpCommunityWordlist]). Runs in bounded-size
     * concurrent batches rather than one string at a time: a full miss on every candidate would
     * otherwise cost `candidates.size * TIMEOUT_MS` serially (~3 minutes for the bundled 118-entry
     * list) versus roughly `(candidates.size / batchSize) * TIMEOUT_MS` batched (~12 seconds).
     */
    suspend fun bruteForceCommunity(
        ipAddress: String,
        candidates: List<String>,
        batchSize: Int = 16,
    ): Result<Pair<String, SnmpInfo>> = withContext(Dispatchers.IO) {
        candidates.chunked(batchSize).forEach { batch ->
            val results = batch.map { community -> async { community to query(ipAddress, community) } }.awaitAll()
            results.firstOrNull { (_, result) -> result.isSuccess }?.let { (community, result) ->
                return@withContext Result.success(community to result.getOrThrow())
            }
        }
        Result.failure(IOException("No common community string worked for $ipAddress (tried ${candidates.size})"))
    }

    suspend fun query(ipAddress: String, community: String = "public"): Result<SnmpInfo> = withContext(Dispatchers.IO) {
        try {
            DatagramSocket().use { socket ->
                socket.soTimeout = TIMEOUT_MS
                val requestId = (0 until Int.MAX_VALUE).random()
                val request = buildGetRequest(community, requestId, listOf(SYS_DESCR_OID, SYS_NAME_OID))
                socket.send(DatagramPacket(request, request.size, InetAddress.getByName(ipAddress), SNMP_PORT))

                val buffer = ByteArray(1500)
                val responsePacket = DatagramPacket(buffer, buffer.size)
                socket.receive(responsePacket)

                val values = parseGetResponse(buffer.copyOf(responsePacket.length))
                Result.success(SnmpInfo(sysName = values[SYS_NAME_OID], sysDescr = values[SYS_DESCR_OID]))
            }
        } catch (e: Exception) {
            Result.failure(IOException("No SNMP response from $ipAddress (device may not run SNMP, or uses a non-default community string)", e))
        }
    }

    /**
     * SNMPv2c GetBulk walk (RFC 3416, PDU tag 0xA5) -- repeatedly asks "give me the next
     * [maxRepetitions] OIDs after this one" and follows the chain until it walks outside
     * [baseOid]'s subtree, hits `endOfMibView`/`noSuchObject`/`noSuchInstance`, or [maxIterations]
     * is reached (a hard safety bound -- a misbehaving agent could otherwise loop forever).
     * Returns an ordered oid->value map; values are formatted per their real SNMP type
     * (OCTET STRING, INTEGER, Counter32/Gauge32/TimeTicks/Counter64, IpAddress), not just the
     * OCTET STRING-only handling [query] needs for sysName/sysDescr.
     */
    suspend fun walk(
        ipAddress: String,
        baseOid: String,
        community: String = "public",
        maxRepetitions: Int = 10,
        maxIterations: Int = 50,
    ): Result<Map<String, String>> = withContext(Dispatchers.IO) {
        try {
            DatagramSocket().use { socket ->
                socket.soTimeout = TIMEOUT_MS
                val results = LinkedHashMap<String, String>()
                var currentOid = baseOid
                var iteration = 0
                walkLoop@ while (iteration < maxIterations) {
                    iteration++
                    val requestId = (0 until Int.MAX_VALUE).random()
                    val request = buildGetBulkRequest(community, requestId, currentOid, maxRepetitions)
                    socket.send(DatagramPacket(request, request.size, InetAddress.getByName(ipAddress), SNMP_PORT))

                    val buffer = ByteArray(4096)
                    val responsePacket = DatagramPacket(buffer, buffer.size)
                    socket.receive(responsePacket)

                    val varBinds = parseGetBulkResponse(buffer.copyOf(responsePacket.length))
                    if (varBinds.isEmpty()) break@walkLoop

                    var madeProgress = false
                    for ((oid, node) in varBinds) {
                        if (node.tag == 0x80 || node.tag == 0x81 || node.tag == 0x82) break@walkLoop // noSuchObject/noSuchInstance/endOfMibView
                        if (oid != baseOid && !oid.startsWith("$baseOid.")) break@walkLoop // walked past the requested subtree
                        if (results.containsKey(oid)) break@walkLoop // agent isn't advancing -- stop instead of spinning
                        results[oid] = decodeValue(node)
                        currentOid = oid
                        madeProgress = true
                    }
                    if (!madeProgress) break@walkLoop
                }
                if (results.isEmpty()) Result.failure(IOException("No SNMP walk data from $ipAddress under $baseOid"))
                else Result.success(results)
            }
        } catch (e: Exception) {
            Result.failure(IOException("SNMP walk failed for $ipAddress under $baseOid (device may not support GetBulk, or the subtree doesn't exist)", e))
        }
    }

    // --- BER/ASN.1 encoding -------------------------------------------------

    private fun berLength(len: Int): ByteArray {
        if (len < 0x80) return byteArrayOf(len.toByte())
        val bytes = mutableListOf<Byte>()
        var n = len
        while (n > 0) {
            bytes.add(0, (n and 0xFF).toByte())
            n = n shr 8
        }
        return byteArrayOf((0x80 or bytes.size).toByte()) + bytes.toByteArray()
    }

    private fun tlv(tag: Int, content: ByteArray): ByteArray =
        byteArrayOf(tag.toByte()) + berLength(content.size) + content

    private fun berInteger(value: Int): ByteArray {
        if (value == 0) return tlv(0x02, byteArrayOf(0))
        var v = value
        val bytes = mutableListOf<Byte>()
        while (v > 0) {
            bytes.add(0, (v and 0xFF).toByte())
            v = v shr 8
        }
        if (bytes[0].toInt() and 0x80 != 0) bytes.add(0, 0) // leading zero so it isn't read as negative
        return tlv(0x02, bytes.toByteArray())
    }

    private fun berOctetString(bytes: ByteArray): ByteArray = tlv(0x04, bytes)

    private fun berNull(): ByteArray = tlv(0x05, ByteArray(0))

    private fun berOid(dotted: String): ByteArray {
        val parts = dotted.split(".").map { it.toInt() }
        val out = mutableListOf<Byte>()
        out.add((parts[0] * 40 + parts[1]).toByte())
        for (i in 2 until parts.size) {
            var v = parts[i]
            if (v == 0) {
                out.add(0)
                continue
            }
            val chunk = mutableListOf<Int>()
            while (v > 0) {
                chunk.add(0, v and 0x7F)
                v = v shr 7
            }
            chunk.forEachIndexed { index, b ->
                out.add(if (index != chunk.lastIndex) (b or 0x80).toByte() else b.toByte())
            }
        }
        return tlv(0x06, out.toByteArray())
    }

    private fun buildGetRequest(community: String, requestId: Int, oids: List<String>): ByteArray {
        val varBinds = oids.map { tlv(0x30, berOid(it) + berNull()) }
        val varBindList = tlv(0x30, varBinds.reduce { a, b -> a + b })
        val pdu = berInteger(requestId) + berInteger(0) + berInteger(0) + varBindList
        val getRequestPdu = tlv(0xA0, pdu)
        // Version 1 = SNMPv2c (0 = v1). community defaults to "public", the de facto read-only default.
        val message = berInteger(1) + berOctetString(community.toByteArray(Charsets.US_ASCII)) + getRequestPdu
        return tlv(0x30, message)
    }

    private fun buildGetBulkRequest(community: String, requestId: Int, oid: String, maxRepetitions: Int): ByteArray {
        val varBindList = tlv(0x30, tlv(0x30, berOid(oid) + berNull()))
        // non-repeaters=0 -- every requested OID (just the one here) is a repeater, per RFC 3416.
        val pdu = berInteger(requestId) + berInteger(0) + berInteger(maxRepetitions) + varBindList
        val getBulkPdu = tlv(0xA5, pdu)
        val message = berInteger(1) + berOctetString(community.toByteArray(Charsets.US_ASCII)) + getBulkPdu
        return tlv(0x30, message)
    }

    // --- BER/ASN.1 decoding (just enough to walk a GetResponse) -------------

    private data class BerNode(val tag: Int, val content: ByteArray)

    private fun readTlv(bytes: ByteArray, offset: Int): Pair<BerNode, Int> {
        val tag = bytes[offset].toInt() and 0xFF
        var pos = offset + 1
        var len = bytes[pos].toInt() and 0xFF
        pos++
        if (len and 0x80 != 0) {
            val numLenBytes = len and 0x7F
            len = 0
            repeat(numLenBytes) {
                len = (len shl 8) or (bytes[pos].toInt() and 0xFF)
                pos++
            }
        }
        val content = bytes.copyOfRange(pos, pos + len)
        return BerNode(tag, content) to (pos + len)
    }

    private fun readChildren(content: ByteArray): List<BerNode> {
        val nodes = mutableListOf<BerNode>()
        var pos = 0
        while (pos < content.size) {
            val (node, next) = readTlv(content, pos)
            nodes.add(node)
            pos = next
        }
        return nodes
    }

    private fun decodeOid(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""
        val first = bytes[0].toInt() and 0xFF
        val parts = mutableListOf(first / 40, first % 40)
        var value = 0
        for (i in 1 until bytes.size) {
            val b = bytes[i].toInt() and 0xFF
            value = (value shl 7) or (b and 0x7F)
            if (b and 0x80 == 0) {
                parts.add(value)
                value = 0
            }
        }
        return parts.joinToString(".")
    }

    /** Same GetResponse-PDU shape a GetBulk reply comes back as (tag 0xA2, RFC 3416) -- returns raw (oid, valueNode) pairs, in order, since [SnmpClient.walk] needs the type tag to detect endOfMibView/noSuchObject/noSuchInstance and to format non-string types. */
    private fun parseGetBulkResponse(bytes: ByteArray): List<Pair<String, BerNode>> {
        val (message, _) = readTlv(bytes, 0)
        val top = readChildren(message.content)
        val pdu = top.firstOrNull { it.tag == 0xA2 } ?: return emptyList()
        val pduFields = readChildren(pdu.content)
        val varBindList = pduFields.getOrNull(3) ?: return emptyList()
        return readChildren(varBindList.content).mapNotNull { varBind ->
            val fields = readChildren(varBind.content)
            val oidNode = fields.getOrNull(0) ?: return@mapNotNull null
            val valueNode = fields.getOrNull(1) ?: return@mapNotNull null
            decodeOid(oidNode.content) to valueNode
        }
    }

    /** Formats a varbind value per its real SNMP/BER type tag -- GetBulk walks return far more types than [query]'s OCTET-STRING-only sysName/sysDescr. */
    private fun decodeValue(node: BerNode): String = when (node.tag) {
        0x02 -> decodeSignedLong(node.content).toString() // INTEGER
        0x04 -> decodeOctetString(node.content) // OCTET STRING
        0x05 -> "" // NULL
        0x06 -> decodeOid(node.content) // OBJECT IDENTIFIER
        0x40 -> node.content.joinToString(".") { (it.toInt() and 0xFF).toString() } // IpAddress
        0x41, 0x42 -> decodeUnsignedLong(node.content).toString() // Counter32 / Gauge32
        0x43 -> "${decodeUnsignedLong(node.content)} ticks" // TimeTicks (centiseconds)
        0x46 -> decodeUnsignedLong(node.content).toString() // Counter64
        else -> node.content.joinToString("") { "%02x".format(it) }
    }

    private fun decodeOctetString(bytes: ByteArray): String =
        if (bytes.all { (it.toInt() and 0xFF) in 32..126 }) String(bytes, Charsets.UTF_8)
        else bytes.joinToString(":") { "%02x".format(it) }

    private fun decodeSignedLong(bytes: ByteArray): Long {
        if (bytes.isEmpty()) return 0
        var v = 0L
        bytes.forEach { v = (v shl 8) or (it.toLong() and 0xFF) }
        if (bytes[0].toInt() and 0x80 != 0) v -= (1L shl (8 * bytes.size))
        return v
    }

    private fun decodeUnsignedLong(bytes: ByteArray): Long {
        var v = 0L
        bytes.forEach { v = (v shl 8) or (it.toLong() and 0xFF) }
        return v
    }

    /** Walks SEQUENCE(version, community, GetResponse PDU(request-id, error-status, error-index, SEQUENCE OF varbind)) -> oid to string value. */
    private fun parseGetResponse(bytes: ByteArray): Map<String, String> {
        val (message, _) = readTlv(bytes, 0)
        val top = readChildren(message.content)
        val pdu = top.firstOrNull { it.tag == 0xA2 } ?: return emptyMap() // 0xA2 = GetResponse-PDU
        val pduFields = readChildren(pdu.content)
        val varBindList = pduFields.getOrNull(3) ?: return emptyMap()
        val result = mutableMapOf<String, String>()
        readChildren(varBindList.content).forEach { varBind ->
            val fields = readChildren(varBind.content)
            val oidNode = fields.getOrNull(0) ?: return@forEach
            val valueNode = fields.getOrNull(1) ?: return@forEach
            if (valueNode.tag == 0x04) { // OCTET STRING -- sysName/sysDescr are always this type
                result[decodeOid(oidNode.content)] = String(valueNode.content, Charsets.UTF_8)
            }
        }
        return result
    }
}
