package com.greyrecon.app.engine.security

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

data class ExposureFinding(val path: String, val name: String)

/**
 * A small, curated set of well-known misconfiguration/exposure checks -- the same category as
 * `projectdiscovery/nuclei`'s simplest "exposed-panels"/"exposures" templates (version-fingerprint
 * probing, default-credential login, or actual exploitation are explicitly out of scope here; every
 * check below is a single non-destructive GET plus a signature match, same as [SecurityChecker]'s
 * existing header check). Not a YAML-template engine or a port of nuclei's thousands of templates --
 * six real, broadly-applicable checks, chosen because each is a genuinely common real-world exposure
 * rather than product-specific trivia.
 *
 * Scoped as an on-demand per-device action, same reasoning as [SnmpClient.bruteForceCommunity]:
 * sixteen extra requests per device would be real noise baked into every automatic scan.
 *
 * Extended (SESSION 22) with ten more checks chosen by reading through
 * `projectdiscovery/nuclei-templates` (MIT) for its highest-signal, most broadly-applicable
 * exposure/debug-panel categories -- not a port of the tool or its YAML engine (a whole template
 * DSL runtime would be disproportionate here), just the same curated-signature approach applied to
 * the specific paths/markers that show up across many of that corpus's real-world templates.
 */
object ExposureChecker {

    private val CHECKS = listOf(
        Triple("/.git/config", listOf("[core]"), "Exposed .git repository (source history/credentials leak risk)"),
        Triple("/.env", listOf("APP_KEY", "DB_PASSWORD", "DB_USERNAME", "SECRET_KEY"), "Exposed .env file (credentials/config leak)"),
        Triple("/server-status", listOf("Apache Server Status"), "Exposed Apache mod_status page (internal request/traffic disclosure)"),
        Triple("/actuator/env", listOf("\"propertySources\"", "\"activeProfiles\""), "Exposed Spring Boot Actuator env endpoint (config/secrets disclosure)"),
        Triple("/manager/html", listOf("Tomcat Web Application Manager"), "Exposed Tomcat Manager login (default-credential attack surface)"),
        Triple("/phpinfo.php", listOf("phpinfo()"), "Exposed phpinfo() page (full server config/environment disclosure)"),
        Triple("/.git/HEAD", listOf("ref: refs/"), "Exposed .git repository (source history/credentials leak risk)"),
        Triple("/.aws/credentials", listOf("aws_access_key_id", "aws_secret_access_key"), "Exposed AWS credentials file"),
        Triple("/.htpasswd", listOf("\$apr1\$", ":{SHA}"), "Exposed .htpasswd file (credential leak)"),
        Triple("/.vscode/sftp.json", listOf("\"host\"", "\"password\""), "Exposed VSCode SFTP deployment config (credentials leak)"),
        Triple("/debug/pprof/", listOf("Types of profiles available"), "Exposed Go pprof debug endpoint (internal runtime disclosure)"),
        Triple("/console", listOf("Werkzeug Debugger"), "Exposed Werkzeug/Flask debug console (potential remote code execution)"),
        Triple("/_profiler/", listOf("Symfony Profiler"), "Exposed Symfony profiler (internal request/config disclosure)"),
        Triple("/telescope/requests", listOf("laravel-telescope"), "Exposed Laravel Telescope debug panel (full app internals disclosure)"),
        Triple("/nginx_status", listOf("Active connections"), "Exposed nginx status page (internal traffic disclosure)"),
        Triple("/swagger-ui.html", listOf("swagger-ui"), "Exposed Swagger/OpenAPI documentation (API surface disclosure)"),
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(4, TimeUnit.SECONDS)
        .followRedirects(false) // a redirect away from the checked path means the exposure doesn't actually exist at that path
        .build()

    suspend fun check(ipAddress: String): Result<List<ExposureFinding>> = withContext(Dispatchers.IO) {
        runCatching {
            val baseUrl = firstReachableBase(ipAddress) ?: throw java.io.IOException("No HTTP(S) service responded on $ipAddress")
            CHECKS.map { (path, signatures, name) ->
                async { probeOne(baseUrl, path, signatures, name) }
            }.awaitAll().filterNotNull()
        }
    }

    private fun firstReachableBase(ipAddress: String): String? =
        listOf("https://$ipAddress", "http://$ipAddress").firstOrNull { base ->
            runCatching { client.newCall(Request.Builder().url(base).head().build()).execute().use { true } }.getOrDefault(false)
        }

    private fun probeOne(baseUrl: String, path: String, signatures: List<String>, name: String): ExposureFinding? =
        try {
            client.newCall(Request.Builder().url("$baseUrl$path").get().build()).execute().use { response ->
                if (response.code != 200) return null
                val body = response.body?.string()?.take(65_536) ?: return null
                if (signatures.any { body.contains(it) }) ExposureFinding(path, name) else null
            }
        } catch (_: Exception) {
            null
        }
}
