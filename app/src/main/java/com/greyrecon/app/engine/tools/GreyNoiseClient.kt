package com.greyrecon.app.engine.tools

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

data class GreyNoiseResult(
    val ip: String,
    val noise: Boolean,
    val riot: Boolean,
    val classification: String?,
    val name: String?,
    val lastSeen: String?,
    val message: String?,
)

/**
 * GreyNoise Community API -- tells you whether a *public* IP is known internet background-noise
 * (mass scanners, crawlers) vs. something more targeted, plus RIOT (known-benign business service)
 * classification and the actor name. Defensive triage: when correlating an external IP a device on
 * the network is talking to, this answers "is this just internet noise or worth a closer look."
 *
 * Free; an API key (optional, from Settings) raises the daily lookup limit. A 404 here is a normal,
 * meaningful answer -- "this IP has not been observed", i.e. not a known scanner -- not an error.
 */
class GreyNoiseClient(
    private val apiKey: String?,
    private val client: OkHttpClient = defaultClient,
) {

    suspend fun lookup(ip: String): Result<GreyNoiseResult> = withContext(Dispatchers.IO) {
        try {
            val builder = Request.Builder()
                .url("https://api.greynoise.io/v3/community/${ip.trim()}")
                .header("Accept", "application/json")
            if (!apiKey.isNullOrBlank()) builder.header("key", apiKey)

            client.newCall(builder.get().build()).execute().use { response ->
                val body = response.body?.string()
                if (response.code == 429) return@withContext Result.failure(IOException("GreyNoise rate limit hit -- add a free GreyNoise API key in Settings for more lookups/day."))
                if (response.code == 401) return@withContext Result.failure(IOException("GreyNoise rejected the API key."))
                if (body.isNullOrBlank()) return@withContext Result.failure(IOException("Empty GreyNoise response"))
                val obj = json.parseToJsonElement(body).jsonObject
                Result.success(
                    GreyNoiseResult(
                        ip = obj["ip"]?.jsonPrimitive?.content ?: ip,
                        noise = obj["noise"]?.jsonPrimitive?.content?.toBoolean() ?: false,
                        riot = obj["riot"]?.jsonPrimitive?.content?.toBoolean() ?: false,
                        classification = obj["classification"]?.jsonPrimitive?.content,
                        name = obj["name"]?.jsonPrimitive?.content,
                        lastSeen = obj["last_seen"]?.jsonPrimitive?.content,
                        message = obj["message"]?.jsonPrimitive?.content,
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
