package com.greyrecon.app.engine.tools

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.Socket

/**
 * Tries RDAP first ([RdapClient] -- structured JSON, RFC 9082/9083), falling back to legacy
 * port-43 text WHOIS only if RDAP fails (a TLD/RIR not yet in IANA's bootstrap, or a real network
 * error) -- RDAP's structured JSON is both more reliable to parse and more current than scraping
 * inconsistent legacy WHOIS text formats. Private/reserved IP ranges are special-cased with an
 * immediate canned explanation instead of a real network round-trip for information that never
 * changes -- confirmed via direct code read this app previously lacked (a lookup on 192.168.x.x
 * would silently go all the way to IANA's legacy WHOIS server for raw, unfriendly registry text).
 */
object WhoisLookup {

    suspend fun lookup(query: String): Result<String> = withContext(Dispatchers.IO) {
        privateRangeExplanation(query)?.let { return@withContext Result.success(it) }

        val rdapResult = if (looksLikeDomain(query)) RdapClient.lookupDomain(query) else RdapClient.lookupIp(query)
        rdapResult.recoverCatching { legacyLookup(query).getOrThrow() }
    }

    private fun legacyLookup(query: String): Result<String> = runCatching {
        val first = queryServer("whois.iana.org", query)
        val referServer = first.lineSequence()
            .firstOrNull { it.startsWith("refer:", ignoreCase = true) }
            ?.substringAfter(":")
            ?.trim()
        if (referServer.isNullOrBlank()) return@runCatching first

        // Thin registries (VeriSign's .com/.net server confirmed live) return only generic
        // TLD-registry info for a bare query -- a "domain " prefix is needed to get the
        // actual per-domain record (creation/expiry date, registrar, nameservers). Only applies
        // to domain queries, not IP lookups, which use a different server model entirely.
        val referredQuery = if (looksLikeDomain(query)) "domain $query" else query
        "$first\n\n--- $referServer ---\n\n${queryServer(referServer, referredQuery)}"
    }

    private fun looksLikeDomain(query: String): Boolean = query.any { it.isLetter() }

    /** RFC 1918/6598/1122/3927 reserved ranges -- always the same non-registry answer, never worth a real lookup. */
    private fun privateRangeExplanation(query: String): String? {
        val parts = query.split(".").map { it.toIntOrNull() }
        if (parts.size != 4 || parts.any { it == null || it !in 0..255 }) return null
        val a = parts[0]!!
        val b = parts[1]!!
        return when {
            a == 10 -> "10.0.0.0/8 -- private-use address space (RFC 1918). Not publicly routable; assigned by your own router/DHCP server, not a real registry."
            a == 172 && b in 16..31 -> "172.16.0.0/12 -- private-use address space (RFC 1918). Not publicly routable; assigned by your own router/DHCP server, not a real registry."
            a == 192 && b == 168 -> "192.168.0.0/16 -- private-use address space (RFC 1918). Not publicly routable; assigned by your own router/DHCP server, not a real registry."
            a == 100 && b in 64..127 -> "100.64.0.0/10 -- shared address space (RFC 6598), commonly used by carrier-grade NAT. Not publicly routable."
            a == 127 -> "127.0.0.0/8 -- loopback address space (RFC 1122). Refers to the local device itself."
            a == 169 && b == 254 -> "169.254.0.0/16 -- link-local address space (RFC 3927). Self-assigned when a device can't reach a DHCP server."
            else -> null
        }
    }

    private fun queryServer(host: String, query: String): String {
        Socket(host, 43).use { socket ->
            socket.soTimeout = 8_000
            PrintWriter(socket.getOutputStream(), true).apply {
                print("$query\r\n")
                flush()
            }
            return BufferedReader(InputStreamReader(socket.getInputStream())).readText()
        }
    }
}
