package com.greyrecon.app.engine.cve

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * NVD (nvd.nist.gov) CVE keyword-search client -- the fill-in for what Shodan
 * can't do: Shodan is keyed on a device's PUBLIC IP, so it fails for nearly
 * every LAN-only device (see ShodanClient). NVD's keyword search isn't
 * IP-based at all -- it matches a phrase against CVE descriptions -- so it
 * works identically whether the device is internet-facing or not. The real
 * tradeoff: matching is by vendor/product keyword, not a confirmed exact
 * firmware version, so a result here is "a known vulnerability class
 * associated with this vendor/service", not "your exact device has this CVE".
 * That distinction belongs in the UI, not silently lost in this client.
 *
 * A free NVD API key raises the rate limit from 5 req/30s to 50 req/30s --
 * optional here since a single on-demand per-device lookup rarely needs it,
 * but supported the same BYOK way as the Shodan/AI keys.
 */
class NvdClient(
    private val apiKey: String?,
    private val client: OkHttpClient = defaultClient,
) {

    suspend fun search(vendor: String, serviceHint: String? = null): Result<List<CveFinding>> = withContext(Dispatchers.IO) {
        try {
            val keywords = listOfNotNull(primaryVendorName(vendor), serviceHint).joinToString(" ")
            val url = "https://services.nvd.nist.gov/rest/json/cves/2.0".toHttpUrl().newBuilder()
                .addQueryParameter("keywordSearch", keywords)
                .addQueryParameter("resultsPerPage", "5")
                .build()

            val requestBuilder = Request.Builder().url(url).get()
            if (!apiKey.isNullOrBlank()) requestBuilder.addHeader("apiKey", apiKey)

            client.newCall(requestBuilder.build()).execute().use { response ->
                when (response.code) {
                    429 -> return@withContext Result.failure(IOException("NVD rate limit hit -- add a free NVD API key in Settings for a higher limit, or wait a moment and retry"))
                    403 -> return@withContext Result.failure(IOException("NVD rejected the request (invalid API key?)"))
                }
                if (!response.isSuccessful) {
                    return@withContext Result.failure(IOException("NVD lookup failed: HTTP ${response.code}"))
                }
                val body = response.body?.string() ?: return@withContext Result.success(emptyList())
                val root = json.parseToJsonElement(body).jsonObject
                Result.success(toFindings(root))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * MAC OUI vendor strings carry registry boilerplate a real CVE description never quotes verbatim
     * -- "D-Link Corporation" (0 real NVD results, confirmed live) vs "D-Link" (1825 results). NVD's
     * keywordSearch requires every word in the query to appear, so a trailing "Corporation"/"Inc"/
     * "Co.,Ltd" silently zeroes out results for almost every real vendor string. The brand name is
     * reliably the leading token, so take just that.
     */
    private fun primaryVendorName(vendor: String): String =
        vendor.trim().substringBefore(' ').substringBefore(',').substringBefore(';')

    private fun toFindings(root: JsonObject): List<CveFinding> {
        val vulnerabilities = root["vulnerabilities"]?.jsonArray ?: return emptyList()
        return vulnerabilities.mapNotNull { entry ->
            val cve = entry.jsonObject["cve"]?.jsonObject ?: return@mapNotNull null
            val id = cve["id"]?.jsonPrimitive?.content ?: return@mapNotNull null
            val description = cve["descriptions"]?.jsonArray
                ?.firstOrNull { it.jsonObject["lang"]?.jsonPrimitive?.content == "en" }
                ?.jsonObject?.get("value")?.jsonPrimitive?.content
                ?: "No description available."
            CveFinding(cveId = id, summary = description.take(240), severity = extractSeverity(cve))
        }
    }

    /** Prefers the newest CVSS version present -- v3.1, then v3.0, then v2 -- same fallback order NVD's own UI uses. */
    private fun extractSeverity(cve: JsonObject): String? {
        val metrics = cve["metrics"]?.jsonObject ?: return null
        for (key in listOf("cvssMetricV31", "cvssMetricV30", "cvssMetricV2")) {
            val severity = metrics[key]?.jsonArray?.firstOrNull()
                ?.jsonObject?.get("cvssData")?.jsonObject?.get("baseSeverity")?.jsonPrimitive?.content
            if (severity != null) return severity
        }
        return null
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        private val defaultClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}
