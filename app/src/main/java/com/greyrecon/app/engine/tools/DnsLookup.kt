package com.greyrecon.app.engine.tools

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

data class DnsRecord(val name: String, val type: String, val data: String, val ttl: Int)

/** DNS-over-HTTPS via Google's JSON API (dns.google) -- Android's InetAddress only resolves A/AAAA via the system resolver, not MX/TXT/NS/etc., and DoH avoids needing a raw UDP socket to an arbitrary DNS server. */
object DnsLookup {

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()
    private val json = Json { ignoreUnknownKeys = true }

    private val TYPE_NAMES = mapOf(1 to "A", 2 to "NS", 5 to "CNAME", 6 to "SOA", 15 to "MX", 16 to "TXT", 28 to "AAAA")

    suspend fun lookup(domain: String, type: String): Result<List<DnsRecord>> = withContext(Dispatchers.IO) {
        runCatching {
            val url = "https://dns.google/resolve?name=${domain.trim()}&type=$type"
            val request = Request.Builder().url(url).header("Accept", "application/dns-json").build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("DNS query failed: HTTP ${response.code}")
                val body = response.body?.string() ?: throw IOException("Empty response")
                val root = json.parseToJsonElement(body).jsonObject
                val answers = root["Answer"]?.jsonArray ?: return@runCatching emptyList()
                answers.map { entry ->
                    val obj = entry.jsonObject
                    val typeCode = obj["type"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
                    DnsRecord(
                        name = obj["name"]?.jsonPrimitive?.content ?: domain,
                        type = TYPE_NAMES[typeCode] ?: "TYPE$typeCode",
                        data = obj["data"]?.jsonPrimitive?.content ?: "",
                        ttl = obj["TTL"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
                    )
                }
            }
        }
    }
}
