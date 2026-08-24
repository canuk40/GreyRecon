package com.greyrecon.app.engine.tools

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

data class AbuseIpdbResult(
    val ip: String,
    val abuseConfidenceScore: Int,
    val totalReports: Int,
    val countryCode: String?,
    val isp: String?,
    val domain: String?,
    val usageType: String?,
    val lastReportedAt: String?,
)

/**
 * AbuseIPDB v2 `/check` -- an IP's crowd-sourced abuse-confidence score (0-100) and report history.
 * Complements GreyNoise (scanner classification vs. abuse reputation): a high score on the network's
 * own public IP can mean it's flagged as a compromised/botnet source; on an external IP it flags a
 * known-bad host a device is contacting.
 *
 * Requires a free API key (1000 checks/day), entered in Settings -- unlike GreyNoise, there is no
 * unauthenticated tier here.
 */
class AbuseIpdbClient(
    private val apiKey: String,
    private val client: OkHttpClient = defaultClient,
) {

    suspend fun check(ip: String, maxAgeDays: Int = 90): Result<AbuseIpdbResult> = withContext(Dispatchers.IO) {
        try {
            val url = "https://api.abuseipdb.com/api/v2/check".toHttpUrl().newBuilder()
                .addQueryParameter("ipAddress", ip.trim())
                .addQueryParameter("maxAgeInDays", maxAgeDays.toString())
                .build()

            val request = Request.Builder()
                .url(url)
                .header("Key", apiKey)
                .header("Accept", "application/json")
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                val body = response.body?.string()
                if (response.code == 401) return@withContext Result.failure(IOException("AbuseIPDB rejected the API key -- check it in Settings."))
                if (response.code == 429) return@withContext Result.failure(IOException("AbuseIPDB daily limit reached (1000/day on the free tier)."))
                if (!response.isSuccessful || body.isNullOrBlank()) return@withContext Result.failure(IOException("AbuseIPDB lookup failed: HTTP ${response.code}"))

                val data = json.parseToJsonElement(body).jsonObject["data"]?.jsonObject
                    ?: return@withContext Result.failure(IOException("Unexpected AbuseIPDB response shape"))
                Result.success(
                    AbuseIpdbResult(
                        ip = data["ipAddress"]?.jsonPrimitive?.content ?: ip,
                        abuseConfidenceScore = data["abuseConfidenceScore"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
                        totalReports = data["totalReports"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
                        countryCode = data["countryCode"]?.jsonPrimitive?.content,
                        isp = data["isp"]?.jsonPrimitive?.content,
                        domain = data["domain"]?.jsonPrimitive?.content,
                        usageType = data["usageType"]?.jsonPrimitive?.content,
                        lastReportedAt = data["lastReportedAt"]?.jsonPrimitive?.content,
                    )
                )
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        private val defaultClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }
}
