package com.greyrecon.app.engine.cve

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/** One entry in CISA's Known Exploited Vulnerabilities catalog. */
data class KevEntry(
    val cveId: String,
    val name: String,
    val dateAdded: String,
    val requiredAction: String,
    val knownRansomwareUse: Boolean,
)

/**
 * Looks a CVE up in CISA's Known Exploited Vulnerabilities (KEV) catalog -- the authoritative list
 * of CVEs with confirmed in-the-wild exploitation. This is the piece [NvdClient]/[EpssClient] can't
 * give on their own: NVD says "this CVE exists" and EPSS estimates "how likely it is to be exploited",
 * but KEV says "this is being exploited RIGHT NOW", which is the strongest single prioritization
 * signal a defender has.
 *
 * The catalog is a single public JSON file, US-government work released as CC0 / public domain (no
 * key, no terms restriction -- safe for a paid app, unlike several rate-limited commercial threat
 * feeds). Fetched once and cached in-process, since it only updates a few times a week and a per-CVE
 * lookup shouldn't re-download ~2MB every time.
 */
class KevClient(private val client: OkHttpClient = defaultClient) {

    @Volatile
    private var cache: Map<String, KevEntry>? = null
    private val loadMutex = Mutex()

    /** Returns the KEV entry for [cveId] (case-insensitive), or null if the CVE isn't on the catalog. */
    suspend fun lookup(cveId: String): Result<KevEntry?> = withContext(Dispatchers.IO) {
        runCatching {
            val catalog = ensureCatalog()
            catalog[cveId.trim().uppercase()]
        }
    }

    private suspend fun ensureCatalog(): Map<String, KevEntry> {
        cache?.let { return it }
        return loadMutex.withLock {
            cache?.let { return it }
            fetchCatalog().also { cache = it }
        }
    }

    private fun fetchCatalog(): Map<String, KevEntry> {
        val request = Request.Builder().url(CATALOG_URL).get().build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("CISA KEV fetch failed: HTTP ${response.code}")
            val body = response.body?.string() ?: throw IOException("Empty KEV response")
            val vulns = json.parseToJsonElement(body).jsonObject["vulnerabilities"]?.jsonArray
                ?: throw IOException("Unexpected KEV catalog shape")
            val map = HashMap<String, KevEntry>(vulns.size)
            vulns.forEach { entry ->
                val obj = entry.jsonObject
                val id = obj["cveID"]?.jsonPrimitive?.content ?: return@forEach
                map[id.uppercase()] = KevEntry(
                    cveId = id,
                    name = obj["vulnerabilityName"]?.jsonPrimitive?.content ?: id,
                    dateAdded = obj["dateAdded"]?.jsonPrimitive?.content ?: "unknown",
                    requiredAction = obj["requiredAction"]?.jsonPrimitive?.content ?: "",
                    knownRansomwareUse = obj["knownRansomwareCampaignUse"]?.jsonPrimitive?.content
                        ?.equals("Known", ignoreCase = true) == true,
                )
            }
            return map
        }
    }

    companion object {
        private const val CATALOG_URL =
            "https://www.cisa.gov/sites/default/files/feeds/known_exploited_vulnerabilities.json"
        private val json = Json { ignoreUnknownKeys = true }
        private val defaultClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .build()
    }
}
