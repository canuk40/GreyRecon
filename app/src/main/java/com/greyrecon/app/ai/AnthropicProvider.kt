package com.greyrecon.app.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Anthropic's Messages API has a different shape than the OpenAI-compatible
 * providers: x-api-key header instead of Authorization: Bearer, a required
 * anthropic-version header, and system prompt is a top-level field instead of
 * a "system" role message.
 */
class AnthropicProvider(
    private val apiKey: String,
    private val model: String = "claude-sonnet-5",
    private val client: OkHttpClient = defaultClient,
) : AIProvider {

    override val type = AIProviderType.ANTHROPIC

    override suspend fun complete(prompt: String, context: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val requestBody = json.encodeToString(
                MessagesRequest(
                    model = model,
                    maxTokens = 1024,
                    system = "You are a network security assistant. Explain scan results and vulnerabilities in plain, concise language.",
                    messages = listOf(Message(role = "user", content = "$prompt\n\nScan context:\n$context")),
                )
            )

            val request = Request.Builder()
                .url("https://api.anthropic.com/v1/messages")
                .addHeader("x-api-key", apiKey)
                .addHeader("anthropic-version", "2023-06-01")
                .post(requestBody.toRequestBody("application/json".toMediaType()))
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result.failure(IOException("Anthropic request failed: HTTP ${response.code} ${response.body?.string()}"))
                }
                val body = response.body?.string() ?: return@withContext Result.failure(IOException("Empty response body"))
                val parsed = json.decodeFromString<MessagesResponse>(body)
                val text = parsed.content.firstOrNull()?.text
                    ?: return@withContext Result.failure(IOException("No completion in response"))
                Result.success(text)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    @Serializable
    private data class MessagesRequest(
        val model: String,
        @SerialName("max_tokens") val maxTokens: Int,
        val system: String,
        val messages: List<Message>,
    )

    @Serializable
    private data class Message(val role: String, val content: String)

    @Serializable
    private data class MessagesResponse(val content: List<ContentBlock>)

    @Serializable
    private data class ContentBlock(val text: String)

    companion object {
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        private val defaultClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }
}
