package com.greyrecon.app.engine.security

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

data class DefaultCredsHit(val product: String, val username: String, val password: String)

/**
 * Tests curated default-credential pairs against an HTTP Basic-Auth admin panel -- same
 * batched-concurrent-probe shape as [com.greyrecon.app.engine.snmp.SnmpClient.bruteForceCommunity],
 * extended from UDP/SNMP to HTTP.
 *
 * Bundled asset (`assets/default_creds.csv`) is the real `ihebski/DefaultCreds-cheat-sheet` (MIT,
 * verified via the repo's own LICENSE file), ~3766 real vendor/username/password rows, not a
 * fabricated or sampled subset.
 *
 * Opt-in, per-device, same reasoning as [SnmpClient.bruteForceCommunity] and [ExposureChecker]:
 * this is real credential-testing traffic against a specific target, not something to fire
 * automatically during a routine scan. Meant to follow up an [ExposureChecker] hit that already
 * found an exposed admin panel (e.g. Tomcat Manager) -- confirming a *default* credential still
 * works is a materially different, higher-severity finding than just "the panel is reachable."
 */
object DefaultCredsChecker {

    private val client = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(4, TimeUnit.SECONDS)
        .build()

    /** [product] matches CSV rows case-insensitively as a substring, e.g. "Tomcat" matches both
     * "Apache" and "Apache Tomcat Host Manager (web)" rows in the real sheet. */
    fun candidatesFor(context: Context, product: String): List<Pair<String, String>> {
        val matches = mutableListOf<Pair<String, String>>()
        context.assets.open("default_creds.csv").bufferedReader().useLines { lines ->
            lines.drop(1).forEach { line -> // header: productvendor,username,password
                val parts = line.split(",")
                if (parts.size != 3) return@forEach
                if (!parts[0].contains(product, ignoreCase = true)) return@forEach
                val user = if (parts[1] == "<blank>") "" else parts[1]
                val pass = if (parts[2] == "<blank>") "" else parts[2]
                matches.add(user to pass)
            }
        }
        return matches
    }

    suspend fun tryDefaults(
        baseUrl: String,
        path: String,
        product: String,
        candidates: List<Pair<String, String>>,
        batchSize: Int = 8,
    ): Result<DefaultCredsHit> = withContext(Dispatchers.IO) {
        candidates.chunked(batchSize).forEach { batch ->
            val results = batch.map { (user, pass) -> async { Triple(user, pass, probe(baseUrl, path, user, pass)) } }.awaitAll()
            results.firstOrNull { (_, _, success) -> success }?.let { (user, pass, _) ->
                return@withContext Result.success(DefaultCredsHit(product, user, pass))
            }
        }
        Result.failure(IOException("No default credential worked for $product ($baseUrl$path, tried ${candidates.size})"))
    }

    private fun probe(baseUrl: String, path: String, username: String, password: String): Boolean =
        try {
            val request = Request.Builder()
                .url("$baseUrl$path")
                .header("Authorization", Credentials.basic(username, password))
                .get()
                .build()
            client.newCall(request).execute().use { it.code in 200..299 }
        } catch (_: Exception) {
            false
        }
}
