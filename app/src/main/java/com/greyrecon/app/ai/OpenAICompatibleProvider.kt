package com.greyrecon.app.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
 * Covers every provider whose API is OpenAI-compatible (chat/completions shape,
 * `Authorization: Bearer <key>` auth): OpenAI itself, DeepSeek, Groq, OpenRouter,
 * and Ollama's local OpenAI-compat endpoint. Only [baseUrl] changes between them
 * -- one implementation instead of five near-identical ones.
 */
class OpenAICompatibleProvider(
    override val type: AIProviderType,
    private val apiKey: String,
    private val baseUrl: String,
    private val model: String,
    private val client: OkHttpClient = defaultClient,
) : AIProvider {

    override suspend fun complete(prompt: String, context: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val requestBody = json.encodeToString(
                ChatRequest(
                    model = model,
                    messages = listOf(
                        Message(role = "system", content = "You are a network security assistant. Explain scan results and vulnerabilities in plain, concise language."),
                        Message(role = "user", content = "$prompt\n\nScan context:\n$context"),
                    ),
                )
            )

            val request = Request.Builder()
                .url("${baseUrl.trimEnd('/')}/chat/completions")
                .apply { if (apiKey.isNotBlank()) addHeader("Authorization", "Bearer $apiKey") }
                .post(requestBody.toRequestBody("application/json".toMediaType()))
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result.failure(IOException("${type.name} request failed: HTTP ${response.code} ${response.body?.string()}"))
                }
                val body = response.body?.string() ?: return@withContext Result.failure(IOException("Empty response body"))
                val parsed = json.decodeFromString<ChatResponse>(body)
                val text = parsed.choices.firstOrNull()?.message?.content
                    ?: return@withContext Result.failure(IOException("No completion in response"))
                Result.success(text)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    @Serializable
    private data class ChatRequest(val model: String, val messages: List<Message>, val temperature: Double = 0.3)

    @Serializable
    private data class Message(val role: String, val content: String)

    @Serializable
    private data class ChatResponse(val choices: List<Choice>)

    @Serializable
    private data class Choice(val message: Message)

    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        private val defaultClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()

        /** Known base URLs for each OpenAI-compatible provider. Ollama's is the local default; override via AIProviderConfig.baseUrl if it's running elsewhere. */
        fun baseUrlFor(type: AIProviderType): String = when (type) {
            AIProviderType.OPENAI -> "https://api.openai.com/v1"
            AIProviderType.DEEPSEEK -> "https://api.deepseek.com/v1"
            AIProviderType.GROQ -> "https://api.groq.com/openai/v1"
            AIProviderType.OPENROUTER -> "https://openrouter.ai/api/v1"
            AIProviderType.OLLAMA -> "http://localhost:11434/v1"
            AIProviderType.ANTHROPIC -> error("Anthropic is not OpenAI-compatible -- use AnthropicProvider")
        }
    }
}
