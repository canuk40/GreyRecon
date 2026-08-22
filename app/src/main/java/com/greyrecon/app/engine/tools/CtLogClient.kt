package com.greyrecon.app.engine.tools

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Certificate Transparency search via crt.sh -- a free, public, unauthenticated read-only search
 * over the CT logs every publicly-trusted CA is required to publish to (RFC 6962). Every cert ever
 * issued for a domain shows up here whether or not the issuing CA/registrant wanted it to, which is
 * exactly what makes CT logs useful for two distinct real capabilities at once, both from the same
 * query:
 *
 * 1. **Passive subdomain enumeration** -- SAN/CN entries across a domain's issued certs are a real
 *    subdomain list gathered with zero active DNS bruteforcing against the target.
 * 2. **New-certificate monitoring** -- issuance timestamps reveal a cert minted for a domain the
 *    watcher didn't expect (subdomain takeover risk, or a compromised/rogue cert).
 *
 * crt.sh has no documented rate limit but is a shared community resource -- calls here are
 * on-demand (Tools screen), never part of the automatic scan.
 */
object CtLogClient {

    private val json = Json { ignoreUnknownKeys = true }
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS) // crt.sh can be slow under load -- longer timeout than the app's other tool clients
        .build()

    data class CtEntry(val commonName: String, val issuerName: String, val entryTimestamp: String)

    data class CtLogResult(val subdomains: List<String>, val recentCerts: List<CtEntry>)

    suspend fun search(domain: String): Result<CtLogResult> = withContext(Dispatchers.IO) {
        runCatching {
            val url = "https://crt.sh/".toHttpUrl().newBuilder()
                .addQueryParameter("q", domain)
                .addQueryParameter("output", "json")
                .build()

            client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                if (!response.isSuccessful) throw IOException("crt.sh lookup failed: HTTP ${response.code}")
                val body = response.body?.string()
                if (body.isNullOrBlank()) return@withContext Result.success(CtLogResult(emptyList(), emptyList()))

                val entries = json.parseToJsonElement(body).jsonArray
                val subdomains = sortedSetOf<String>()
                val certs = mutableListOf<CtEntry>()

                entries.forEach { entry ->
                    val obj = entry.jsonObject
                    val commonName = obj["common_name"]?.jsonPrimitive?.content
                    val nameValue = obj["name_value"]?.jsonPrimitive?.content
                    val issuer = obj["issuer_name"]?.jsonPrimitive?.content ?: "?"
                    val timestamp = obj["entry_timestamp"]?.jsonPrimitive?.content ?: "?"

                    // name_value carries every SAN entry for the cert, one per line -- the real subdomain list.
                    nameValue?.lineSequence()?.forEach { line -> if (line.isNotBlank()) subdomains += line.trim() }
                    commonName?.let { subdomains += it }

                    if (commonName != null) certs += CtEntry(commonName, issuer, timestamp)
                }

                CtLogResult(
                    subdomains = subdomains.toList(),
                    recentCerts = certs.distinctBy { it.commonName to it.entryTimestamp }
                        .sortedByDescending { it.entryTimestamp }
                        .take(30),
                )
            }
        }
    }
}
