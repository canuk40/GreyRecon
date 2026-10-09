package com.greyrecon.app.engine.scan

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Identifies what is actually answering on a device's open web port, from the `Server` header and
 * the page title.
 *
 * A port scan says "80/tcp open". That is a true statement that helps nobody decide anything. The
 * same request one layer further in usually says "Synology DiskStation" or "RouterOS" or
 * "nginx/1.24.0", which is the thing the user wanted to know and is also what makes the CVE
 * enrichment that already exists in this app targetable rather than generic.
 *
 * Deliberately conservative:
 *  - only runs against ports that are plausibly HTTP, so it does not poke at SSH or SMB
 *  - GET with a tiny read, not a crawl. One request per port, nothing followed, nothing parsed
 *    beyond the title tag
 *  - never sends credentials and never follows redirects off-host
 *  - short timeouts, because a silent device on the LAN should cost a couple of seconds, not hang
 *    the whole scan
 */
object HttpBannerProbe {

    /** Ports worth asking. Anything else is left alone. */
    val HTTP_PORTS = setOf(80, 81, 443, 591, 2080, 2443, 5000, 5001, 7080, 8000, 8008, 8080, 8081, 8088, 8443, 8843, 8888, 9000, 9443)

    private val client = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .followRedirects(false)
        .build()

    private val TITLE = Regex("<title[^>]*>(.*?)</title>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))

    /**
     * @return a short description such as "Synology DiskStation -- nginx", or null if nothing on
     *   [ports] answered usefully.
     */
    suspend fun probe(ipAddress: String, ports: Collection<Int>): String? = withContext(Dispatchers.IO) {
        ports.filter { it in HTTP_PORTS }
            .sortedBy { if (it == 443 || it == 8443) 0 else 1 } // prefer TLS when both are open
            .firstNotNullOfOrNull { port -> probePort(ipAddress, port) }
    }

    private fun probePort(ipAddress: String, port: Int): String? {
        val scheme = if (port == 443 || port == 8443 || port == 9443 || port == 2443) "https" else "http"
        val url = "$scheme://$ipAddress:$port/"
        return runCatching {
            client.newCall(Request.Builder().url(url).get().build()).execute().use { response ->
                val server = response.header("Server")?.trim()?.takeIf { it.isNotBlank() }
                // Cap the read: some devices stream, and a title lives in the first few KB or not
                // at all.
                val body = response.body?.source()?.let { source ->
                    source.request(MAX_BODY_BYTES)
                    source.buffer.snapshot(minOf(MAX_BODY_BYTES, source.buffer.size).toInt()).utf8()
                }
                val title = body?.let { TITLE.find(it)?.groupValues?.getOrNull(1) }
                    ?.replace(Regex("\\s+"), " ")
                    ?.trim()
                    ?.takeIf { it.isNotBlank() && it.length <= MAX_TITLE }

                when {
                    title != null && server != null -> "$title -- $server"
                    title != null -> title
                    server != null -> server
                    else -> null
                }
            }
        }.getOrNull()
    }

    private const val MAX_BODY_BYTES = 16L * 1024L
    private const val MAX_TITLE = 60
}
