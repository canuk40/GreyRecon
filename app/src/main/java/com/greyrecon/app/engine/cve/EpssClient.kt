package com.greyrecon.app.engine.cve

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
 * EPSS (first.org's Exploit Prediction Scoring System) client -- a CVE's CVSS severity says how bad
 * it *would* be if exploited, not how likely that actually is; EPSS is a real, continuously-updated
 * probability (0.0-1.0) that a given CVE will see exploitation in the wild in the next 30 days. Pairs
 * directly with [NvdClient]'s output: NVD's keyword search alone can't distinguish "critical CVE
 * nobody has ever bothered to exploit" from "actively getting hit right now", which is the actual
 * question a pentester prioritizing findings needs answered.
 *
 * Unauthenticated, no rate limit documented (unlike NVD's), and takes a batch of CVE ids in one
 * request -- so this always fires as a single follow-up call after an [NvdClient] search, not one
 * request per finding.
 */
object EpssClient {

    private val json = Json { ignoreUnknownKeys = true }
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    suspend fun lookup(cveIds: List<String>): Result<Map<String, Double>> = withContext(Dispatchers.IO) {
        if (cveIds.isEmpty()) return@withContext Result.success(emptyMap())
        try {
            val url = "https://api.first.org/data/v1/epss".toHttpUrl().newBuilder()
                .addQueryParameter("cve", cveIds.joinToString(","))
                .build()

            client.newCall(Request.Builder().url(url).get().build()).execute().use { response ->
                if (!response.isSuccessful) return@withContext Result.failure(IOException("EPSS lookup failed: HTTP ${response.code}"))
                val body = response.body?.string() ?: return@withContext Result.success(emptyMap())
                val root = json.parseToJsonElement(body).jsonObject
                val scores = root["data"]?.jsonArray.orEmpty().mapNotNull { entry ->
                    val obj = entry.jsonObject
                    val cve = obj["cve"]?.jsonPrimitive?.content ?: return@mapNotNull null
                    val score = obj["epss"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: return@mapNotNull null
                    cve to score
                }.toMap()
                Result.success(scores)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
