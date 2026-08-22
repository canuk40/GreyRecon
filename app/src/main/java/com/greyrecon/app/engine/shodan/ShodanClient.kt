package com.greyrecon.app.engine.shodan

import com.greyrecon.app.engine.model.ShodanFinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Real Shodan REST API client -- GET /shodan/host/{ip}. This is the Pro-tier
 * differentiator: cross-references a discovered device's public IP against
 * Shodan's own internet-wide scan data for known CVEs and exposed service
 * banners, same technique RoboShadow's ShodanData* model classes revealed.
 *
 * Note: Shodan's host lookup is keyed on the device's PUBLIC IP, not its LAN
 * IP (192.168.x.x) -- most devices found by the local discovery engine won't
 * have a meaningful Shodan record unless they're directly internet-exposed
 * (e.g. a router with UPnP port forwarding, a misconfigured camera). This is
 * a real product constraint to design the UI around, not a bug: don't imply
 * every LAN device will have Shodan data.
 *
 * Confirmed live against the real API on a free-tier key: unindexed hosts
 * (which is nearly every LAN IP) come back as HTTP 403 "Requires membership
 * or higher," not 404 -- Shodan's free tier can only look up hosts it's
 * already indexed. Handled as its own distinct error rather than lumped in
 * with a real 404 "no record" or a real auth failure.
 */
class ShodanClient(
    private val apiKey: String,
    private val client: OkHttpClient = defaultClient,
) {

    suspend fun lookupHost(ipAddress: String): Result<List<ShodanFinding>> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("https://api.shodan.io/shodan/host/$ipAddress?key=$apiKey")
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                when (response.code) {
                    404 -> return@withContext Result.success(emptyList()) // no Shodan record for this IP -- not an error
                    401 -> return@withContext Result.failure(IOException("Shodan API key invalid or unauthorized"))
                    // Confirmed via live testing: Shodan's free tier returns 403 "Requires membership
                    // or higher to access" for any host it hasn't already indexed -- which in practice
                    // is nearly every private/LAN IP. Not a bug or a real error, just a plan limit.
                    403 -> return@withContext Result.failure(IOException("Shodan free tier can't look up unindexed hosts (most private/LAN IPs) -- needs a paid membership"))
                }
                if (!response.isSuccessful) {
                    return@withContext Result.failure(IOException("Shodan lookup failed: HTTP ${response.code}"))
                }
                val body = response.body?.string() ?: return@withContext Result.success(emptyList())
                val root = json.parseToJsonElement(body).jsonObject
                val cveIds = extractVulnIds(root)
                val services = root["data"]?.jsonArray?.map { json.decodeFromJsonElement(ShodanService.serializer(), it) }.orEmpty()
                Result.success(toFindings(cveIds, services))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Shodan's documented examples show "vulns" as an array of CVE ID strings,
     * but some real responses (and older API versions) return it as an object
     * keyed by CVE ID instead -- docs don't give a hard guarantee either way.
     * Handle both rather than risk a full parse failure on real data.
     */
    private fun extractVulnIds(root: JsonObject): List<String> {
        val vulns = root["vulns"] ?: return emptyList()
        return when {
            vulns is kotlinx.serialization.json.JsonArray -> vulns.jsonArray.map { it.jsonPrimitive.content }
            else -> runCatching { vulns.jsonObject.keys.toList() }.getOrDefault(emptyList())
        }
    }

    private fun toFindings(cveIds: List<String>, services: List<ShodanService>): List<ShodanFinding> {
        val cveFindings = cveIds.map { cveId ->
            ShodanFinding(cveId = cveId, summary = "Known vulnerability $cveId reported by Shodan for this host.", severity = null)
        }
        val serviceFindings = services.map { service ->
            ShodanFinding(
                cveId = null,
                summary = "Port ${service.port}/${service.transport ?: "tcp"} exposed: ${service.product ?: service.data?.take(80) ?: "unidentified service"}",
                severity = null,
            )
        }
        return cveFindings + serviceFindings
    }

    @Serializable
    private data class ShodanService(
        val port: Int,
        val transport: String? = null,
        val product: String? = null,
        val data: String? = null,
    )

    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        private val defaultClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}
