package com.greyrecon.app.ai.agent

import com.greyrecon.app.mcp.ToolDef
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Agent loop for Anthropic's Messages API, which uses a different tool-calling shape than the
 * OpenAI-compatible providers: content is an array of typed blocks (text / tool_use / tool_result)
 * rather than flat messages with a separate tool_calls field, the system prompt is top-level, and
 * tool schemas live under `input_schema`. Same loop shape as [OpenAiAgentBackend] otherwise.
 *
 * Structurally complete but, unlike the OpenAI path, not yet exercised against a live Anthropic key
 * (see GreyRecon.md). Kept model id in sync with [com.greyrecon.app.ai.AnthropicProvider].
 */
class AnthropicAgentBackend(
    private val apiKey: String,
    private val model: String = "claude-sonnet-5",
    private val executor: ToolExecutor,
    private val client: OkHttpClient = defaultClient,
) : AgentBackend {

    private val messages = mutableListOf<AnthMessage>()
    private val tools = executor.definitions().map { it.toAnthropicTool() }

    override suspend fun send(userText: String, onEvent: (AgentEvent) -> Unit) = withContext(Dispatchers.IO) {
        messages.add(AnthMessage(role = "user", content = listOf(Block(type = "text", text = userText))))

        repeat(AGENT_MAX_TOOL_ROUNDS) {
            val response = try {
                callModel()
            } catch (e: Exception) {
                onEvent(AgentEvent.Failed(e.message ?: "Request failed"))
                return@withContext
            }

            // Preserve the assistant's exact block list (text + tool_use) as one turn of history.
            messages.add(AnthMessage(role = "assistant", content = response.content))

            val toolUses = response.content.filter { it.type == "tool_use" }
            if (toolUses.isEmpty()) {
                val text = response.content.filter { it.type == "text" }.mapNotNull { it.text }.joinToString("\n").trim()
                onEvent(AgentEvent.Answer(text.ifEmpty { "(no answer)" }))
                return@withContext
            }

            val resultBlocks = toolUses.map { tu ->
                onEvent(AgentEvent.ToolStart(tu.name ?: "?", argsPreview(tu.input)))
                val result = executor.execute(tu.name ?: "", tu.input)
                onEvent(AgentEvent.ToolEnd(tu.name ?: "?", result.lineOne()))
                Block(type = "tool_result", toolUseId = tu.id, content = result)
            }
            messages.add(AnthMessage(role = "user", content = resultBlocks))
        }

        onEvent(AgentEvent.Failed("Stopped after $AGENT_MAX_TOOL_ROUNDS tool rounds without a final answer."))
    }

    private fun callModel(): MessagesResponse {
        val body = json.encodeToString(
            MessagesRequest(
                model = model,
                maxTokens = 1024,
                system = AGENT_SYSTEM_PROMPT,
                tools = tools,
                messages = messages.toList(),
            )
        )
        val request = Request.Builder()
            .url("https://api.anthropic.com/v1/messages")
            .addHeader("x-api-key", apiKey)
            .addHeader("anthropic-version", "2023-06-01")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(request).execute().use { response ->
            val raw = response.body?.string()
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}: ${raw?.take(300) ?: "no body"}")
            if (raw.isNullOrBlank()) throw IOException("Empty response")
            return json.decodeFromString<MessagesResponse>(raw)
        }
    }

    private fun argsPreview(input: JsonObject?): String =
        input?.entries?.joinToString(", ") { (k, v) -> "$k=${v.toString().trim('"')}" }.orEmpty()

    private fun String.lineOne(): String = trim().lineSequence().firstOrNull()?.take(80).orEmpty()

    private fun ToolDef.toAnthropicTool(): AnthTool {
        val props = buildJsonObject {
            parameters.forEach { (param, desc) ->
                put(param, buildJsonObject {
                    put("type", "string")
                    put("description", desc)
                })
            }
        }
        val schema = buildJsonObject {
            put("type", "object")
            put("properties", props)
            put("required", buildJsonArray { required.forEach { add(JsonPrimitive(it)) } })
        }
        return AnthTool(name = name, description = description, inputSchema = schema)
    }

    @Serializable
    private data class MessagesRequest(
        val model: String,
        @SerialName("max_tokens") val maxTokens: Int,
        val system: String,
        val tools: List<AnthTool>,
        val messages: List<AnthMessage>,
    )

    @Serializable
    private data class AnthTool(
        val name: String,
        val description: String,
        @SerialName("input_schema") val inputSchema: JsonObject,
    )

    @Serializable
    private data class AnthMessage(val role: String, val content: List<Block>)

    @Serializable
    private data class Block(
        val type: String,
        val text: String? = null,
        val id: String? = null,
        val name: String? = null,
        val input: JsonObject? = null,
        @SerialName("tool_use_id") val toolUseId: String? = null,
        val content: String? = null,
    )

    @Serializable
    private data class MessagesResponse(
        val content: List<Block>,
        @SerialName("stop_reason") val stopReason: String? = null,
    )

    private companion object {
        val json = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true }
        val defaultClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .build()
    }
}
