package com.greyrecon.app.engine.cve

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * CISA's SSVC (Stakeholder-Specific Vulnerability Categorization) decision points for a CVE --
 * Exploitation status ("none"/"poc"/"active"), Automatable ("yes"/"no": can exploitation be scripted
 * at scale without human-in-the-loop decisions), and Technical Impact ("partial"/"total"). A
 * different prioritization axis than [EpssClient]'s probability estimate or [KevClient]'s binary
 * "confirmed exploited" flag -- SSVC answers "should THIS org act now", not "how likely is this to
 * be exploited somewhere".
 *
 * Only present when CISA's ADP (Authorized Data Publisher) role has actually enriched a given CVE --
 * most CVEs have no SSVC assessment at all, which is a normal, common outcome, not a failure.
 */
data class SsvcAssessment(
    val exploitation: String?,
    val automatable: String?,
    val technicalImpact: String?,
)

/**
 * Per-CVE lookup against `cisagov/vulnrichment` (CC0-1.0, US government work) -- CISA's own
 * SSVC scoring plus backfilled CVSS/CWE/CPE where the original CNA never provided them, published
 * as one CVE-record-v5-shaped JSON file per CVE, sharded the same way the official CVE List v5
 * repo is (`<year>/<bucket>xxx/CVE-<year>-<n>.json`, bucket = the sequence number's thousands
 * digit(s)) -- so a single CVE's data can be fetched directly without downloading anything else.
 *
 * Deliberately NOT using the project's own `cvss-bt` CSV (t0sche/cvss-bt), which was the other
 * candidate for this same "exploit-maturity" signal: it's a single ~85 MB, 380,000-row flat file
 * with no per-CVE fetch path, updated daily -- fine for a server-side batch job, a real regression
 * for a mobile client that just wants one CVE's data (confirmed live: its Content-Length header
 * alone is ~86 MB). Vulnrichment's per-file layout is the only one of the two shapes that actually
 * fits this app's existing per-CVE lookup pattern (matching [KevClient]/[EpssClient]).
 */
object VulnrichmentClient {

    private val json = Json { ignoreUnknownKeys = true }
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val CVE_ID_PATTERN = Regex("^CVE-(\\d{4})-(\\d+)$", RegexOption.IGNORE_CASE)

    suspend fun lookup(cveId: String): Result<SsvcAssessment?> = withContext(Dispatchers.IO) {
        runCatching {
            val (year, bucket, normalizedId) = shardFor(cveId) ?: return@runCatching null
            val url = "https://raw.githubusercontent.com/cisagov/vulnrichment/develop/$year/${bucket}xxx/$normalizedId.json"
            val request = Request.Builder().url(url).get().build()
            client.newCall(request).execute().use { response ->
                // 404 means this CVE hasn't been enriched by CISA's ADP -- the common case, not
                // an error worth surfacing as a failure.
                if (response.code == 404) return@use null
                if (!response.isSuccessful) throw java.io.IOException("Vulnrichment fetch failed: HTTP ${response.code}")
                val body = response.body?.string() ?: return@use null
                extractSsvc(body)
            }
        }
    }

    private fun shardFor(cveId: String): Triple<String, String, String>? {
        val match = CVE_ID_PATTERN.find(cveId.trim()) ?: return null
        val year = match.groupValues[1]
        val sequence = match.groupValues[2]
        val bucket = (sequence.toLongOrNull()?.div(1000)) ?: return null
        return Triple(year, bucket.toString(), "CVE-$year-$sequence")
    }

    /** Walks `containers.adp[].metrics[].other` for the entry whose `type` is "ssvc". */
    private fun extractSsvc(body: String): SsvcAssessment? {
        val adp = json.parseToJsonElement(body).jsonObject["containers"]
            ?.jsonObject?.get("adp")?.jsonArray ?: return null

        adp.forEach { adpEntry ->
            val metrics = adpEntry.jsonObject["metrics"]?.jsonArray ?: return@forEach
            metrics.forEach { metric ->
                val other = metric.jsonObject["other"]?.jsonObject ?: return@forEach
                if (other["type"]?.jsonPrimitive?.content != "ssvc") return@forEach
                val options = other["content"]?.jsonObject?.get("options")?.jsonArray ?: return@forEach
                var exploitation: String? = null
                var automatable: String? = null
                var technicalImpact: String? = null
                options.forEach { option ->
                    val obj = option.jsonObject
                    obj["Exploitation"]?.jsonPrimitive?.content?.let { exploitation = it }
                    obj["Automatable"]?.jsonPrimitive?.content?.let { automatable = it }
                    obj["Technical Impact"]?.jsonPrimitive?.content?.let { technicalImpact = it }
                }
                return SsvcAssessment(exploitation, automatable, technicalImpact)
            }
        }
        return null
    }
}
