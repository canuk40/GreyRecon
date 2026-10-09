package com.greyrecon.app.integrations

import android.content.Context
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * Posts a JSON event to a user-supplied URL when something happens on a watched network.
 *
 * This is the same instinct that produced the MCP server: the people most likely to pay for this
 * app already run their own monitoring, and the most valuable thing GreyRecon can do is hand them
 * an event rather than make them come and look at a phone screen. A webhook reaches Home
 * Assistant, n8n, a Discord relay, ntfy, or anything else the user already has, without GreyRecon
 * needing to integrate with any of them specifically.
 *
 * Security choices worth stating, because this sends the user's network data off-device:
 *  - **HTTPS only.** A plaintext webhook would put a list of everything on someone's home network
 *    onto the wire for anyone on the path. Refused rather than warned about.
 *  - **No payload beyond the event.** Device vendor, IP, and the event type. Not the full
 *    inventory, not MAC addresses - a webhook endpoint is frequently a third-party relay.
 *  - **Opt in, off by default, and silent on failure.** A broken webhook must never become an
 *    error dialog during a background scan.
 */
class WebhookNotifier(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .writeTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    var url: String?
        get() = prefs.getString(KEY_URL, null)
        set(value) {
            val cleaned = value?.trim().orEmpty()
            prefs.edit().apply {
                if (cleaned.isBlank()) remove(KEY_URL) else putString(KEY_URL, cleaned)
            }.apply()
        }

    val isConfigured: Boolean get() = isAcceptable(url)

    /** HTTPS only, and a parseable absolute URL. */
    fun isAcceptable(candidate: String?): Boolean {
        val value = candidate?.trim() ?: return false
        return value.startsWith("https://", ignoreCase = true) &&
            runCatching { java.net.URL(value).host.isNotBlank() }.getOrDefault(false)
    }

    /**
     * Fire-and-forget. Returns the HTTP status when one came back, null when the post failed or
     * no webhook is configured -- callers in a background worker should ignore it either way.
     */
    suspend fun post(
        event: String,
        networkLabel: String,
        detail: String,
        deviceIp: String? = null,
        deviceLabel: String? = null,
    ): Int? = withContext(Dispatchers.IO) {
        val endpoint = url?.takeIf { isAcceptable(it) } ?: return@withContext null

        val body = JSONObject().apply {
            put("source", "greyrecon")
            put("event", event)
            put("network", networkLabel)
            put("detail", detail)
            put("timestamp", System.currentTimeMillis())
            deviceIp?.let { put("deviceIp", it) }
            deviceLabel?.let { put("deviceLabel", it) }
        }.toString()

        runCatching {
            client.newCall(
                Request.Builder()
                    .url(endpoint)
                    .post(body.toRequestBody(JSON))
                    .build()
            ).execute().use { it.code }
        }.getOrNull()
    }

    private companion object {
        const val PREFS = "greyrecon_integrations"
        const val KEY_URL = "webhook_url"
        val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
