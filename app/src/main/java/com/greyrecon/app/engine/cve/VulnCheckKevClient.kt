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

/** One entry from VulnCheck's own KEV index -- see [VulnCheckKevClient]'s doc comment for how this differs from [KevClient]/CISA's catalog. */
data class VulnCheckKevEntry(
    val cveIds: List<String>,
    val vulnerabilityName: String,
    val dateAdded: String,
    val requiredAction: String,
    val knownRansomwareUse: Boolean,
    /** Count of independent sources VulnCheck's own research separately confirmed exploitation from -- richer than CISA's plain yes/no flag. */
    val reportedExploitationSourceCount: Int,
)

/**
 * Looks a CVE up in VulnCheck's own KEV index (`vulncheck-kev`) -- a real superset of CISA's
 * catalog, not a duplicate of [KevClient]: VulnCheck's own published figures put it at roughly
 * double CISA's entry count, since CISA's KEV only lists CVEs the US government has itself acted
 * on, while VulnCheck's own research team adds independently-confirmed exploited CVEs CISA hasn't
 * (yet, or ever will) formally list. Complements KevClient rather than replacing it -- a CVE absent
 * from CISA's list can still show up here.
 *
 * BYOK, free tier (`SecureKeyStore.vulncheckKey`) -- required for this lookup, unlike KevClient's
 * CISA catalog which needs no key at all. Endpoint and response shape confirmed against VulnCheck's
 * own published OpenAPI spec (vulncheck-oss/sdk-go-v2 on GitHub, Apache-2.0), not guessed: a direct
 * per-CVE query (`?cve=`) against `/v3/index/vulncheck-kev`, not the generic bulk-backup-file
 * mechanism the rest of VulnCheck's ~500 other indices use -- KEV specifically gets its own richer,
 * filterable endpoint since it's a flagship dataset.
 */
class VulnCheckKevClient(private val apiKey: String?) {

    suspend fun lookup(cveId: String): Result<VulnCheckKevEntry?> = withContext(Dispatchers.IO) {
        val key = apiKey?.takeIf { it.isNotBlank() }
            ?: return@withContext Result.failure(IOException("No VulnCheck API key configured"))
        try {
            val url = "https://api.vulncheck.com/v3/index/vulncheck-kev".toHttpUrl().newBuilder()
                .addQueryParameter("cve", cveId.trim().uppercase())
                .build()
            val request = Request.Builder().url(url).header("Authorization", "Bearer $key").get().build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext Result.failure(IOException("VulnCheck lookup failed: HTTP ${response.code}"))
                val body = response.body?.string() ?: return@withContext Result.success(null)
                val entries = json.parseToJsonElement(body).jsonObject["data"]?.jsonArray.orEmpty()
                val entry = entries.firstOrNull()?.jsonObject ?: return@withContext Result.success(null)
                Result.success(parseEntry(entry))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun parseEntry(entry: JsonObject): VulnCheckKevEntry {
        val cveIds = entry["cve"]?.jsonArray?.mapNotNull { it.jsonPrimitive.content } ?: emptyList()
        return VulnCheckKevEntry(
            cveIds = cveIds,
            vulnerabilityName = entry["vulnerabilityName"]?.jsonPrimitive?.content ?: cveIds.firstOrNull() ?: "unknown",
            dateAdded = entry["date_added"]?.jsonPrimitive?.content ?: "unknown",
            requiredAction = entry["required_action"]?.jsonPrimitive?.content ?: "",
            knownRansomwareUse = entry["knownRansomwareCampaignUse"]?.jsonPrimitive?.content
                ?.equals("Known", ignoreCase = true) == true,
            reportedExploitationSourceCount = entry["vulncheck_reported_exploitation"]?.jsonArray?.size ?: 0,
        )
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        private val client = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }
}
